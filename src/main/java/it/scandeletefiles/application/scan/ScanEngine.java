package it.scandeletefiles.application.scan;

import it.scandeletefiles.application.CancellableOperation;
import it.scandeletefiles.application.OperationObserver;
import it.scandeletefiles.application.OperationProgress;
import it.scandeletefiles.application.OperationResult;
import it.scandeletefiles.application.OperationStatus;
import it.scandeletefiles.application.csv.CsvFormat;
import it.scandeletefiles.application.ScanApplicationService;
import it.scandeletefiles.application.ScanCommand;
import it.scandeletefiles.bootstrap.PlatformAdapters;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
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
import java.util.Map;
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
        final Map<String, Integer> excludedByFormat = new TreeMap<>();
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
                                boolean formatoAccettato = command.scanAllFiles()
                                        || extensions.contains(format);
                                if (!formatoAccettato) {
                                    context.excludedByFormat.merge(format, 1, Integer::sum);
                                    notifyProgress(OperationProgress.indeterminate(
                                            context.visited,
                                            "File esaminati: " + context.visited
                                                    + ", corrispondenti: " + context.matching.size()
                                                    + ", esclusi: " + context.excludedByFormat.values().stream()
                                                    .mapToInt(Integer::intValue).sum()
                                    ));
                                    return FileVisitResult.CONTINUE;
                                }
                                Instant modified = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant();
                                if (!modified.isBefore(threshold)) {
                                    notifyProgress(OperationProgress.indeterminate(
                                            context.visited,
                                            "File esaminati: " + context.visited
                                                    + ", corrispondenti: " + context.matching.size()
                                                    + ", esclusi: " + context.excludedByFormat.values().stream()
                                                    .mapToInt(Integer::intValue).sum()
                                    ));
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
                            public FileVisitResult visitFileFailed(Path file, IOException exc)
                                    throws IOException {
                                context.accessErrors.add(file.toString() + ": " + exc.getMessage());
                                return FileVisitResult.CONTINUE;
                            }
                        });
                complete(start, clock, context);
            } catch (ScanCancelledException exc) {
                cancelled(start, clock.instant(), context);
            } catch (Exception exc) {
                failed(start, clock.instant(), context, exc);
            }
        }

        private void checkCancelled() {
            if (cancelled.get()) {
                throw new ScanCancelledException();
            }
        }

        private void complete(Instant start, Clock clock, ScanContext context) throws IOException {
            Instant end = clock.instant();
            notifyProgress(OperationProgress.indeterminate(context.visited,
                    "Scrittura file CSV in corso..."));
            List<CsvRow> rows = context.matching.stream()
                    .sorted(Comparator.comparing(CsvRow::fullPath))
                    .collect(Collectors.toUnmodifiableList());
            Path manifest = writeCsv(command.manifestDestination(), rows);
            notifyProgress(OperationProgress.indeterminate(context.visited,
                    "Scrittura log di audit in corso..."));
            writeAudit(manifest, OperationStatus.COMPLETED, start, end, context, null, null);
            notifyFinished(new OperationResult(OperationStatus.COMPLETED,
                    "Scansione completata: " + rows.size() + " file corrispondenti. File CSV: " + manifest));
        }

        private void cancelled(Instant start, Instant end, ScanContext context) {
            try {
                // Scrivi il file CSV con i dati parziali raccolti fino all'annullamento
                List<CsvRow> rows = context.matching.stream()
                        .sorted(Comparator.comparing(CsvRow::fullPath))
                        .collect(Collectors.toUnmodifiableList());
                Path manifest = writeCsv(command.manifestDestination(), rows);
                // Scrivi l'audit accanto al CSV effettivamente pubblicato
                writeAudit(manifest, OperationStatus.CANCELLED, start, end, context,
                        "Annullata dall'utente prima del completamento", null);
            } catch (IOException exc) {
                // il log di audit deve essere prodotto anche quando la scansione viene annullata
            }
            notifyFinished(new OperationResult(OperationStatus.CANCELLED, "Scansione annullata"));
        }

        private void failed(Instant start, Instant end, ScanContext context, Exception exc) {
            Path audit = writeFailureAudit(start, end, context, exc);
            String detail = exceptionDescription(exc);
            String auditMessage = audit == null
                    ? " Impossibile scrivere anche il log diagnostico."
                    : " Log diagnostico: " + audit;
            notifyFinished(new OperationResult(OperationStatus.FAILED,
                    "Scansione fallita: " + detail + "." + auditMessage));
        }

        private Path writeCsv(Path requestedManifest, List<CsvRow> rows) throws IOException {
            Path parent = requestedManifest.getParent() == null ? Path.of(".") : requestedManifest.getParent();
            Path temp = parent.resolve(requestedManifest.getFileName().toString() + ".tmp." + System.nanoTime());
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
                    writer.write(CsvFormat.escape(row.fileName(), CSV_SEPARATOR));
                    writer.write(CSV_SEPARATOR);
                    writer.write(CsvFormat.escape(row.folderPath(), CSV_SEPARATOR));
                    writer.write(CSV_SEPARATOR);
                    writer.write(CsvFormat.escape(row.fullPath(), CSV_SEPARATOR));
                    writer.write(CSV_SEPARATOR);
                    writer.write(CsvFormat.escape(row.format(), CSV_SEPARATOR));
                    writer.write(CSV_SEPARATOR);
                    writer.write(CsvFormat.escape(row.lastModified(), CSV_SEPARATOR));
                    writer.write(CSV_SEPARATOR);
                    writer.write(CsvFormat.escape(DELETE_YES, CSV_SEPARATOR));
                    writer.write('\n');
                }
            }
            for (int sequence = 1; ; sequence++) {
                Path candidate = uniqueName(requestedManifest, sequence);
                if (Files.exists(candidate) || Files.exists(auditPath(candidate))) {
                    continue;
                }
                try {
                    Files.move(temp, candidate, StandardCopyOption.REPLACE_EXISTING);
                    return candidate;
                } catch (FileAlreadyExistsException collision) {
                    // Un'altra scansione ha pubblicato lo stesso nome tra il controllo e lo sposto:
                    // prova il suffisso successivo, temp è ancora disponibile per il retry.
                } catch (NoSuchFileException gone) {
                    throw new IOException("Impossibile pubblicare il file CSV: file temporaneo non disponibile",
                            gone);
                }
            }
        }

        private static Path availableAuditName(Path requestedManifest) {
            for (int sequence = 1; ; sequence++) {
                Path candidate = uniqueName(requestedManifest, sequence);
                if (!Files.exists(auditPath(candidate))) {
                    return candidate;
                }
            }
        }

        private static Path auditPath(Path manifest) {
            return manifest.resolveSibling(manifest.getFileName().toString() + ".log");
        }

        private static Path uniqueName(Path requested, int sequence) {
            if (sequence == 1) {
                return requested;
            }
            String name = requested.getFileName().toString();
            int extension = name.lastIndexOf('.');
            String stem = extension > 0 ? name.substring(0, extension) : name;
            String suffix = extension > 0 ? name.substring(extension) : "";
            return requested.resolveSibling(stem + "_" + sequence + suffix);
        }

        private void writeAudit(Path manifest, OperationStatus status, Instant start, Instant end,
                                ScanContext context, String note, Throwable failure) throws IOException {
            Clock clock = platform.clock();
            ZoneId zone = clock.getZone();
            Path audit = auditPath(manifest);
            List<String> lines = new ArrayList<>();
            lines.add("ScanDeleteFiles — Audit di scansione");
            lines.add("");
            lines.add("Stato: " + CsvFormat.statoItaliano(status));
            lines.add("Inizio: " + LocalDateTime.ofInstant(start, zone).format(TIMESTAMP_FORMAT));
            lines.add("Fine: " + LocalDateTime.ofInstant(end, zone).format(TIMESTAMP_FORMAT));
            lines.add("Durata: " + String.format(Locale.ITALIAN, "%.3f s",
                    (end.toEpochMilli() - start.toEpochMilli()) / 1000.0));
            lines.add("Cartella analizzata: " + command.root());
            lines.add("Data soglia esclusiva: " + command.thresholdDate().format(DATE_FORMAT));
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
            if (!context.excludedByFormat.isEmpty()) {
                lines.add("");
                lines.add("Esclusioni per formato (file scartati perché non inclusi nei filtri):");
                long totalExcluded = context.excludedByFormat.values().stream().mapToLong(Long::valueOf).sum();
                for (var entry : context.excludedByFormat.entrySet()) {
                    lines.add("  " + entry.getKey() + ": " + entry.getValue()
                            + " (" + String.format(Locale.ITALIAN, "%.1f%%",
                            100.0 * entry.getValue() / context.visited) + " del totale visitato)");
                }
                lines.add("  Totale esclusi: " + totalExcluded
                        + " (" + String.format(Locale.ITALIAN, "%.1f%%",
                        100.0 * totalExcluded / context.visited) + " del totale visitato)");
            } else if (context.visited > 0) {
                lines.add("");
                lines.add("Esclusioni per formato: nessuna (tutti i file visitati corrispondono ai filtri selezionati)");
            }
            if (note != null) {
                lines.add("");
                lines.add("Note: " + note);
            }
            if (failure != null) {
                lines.add("");
                lines.add("Eccezione: " + failure.getClass().getName()
                        + (failure.getMessage() == null ? "" : ": " + failure.getMessage()));
                lines.add("Stack trace:");
                StringWriter trace = new StringWriter();
                failure.printStackTrace(new PrintWriter(trace));
                trace.toString().lines().forEach(line -> lines.add("  " + line));
            }
            Files.write(audit, lines, StandardCharsets.UTF_8);
        }

        private Path writeFailureAudit(Instant start, Instant end, ScanContext context, Exception failure) {
            String fileName = command.manifestDestination().getFileName().toString();
            List<Path> locations = List.of(
                    command.manifestDestination(),
                    command.root().resolve(fileName),
                    Path.of(System.getProperty("user.home", ".")).resolve(fileName));
            IOException writeFailure = null;
            for (Path location : locations.stream().distinct().toList()) {
                Path available = availableAuditName(location);
                try {
                    writeAudit(available, OperationStatus.FAILED, start, end, context,
                            exceptionDescription(failure), failure);
                    return auditPath(available);
                } catch (IOException exc) {
                    if (writeFailure == null) {
                        writeFailure = exc;
                    } else {
                        writeFailure.addSuppressed(exc);
                    }
                }
            }
            if (writeFailure != null) {
                failure.addSuppressed(writeFailure);
            }
            return null;
        }

        private static String exceptionDescription(Throwable failure) {
            String message = failure.getMessage();
            return failure.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
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
