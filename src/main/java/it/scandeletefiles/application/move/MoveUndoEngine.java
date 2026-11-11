package it.scandeletefiles.application.move;

import it.scandeletefiles.application.CancellableOperation;
import it.scandeletefiles.application.OperationObserver;
import it.scandeletefiles.application.OperationProgress;
import it.scandeletefiles.application.OperationResult;
import it.scandeletefiles.application.OperationStatus;
import it.scandeletefiles.application.csv.CsvFormat;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MoveUndoEngine implements MoveUndoApplicationService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    private static final Object OUTPUT_LOCK = new Object();
    private static final Set<Path> RESERVED = new HashSet<>();
    private final Clock clock;
    private final MoveJournalCodec codec;

    public MoveUndoEngine(Clock clock) {
        this(clock, new MoveJournalCodec());
    }

    MoveUndoEngine(Clock clock, MoveJournalCodec codec) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.codec = java.util.Objects.requireNonNull(codec, "codec");
    }

    @Override
    public CancellableOperation start(MoveUndoCommand command, OperationObserver observer) {
        java.util.Objects.requireNonNull(command, "command");
        java.util.Objects.requireNonNull(observer, "observer");
        UndoTask task = new UndoTask(command, observer);
        Thread thread = new Thread(task, "scan-delete-files-move-undo");
        thread.setDaemon(true);
        thread.start();
        return task;
    }

    private record Outcome(Path original, Path target, String result, String detail) { }
    private record OutputPaths(Path report, Path audit) { }

    private final class UndoTask implements CancellableOperation, Runnable {
        private final MoveUndoCommand command;
        private final OperationObserver observer;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private OutputPaths outputs;

        UndoTask(MoveUndoCommand command, OperationObserver observer) {
            this.command = command;
            this.observer = observer;
        }

        @Override
        public void run() {
            Instant started = clock.instant();
            List<Outcome> outcomes = new ArrayList<>();
            int restored = 0;
            int errors = 0;
            try {
                outputs = reserveOutputs(command.journal());
                MoveJournal journal = codec.read(command.journal());
                validateJournalPaths(journal);
                int total = (int) journal.files().stream()
                        .filter(file -> file.state() != MoveJournal.FileState.RESTORED).count();
                notifyProgress(OperationProgress.determinate(0, total, "Avvio ripristino Move"));
                int processed = 0;
                for (int i = journal.files().size() - 1; i >= 0; i--) {
                    MoveJournal.FileRecord file = journal.files().get(i);
                    if (file.state() == MoveJournal.FileState.RESTORED) continue;
                    if (cancelled.get()) break;
                    try {
                        if (Files.exists(file.originalPath(), LinkOption.NOFOLLOW_LINKS)
                                && !Files.exists(file.targetPath(), LinkOption.NOFOLLOW_LINKS)
                                && reconcileRestoredOriginal(file)) {
                            journal.files().set(i, file.withState(MoveJournal.FileState.RESTORED));
                            codec.write(command.journal(), journal);
                            restored++;
                            outcomes.add(new Outcome(file.originalPath(), file.targetPath(), "Già ripristinato",
                                    "File già presente nella posizione originale con impronta valida"));
                        } else {
                            restoreFile(journal, file);
                            journal.files().set(i, file.withState(MoveJournal.FileState.RESTORED));
                            codec.write(command.journal(), journal);
                            restored++;
                            outcomes.add(new Outcome(file.originalPath(), file.targetPath(), "Ripristinato", "OK"));
                        }
                    } catch (IOException exc) {
                        errors++;
                        outcomes.add(new Outcome(file.originalPath(), file.targetPath(), "Errore", exc.getMessage()));
                    }
                    processed++;
                    notifyProgress(OperationProgress.determinate(processed, total,
                            "File ripristinati: " + restored + "; errori: " + errors));
                }
                List<String> directoryErrors = restoreDirectories(journal);
                errors += directoryErrors.size();
                boolean wasCancelled = cancelled.get();
                OperationStatus status = wasCancelled ? OperationStatus.CANCELLED
                        : errors == 0 ? OperationStatus.COMPLETED : OperationStatus.FAILED;
                List<String> auditErrors = new ArrayList<>();
                outcomes.stream().filter(outcome -> "Errore".equals(outcome.result()))
                        .map(Outcome::detail).forEach(auditErrors::add);
                auditErrors.addAll(directoryErrors);
                writeReport(outcomes);
                writeAudit(status, started, restored, errors, auditErrors);
                List<String> finalDirectoryErrors = restoreDirectories(journal);
                if (!finalDirectoryErrors.isEmpty()) {
                    errors += finalDirectoryErrors.size();
                    status = OperationStatus.FAILED;
                    auditErrors.addAll(finalDirectoryErrors);
                    writeAudit(status, started, restored, errors, auditErrors);
                    restoreDirectories(journal);
                }
                notifyFinished(new OperationResult(status, message(status, restored, errors)));
            } catch (Exception exc) {
                errors++;
                try {
                    if (outputs != null) {
                        writeReport(outcomes);
                        writeAudit(OperationStatus.FAILED, started, restored, errors, List.of(exc.getMessage()));
                    }
                } catch (IOException ignored) { }
                notifyFinished(new OperationResult(OperationStatus.FAILED,
                        "Ripristino Move fallito: " + exc.getMessage()));
            } finally {
                release(outputs);
            }
        }

        private void restoreFile(MoveJournal journal, MoveJournal.FileRecord file) throws IOException {
            MovePathSafety.requireContained(journal.sourceRoot(), file.originalPath(), true);
            MovePathSafety.requireContained(journal.destinationRoot(), file.targetPath(), false);
            if (Files.exists(file.originalPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Percorso originale già occupato: " + file.originalPath());
            }
            if (!Files.isRegularFile(file.targetPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Target assente o non è un file regolare: " + file.targetPath());
            }
            if (Files.size(file.targetPath()) != file.size()) {
                throw new IOException("Dimensione target modificata: " + file.targetPath());
            }
            if (!sha256(file.targetPath()).equalsIgnoreCase(file.sha256())) {
                throw new IOException("SHA-256 target modificato: " + file.targetPath());
            }
            FileTime originalTime = FileTime.from(Instant.parse(file.mtime()));
            String expectedTargetMtime = file.targetMtime().isBlank()
                    ? file.mtime() : file.targetMtime();
            if (!Files.getLastModifiedTime(file.targetPath(), LinkOption.NOFOLLOW_LINKS)
                    .equals(FileTime.from(Instant.parse(expectedTargetMtime)))) {
                throw new IOException("Data ultima modifica target cambiata: " + file.targetPath());
            }
            Path parent = file.originalPath().getParent();
            if (parent == null) throw new IOException("Percorso originale privo di cartella");
            MovePathSafety.requireContained(journal.sourceRoot(), file.originalPath(), true);
            Files.createDirectories(parent);
            MovePathSafety.requireContained(journal.sourceRoot(), file.originalPath(), true);
            try {
                Files.move(file.targetPath(), file.originalPath());
            } catch (FileAlreadyExistsException exc) {
                throw new IOException("Percorso originale occupato durante il ripristino: " + file.originalPath(), exc);
            }
            Files.setLastModifiedTime(file.originalPath(), originalTime);
        }

        private boolean reconcileRestoredOriginal(MoveJournal.FileRecord file) throws IOException {
            if (!Files.isRegularFile(file.originalPath(), LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file.originalPath()) != file.size()) {
                return false;
            }
            if (!sha256(file.originalPath()).equalsIgnoreCase(file.sha256())) {
                return false;
            }
            FileTime current = Files.getLastModifiedTime(file.originalPath(), LinkOption.NOFOLLOW_LINKS);
            FileTime original = FileTime.from(Instant.parse(file.mtime()));
            if (current.equals(original)) {
                return true;
            }
            if (!file.targetMtime().isBlank()
                    && current.equals(FileTime.from(Instant.parse(file.targetMtime())))) {
                Files.setLastModifiedTime(file.originalPath(), original);
                return true;
            }
            return false;
        }

        private List<String> restoreDirectories(MoveJournal journal) {
            List<String> errors = new ArrayList<>();
            journal.directories().stream()
                    .filter(directory -> directory.side() == MoveJournal.DirectorySide.DESTINATION
                            && !directory.existedBefore())
                    .sorted(Comparator.comparingInt((MoveJournal.DirectoryRecord d) -> d.path().getNameCount()).reversed())
                    .forEach(directory -> {
                        try {
                            MovePathSafety.requireContained(journal.destinationRoot(), directory.path(), true);
                            if (Files.isDirectory(directory.path(), LinkOption.NOFOLLOW_LINKS)
                                    && isEmpty(directory.path())) Files.delete(directory.path());
                        } catch (IOException exc) {
                            errors.add("Cartella creata non rimossa " + directory.path() + ": " + exc.getMessage());
                        }
                    });
            journal.directories().stream()
                    .filter(MoveJournal.DirectoryRecord::existedBefore)
                    .sorted(Comparator.comparingInt((MoveJournal.DirectoryRecord d) -> d.path().getNameCount()).reversed())
                    .forEach(directory -> {
                        try {
                            Path root = directory.side() == MoveJournal.DirectorySide.SOURCE
                                    ? journal.sourceRoot() : journal.destinationRoot();
                            if (!directory.path().equals(root)) MovePathSafety.requireContained(root, directory.path(), false);
                            if (!Files.isDirectory(directory.path(), LinkOption.NOFOLLOW_LINKS)) {
                                throw new IOException("cartella attesa assente");
                            }
                            Files.setLastModifiedTime(directory.path(),
                                    FileTime.from(Instant.parse(directory.mtime())));
                        } catch (IOException exc) {
                            errors.add("Data cartella non ripristinata " + directory.path() + ": " + exc.getMessage());
                        }
                    });
            return errors;
        }

        private void validateJournalPaths(MoveJournal journal) throws IOException {
            if (!Files.isDirectory(journal.sourceRoot(), LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(journal.destinationRoot(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Root sorgente o destinazione del journal assente");
            }
            if (Files.isSymbolicLink(journal.sourceRoot()) || Files.isSymbolicLink(journal.destinationRoot())) {
                throw new IOException("Root simboliche non ammesse");
            }
            for (MoveJournal.FileRecord file : journal.files()) {
                MovePathSafety.requireContained(journal.sourceRoot(), file.originalPath(), true);
                MovePathSafety.requireContained(journal.destinationRoot(), file.targetPath(), true);
                validateTimestamp(file.mtime(), "file " + file.originalPath());
                if (!file.targetMtime().isBlank()) {
                    validateTimestamp(file.targetMtime(), "target " + file.targetPath());
                }
                if (file.size() < 0 || !file.sha256().matches("[0-9a-fA-F]{64}")) {
                    throw new IOException("Metadati di integrità non validi per " + file.originalPath());
                }
            }
            for (MoveJournal.DirectoryRecord directory : journal.directories()) {
                Path root = directory.side() == MoveJournal.DirectorySide.SOURCE
                        ? journal.sourceRoot() : journal.destinationRoot();
                if (!directory.path().equals(root)) MovePathSafety.requireContained(root, directory.path(), true);
                validateTimestamp(directory.mtime(), "cartella " + directory.path());
            }
        }

        private void validateTimestamp(String value, String subject) throws IOException {
            try {
                Instant.parse(value);
            } catch (java.time.format.DateTimeParseException exc) {
                throw new IOException("Timestamp non valido nel journal per " + subject, exc);
            }
        }

        private void writeReport(List<Outcome> outcomes) throws IOException {
            try (BufferedWriter writer = Files.newBufferedWriter(outputs.report(), StandardCharsets.UTF_8)) {
                writer.write("\uFEFFPath originale;Path target;Esito;Dettaglio\n");
                for (Outcome outcome : outcomes) {
                    writer.write(CsvFormat.escape(outcome.original().toString(), ';')); writer.write(';');
                    writer.write(CsvFormat.escape(outcome.target().toString(), ';')); writer.write(';');
                    writer.write(CsvFormat.escape(outcome.result(), ';')); writer.write(';');
                    writer.write(CsvFormat.escape(outcome.detail(), ';')); writer.newLine();
                }
            }
        }

        private void writeAudit(OperationStatus status, Instant started, int restored, int errors,
                                List<String> details) throws IOException {
            Instant ended = clock.instant();
            List<String> lines = new ArrayList<>();
            lines.add("ScanDeleteFiles — Audit ripristino Move");
            lines.add("");
            lines.add("Stato: " + CsvFormat.statoItaliano(status));
            lines.add("Inizio: " + LocalDateTime.ofInstant(started, clock.getZone()).format(TIME));
            lines.add("Fine: " + LocalDateTime.ofInstant(ended, clock.getZone()).format(TIME));
            lines.add("File ripristinati: " + restored);
            lines.add("Errori: " + errors);
            details.forEach(detail -> lines.add("  Errore: " + detail));
            Files.write(outputs.audit(), lines, StandardCharsets.UTF_8);
        }

        @Override public void requestCancellation() { cancelled.set(true); }
        @Override public boolean isCancellationRequested() { return cancelled.get(); }
        private void notifyProgress(OperationProgress progress) { try { observer.onProgress(progress); } catch (Throwable ignored) { } }
        private void notifyFinished(OperationResult result) { try { observer.onFinished(result); } catch (Throwable ignored) { } }
    }

    private static boolean isEmpty(Path directory) throws IOException {
        try (var entries = Files.list(directory)) { return entries.findAny().isEmpty(); }
    }

    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) >= 0;) if (read > 0) digest.update(buffer, 0, read);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exc) {
            throw new IllegalStateException("SHA-256 non disponibile", exc);
        }
    }

    private static OutputPaths reserveOutputs(Path journal) {
        Path absolute = journal.toAbsolutePath().normalize();
        Path parent = absolute.getParent() == null ? Path.of(".").toAbsolutePath() : absolute.getParent();
        String name = absolute.getFileName().toString();
        String marker = ".undo.csv";
        String base = name.endsWith(marker) ? name.substring(0, name.length() - marker.length()) : name;
        String suffix = base.equals("moving") ? "" : base.startsWith("moving_") ? base.substring(7) : base;
        String stem = "undoing" + (suffix.isBlank() ? "" : "_" + suffix);
        synchronized (OUTPUT_LOCK) {
            for (int n = 1; ; n++) {
                String candidate = n == 1 ? stem : stem + "_" + n;
                Path report = parent.resolve(candidate + ".csv");
                Path audit = parent.resolve(candidate + ".log");
                if (!Files.exists(report) && !Files.exists(audit) && !RESERVED.contains(report) && !RESERVED.contains(audit)) {
                    RESERVED.add(report); RESERVED.add(audit);
                    return new OutputPaths(report, audit);
                }
            }
        }
    }

    private static void release(OutputPaths outputs) {
        if (outputs == null) return;
        synchronized (OUTPUT_LOCK) { RESERVED.remove(outputs.report()); RESERVED.remove(outputs.audit()); }
    }

    private static String message(OperationStatus status, int restored, int errors) {
        return switch (status) {
            case COMPLETED -> "Ripristino Move completato: " + restored + " file ripristinati";
            case CANCELLED -> "Ripristino Move annullato: " + restored + " file ripristinati";
            case FAILED -> "Ripristino Move fallito: " + restored + " file ripristinati, " + errors + " errori";
        };
    }
}
