package it.scandeletefiles.application.scan;

import it.scandeletefiles.application.CancellableOperation;
import it.scandeletefiles.application.OperationObserver;
import it.scandeletefiles.application.OperationProgress;
import it.scandeletefiles.application.OperationResult;
import it.scandeletefiles.application.OperationStatus;
import it.scandeletefiles.application.ScanApplicationService;
import it.scandeletefiles.application.ScanCommand;
import it.scandeletefiles.bootstrap.PlatformAdapters;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public final class ScanEngine implements ScanApplicationService {

    private static final char CSV_SEPARATOR = ';';
    private static final String DELETE_YES = "Yes";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    private static final String[] COLUMNS = {
            "Nome file",
            "Path cartella",
            "Path completo",
            "Formato file",
            "Data ultima modifica",
            "Delete"
    };

    private final PlatformAdapters platform;

    public ScanEngine(PlatformAdapters platform) {
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    @Override
    public CancellableOperation start(ScanCommand command, OperationObserver observer) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(observer, "observer");
        ScanTask task = new ScanTask(command, platform, observer);
        Thread thread = new Thread(task, "scan-delete-files-scan");
        thread.setDaemon(true);
        thread.start();
        return task;
    }

    private static final class ScanContext {
        int visited;
        final List<CsvRow> matching = new ArrayList<>();
        final List<String> accessErrors = new ArrayList<>();
    }

    private record CsvRow(String fileName, String folderPath, String fullPath, String format, String lastModified) {
    }

    private static final class ScanCancelledException extends RuntimeException {
        ScanCancelledException() {
        }
    }

    private static final class ScanTask implements CancellableOperation, Runnable {
        private final ScanCommand command;
        private final PlatformAdapters platform;
        private final OperationObserver observer;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private ScanTask(ScanCommand command, PlatformAdapters platform, OperationObserver observer) {
            this.command = command;
            this.platform = platform;
            this.observer = observer;
        }

        @Override
        public void run() {
            Clock clock = platform.clock();
            Instant start = clock.instant();
            final ScanContext context = new ScanContext();
            try {
                notifyProgress(OperationProgress.indeterminate(0, "Avvio scansione"));
                Instant threshold = command.thresholdDate().atStartOfDay(clock.getZone()).toInstant();
                Set<String> extensions = command.extensions().stream()
                        .map(String::toUpperCase)
                        .collect(Collectors.toUnmodifiableSet());

                Files.walkFileTree(command.root(), EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                        new SimpleFileVisitor<Path>() {
                            @Override
                            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                                    throws IOException {
                                checkCancelled();
                                // I collegamenti simbolici, le junction e i punti di riparsione non
                                // vengono attraversati: con attrs letti senza seguire il link,
                                // non sono né directory né link simbolici e quindi si escludono qui.
                                if (attrs.isSymbolicLink() || !attrs.isDirectory()) {
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                                if (!command.includeHidden() && Files.isHidden(dir)) {
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                    throws IOException {
                                checkCancelled();
                                // I link simbolici (e le junction esposte come rilegature di
                                // directory) non sono file ordinari: non entrano nel manifest.
                                if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                                    return FileVisitResult.CONTINUE;
                                }
                                if (!command.includeHidden() && Files.isHidden(file)) {
                                    return FileVisitResult.CONTINUE;
                                }
                                context.visited++;
                                String format = extensionOf(file);
                                if (!extensions.contains(format)) {
                                    return FileVisitResult.CONTINUE;
                                }
                                Instant modified = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant();
                                if (!modified.isBefore(threshold)) {
                                    return FileVisitResult.CONTINUE;
                                }
                                context.matching.add(new CsvRow(
                                        file.getFileName().toString(),
                                        folderPath(file),
                                        file.toString(),
                                        format,
                                        LocalDateTime.ofInstant(modified, clock.getZone()).format(DATE_FORMAT)
                                ));
                                notifyProgress(OperationProgress.indeterminate(
                                        context.visited,
                                        "File esaminati: " + context.visited + ", corrispondenti: " + context.matching.size()
                                ));
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                                context.accessErrors.add(file.toString() + ": " + exc.getMessage());
                                return FileVisitResult.CONTINUE;
                            }
                        });
                complete(start, clock, context);
            } catch (ScanCancelledException exc) {
                cancelled(start, clock.instant(), context);
            } catch (Exception exc) {
                failed(start, clock.instant(), exc);
            }
        }

        private void checkCancelled() {
            if (cancelled.get()) {
                throw new ScanCancelledException();
            }
        }

        private void complete(Instant start, Clock clock, ScanContext context) throws IOException {
            Instant end = clock.instant();
            List<CsvRow> rows = context.matching.stream()
                    .sorted(Comparator.comparing(CsvRow::fullPath))
                    .collect(Collectors.toUnmodifiableList());
            writeCsv(command.manifestDestination(), rows);
            writeAudit(command.manifestDestination(), OperationStatus.COMPLETED, start, end, context, null);
            notifyFinished(new OperationResult(OperationStatus.COMPLETED,
                    "Scansione completata: " + rows.size() + " file corrispondenti"));
        }

        private void cancelled(Instant start, Instant end, ScanContext context) {
            try {
                writeAudit(command.manifestDestination(), OperationStatus.CANCELLED, start, end, context,
                        "Annullata dall'utente prima del completamento");
            } catch (IOException exc) {
                // il log di audit deve essere prodotto anche quando la scansione viene annullata
            }
            notifyFinished(new OperationResult(OperationStatus.CANCELLED, "Scansione annullata"));
        }

        private void failed(Instant start, Instant end, Exception exc) {
            try {
                writeAudit(command.manifestDestination(), OperationStatus.FAILED, start, end, new ScanContext(),
                        exc.getMessage());
            } catch (IOException ioExc) {
                // il log di audit non \u00e8 scrivibile: l'esito \u00e8 comunque segnato come fallito
            }
            notifyFinished(new OperationResult(OperationStatus.FAILED, "Scansione fallita: " + exc.getMessage()));
        }

        private void writeCsv(Path manifest, List<CsvRow> rows) throws IOException {
            Path parent = manifest.getParent() == null ? Path.of(".") : manifest.getParent();
            Path temp = parent.resolve(manifest.getFileName().toString() + ".tmp." + System.nanoTime());
            try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                writer.write('\uFEFF');
                for (int i = 0; i < COLUMNS.length; i++) {
                    if (i > 0) {
                        writer.write(CSV_SEPARATOR);
                    }
                    writer.write(COLUMNS[i]);
                }
                writer.write('\n');
                for (CsvRow row : rows) {
                    writer.write(escapeCsv(row.fileName()));
                    writer.write(CSV_SEPARATOR);
                    writer.write(escapeCsv(row.folderPath()));
                    writer.write(CSV_SEPARATOR);
                    writer.write(escapeCsv(row.fullPath()));
                    writer.write(CSV_SEPARATOR);
                    writer.write(escapeCsv(row.format()));
                    writer.write(CSV_SEPARATOR);
                    writer.write(escapeCsv(row.lastModified()));
                    writer.write(CSV_SEPARATOR);
                    writer.write(DELETE_YES);
                    writer.write('\n');
                }
            }
            moveAtomically(temp, manifest);
        }

        private void moveAtomically(Path temp, Path manifest) throws IOException {
            try {
                Files.move(temp, manifest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | UnsupportedOperationException exc) {
                Files.move(temp, manifest, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private static String escapeCsv(String value) {
            if (value == null) {
                return "";
            }
            if (value.indexOf(CSV_SEPARATOR) >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0
                    || value.indexOf('\r') >= 0) {
                return '"' + value.replace("\"", "\"\"") + '"';
            }
            return value;
        }

        private void writeAudit(Path manifest, OperationStatus status, Instant start, Instant end,
                                ScanContext context, String note) throws IOException {
            Clock clock = platform.clock();
            ZoneId zone = clock.getZone();
            Path audit = manifest.resolveSibling(manifest.getFileName().toString() + ".log");
            List<String> lines = new ArrayList<>();
            lines.add("ScanDeleteFiles — Audit di scansione");
            lines.add("");
            lines.add("Stato: " + statoItaliano(status));
            lines.add("Inizio: " + LocalDateTime.ofInstant(start, zone).format(TIMESTAMP_FORMAT));
            lines.add("Fine: " + LocalDateTime.ofInstant(end, zone).format(TIMESTAMP_FORMAT));
            lines.add("Durata: " + String.format(Locale.ITALIAN, "%.3f s",
                    (end.toEpochMilli() - start.toEpochMilli()) / 1000.0));
            lines.add("Elementi visitati: " + context.visited);
            lines.add("File corrispondenti: " + context.matching.size());
            lines.add("Percentuali per formato:");
            if (context.matching.isEmpty()) {
                lines.add("  (nessun file corrispondente)");
            } else {
                TreeMap<String, Long> perFormat = new TreeMap<>();
                for (CsvRow row : context.matching) {
                    perFormat.merge(row.format(), 1L, Long::sum);
                }
                double total = context.matching.size();
                for (var entry : perFormat.entrySet()) {
                    lines.add("  " + entry.getKey() + ": "
                            + String.format(Locale.ITALIAN, "%.1f%%", 100.0 * entry.getValue() / total));
                }
            }
            lines.add("Errori di accesso: " + context.accessErrors.size());
            for (String error : context.accessErrors) {
                lines.add("  " + error);
            }
            if (note != null) {
                lines.add("");
                lines.add("Note: " + note);
            }
            Files.write(audit, lines, StandardCharsets.UTF_8);
        }

        private static String statoItaliano(OperationStatus status) {
            return switch (status) {
                case COMPLETED -> "COMPLETATA";
                case CANCELLED -> "ANNULLATA";
                case FAILED -> "FALLITA";
            };
        }

        private static String folderPath(Path file) {
            Path parent = file.getParent();
            return parent == null ? "" : parent.toString();
        }

        private static String extensionOf(Path file) {
            String name = file.getFileName().toString();
            int dot = name.lastIndexOf('.');
            if (dot <= 0 || dot == name.length() - 1) {
                return "";
            }
            return name.substring(dot + 1).toUpperCase(Locale.ROOT);
        }

        private void notifyProgress(OperationProgress progress) {
            try {
                observer.onProgress(progress);
            } catch (Throwable ignored) {
            }
        }

        private void notifyFinished(OperationResult result) {
            try {
                observer.onFinished(result);
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void requestCancellation() {
            cancelled.set(true);
        }

        @Override
        public boolean isCancellationRequested() {
            return cancelled.get();
        }
    }
}
