package it.scandeletefiles.application;

import it.scandeletefiles.platform.TrashAdapter;
import it.scandeletefiles.platform.TrashResult;
import it.scandeletefiles.application.csv.CsvFormat;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Esegue l'operazione di cancellazione a partire dal solo manifest CSV selezionato
 * dall'utente. {@code Path completo} della riga è il riferimento autorevole per il
 * file da trattare. In modalità Move la root è fornita esplicitamente dal comando
 * (precompilata dai metadati di scansione ma modificabile dall'utente) e serve solo
 * a ricostruire il percorso relativo nella destinazione. Le righe marcate
 * {@code Yes} (case-insensitive) vengono elaborate; {@code No} e il valore vuoto
 * saltano la riga; qualsiasi altro valore è un errore.
 *
 * <p>L'operazione gira su un thread in background, segnala avanzamento e rispetta
 * l'annullamento cooperativo: le righe già elaborate conservano il loro esito, le
 * rimanenti sono riportate come non elaborate e report e audit vengono comunque
 * prodotti.
 */
public final class DeletionEngine implements DeletionApplicationService {

    private static final char CSV_SEPARATOR = ';';
    private static final String[] COLUMNS = {
            "Nome file",
            "Path cartella",
            "Path completo",
            "Formato file",
            "Data ultima modifica",
            "Delete"
    };
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("uuuu/MM/dd").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT));
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    private static final String BOM = "\uFEFF";

    private static final String ESITO_CESTINATO = "Cestinato";
    private static final String ESITO_ELIMINATO = "Eliminato definitivamente";
    private static final String ESITO_SPOSTATO = "Spostato";
    private static final String ESITO_SALTATO = "Saltato";
    private static final String ESITO_ERRORE = "Errore";
    private static final String ESITO_NON_ELABORATO = "Non elaborato";
    private static final Object OUTPUT_RESERVATION_LOCK = new Object();
    private static final Set<Path> RESERVED_OUTPUTS = new HashSet<>();

    private final TrashAdapter trash;
    private final Clock clock;
    private final DirectoryTimeRestorer directoryTimeRestorer;

    @FunctionalInterface
    interface DirectoryTimeRestorer {
        void restore(Path directory, FileTime timestamp) throws IOException;
    }

    public DeletionEngine(TrashAdapter trash, Clock clock) {
        this(trash, clock, Files::setLastModifiedTime);
    }

    DeletionEngine(TrashAdapter trash, Clock clock, DirectoryTimeRestorer directoryTimeRestorer) {
        this.trash = Objects.requireNonNull(trash, "trash");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.directoryTimeRestorer = Objects.requireNonNull(directoryTimeRestorer, "directoryTimeRestorer");
    }

    @Override
    public CancellableOperation start(DeletionCommand command, OperationObserver observer) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(observer, "observer");
        DeletionTask task = new DeletionTask(command, observer);
        Thread thread = new Thread(task, "scan-delete-files-deletion");
        thread.setDaemon(true);
        thread.start();
        return task;
    }

    private record CsvRow(String[] fields) {
        String fullPath() {
            return fields.length > 2 ? fields[2] : "";
        }

        String deleteValue() {
            return fields.length > 5 ? fields[5].trim() : "";
        }

        LocalDate manifestDate() {
            if (fields.length <= 4) {
                return null;
            }
            String value = fields[4].trim();
            for (DateTimeFormatter formatter : DATE_FORMATS) {
                try {
                    return LocalDate.parse(value, formatter);
                } catch (DateTimeParseException ignored) {
                    // Excel italiano può riscrivere yyyy/MM/dd come dd/MM/yyyy.
                }
            }
            return null;
        }

        boolean malformed() {
            return fields.length != COLUMNS.length;
        }

        boolean coerente() {
            if (malformed()) {
                return false;
            }
            String nome = fields[0].trim();
            String cartella = fields[1].trim();
            String completo = fields[2].trim();
            String formato = fields[3].trim();
            if (nome.isEmpty() || completo.isBlank()) {
                return false;
            }
            Path file = Path.of(completo);
            if (!nome.equals(file.getFileName().toString())) {
                return false;
            }
            Path atteso = Path.of(cartella).resolve(nome).normalize();
            if (!atteso.toString().equals(completo)) {
                return false;
            }
            if (!formato.isEmpty()) {
                String estensione = estensioneDi(nome);
                if (!estensione.equalsIgnoreCase(formato)) {
                    return false;
                }
            }
            return true;
        }

        private static String estensioneDi(String nome) {
            int punto = nome.lastIndexOf('.');
            return punto < 0 || punto == nome.length() - 1 ? "" : nome.substring(punto + 1);
        }
    }

    private record RowOutcome(String esito, String dettaglio) {
    }

    private static final class DeletionContext {
        int controllate;
        int elaborate;
        int daEliminare;
        int cestinati;
        int eliminatiPermanentemente;
        int spostati;
        int saltate;
        int errori;
        final Map<String, Integer> perFormat = new TreeMap<>();
        final Map<Path, FileTime> sourceDirectoryTimes = new LinkedHashMap<>();
        final Map<Path, FileTime> destinationDirectoryTimes = new LinkedHashMap<>();
        final List<String> fileMetadataErrors = new ArrayList<>();
        final List<CsvRow> rows = new ArrayList<>();
        final List<RowOutcome> outcomes = new ArrayList<>();
    }

    private final class DeletionTask implements CancellableOperation, Runnable {
        private final DeletionCommand command;
        private final OperationObserver observer;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private OutputPaths outputPaths;

        private DeletionTask(DeletionCommand command, OperationObserver observer) {
            this.command = command;
            this.observer = observer;
        }

        @Override
        public void run() {
            Instant start = clock.instant();
            DeletionContext context = new DeletionContext();
            try {
                outputPaths = resolveOutputPaths(command.manifest(), command.mode());
                notifyProgress(OperationProgress.indeterminate(0, "Avvio operazione Move/Delete"));
                context.rows.addAll(readManifest(command.manifest()));
                context.controllate = context.rows.size();
                for (CsvRow row : context.rows) {
                    if ("Yes".equalsIgnoreCase(row.deleteValue())) {
                        context.daEliminare++;
                        context.perFormat.merge(formatOf(row), 1, Integer::sum);
                    }
                }
                notifyProgress(progress(context));
                for (int i = 0; i < context.rows.size(); i++) {
                    if (cancelled.get()) {
                        for (int j = i; j < context.rows.size(); j++) {
                            context.outcomes.add(new RowOutcome(ESITO_NON_ELABORATO,
                                    "Operazione annullata prima dell'elaborazione"));
                        }
                        break;
                    }
                    context.outcomes.add(processRow(context.rows.get(i), context));
                    context.elaborate++;
                    notifyProgress(progress(context));
                }
                boolean cancellationRequested = cancelled.get();
                OperationStatus status = cancellationRequested
                        ? OperationStatus.CANCELLED : OperationStatus.COMPLETED;
                String note = cancellationRequested
                        ? "Annullata dall'utente prima del completamento" : null;
                writeReport(command.manifest(), context.rows, context.outcomes);
                writeAudit(command.manifest(), status, start, clock.instant(), context, note);
                List<String> metadataErrors = new ArrayList<>(context.fileMetadataErrors);
                metadataErrors.addAll(restoreSourceDirectoryTimes(context));
                if (!metadataErrors.isEmpty()) {
                    context.errori += metadataErrors.size();
                    status = OperationStatus.FAILED;
                    note = appendNote(note, String.join("; ", metadataErrors));
                    writeAudit(command.manifest(), status, start, clock.instant(), context, note);
                    restoreSourceDirectoryTimes(context);
                }
                notifyFinished(new OperationResult(status, messaggioFinale(status, context)));
            } catch (Exception exc) {
                failed(start, context, exc);
            } finally {
                releaseOutputPaths(outputPaths);
            }
        }

        private RowOutcome processRow(CsvRow row, DeletionContext context) {
            String deleteValue = row.deleteValue();
            if (deleteValue.isEmpty()) {
                context.saltate++;
                return new RowOutcome(ESITO_SALTATO, "Delete non valorizzato: file conservato");
            }
            if (deleteValue.equalsIgnoreCase("No")) {
                context.saltate++;
                return new RowOutcome(ESITO_SALTATO, "Delete = No: file conservato");
            }
            if (!deleteValue.equalsIgnoreCase("Yes")) {
                context.errori++;
                return new RowOutcome(ESITO_ERRORE, "Valore Delete non ammesso: '" + deleteValue + "'");
            }

            if (!row.coerente()) {
                context.errori++;
                return new RowOutcome(ESITO_ERRORE,
                        "Riga del CSV incoerente: Path completo, Nome file, Path cartella e Formato file non corrispondono; file conservato");
            }

            String fullPath = row.fullPath();
            if (fullPath.isBlank()) {
                context.errori++;
                return new RowOutcome(ESITO_ERRORE, "Path completo mancante nella riga");
            }
            Path file = Path.of(fullPath);
            try {
                if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    context.errori++;
                    return new RowOutcome(ESITO_ERRORE, "File non trovato: " + fullPath);
                }
                LocalDate manifestDate = row.manifestDate();
                if (manifestDate == null) {
                    context.errori++;
                    return new RowOutcome(ESITO_ERRORE,
                            "Data ultima modifica non valida nel CSV: " + fullPath);
                }
                Instant modified = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant();
                LocalDate currentDate = LocalDateTime.ofInstant(modified, clock.getZone()).toLocalDate();
                if (!currentDate.equals(manifestDate)) {
                    context.saltate++;
                    return new RowOutcome(ESITO_SALTATO,
                            "Data ultima modifica non corrispondente al CSV: file conservato");
                }
            } catch (IOException exc) {
                context.errori++;
                return new RowOutcome(ESITO_ERRORE, "Impossibile verificare il file: " + exc.getMessage());
            }

            switch (command.mode()) {
                case TRASH -> {
                    TrashResult result = trash.moveToTrash(file);
                    if (result.status() == TrashResult.Status.MOVED) {
                        context.cestinati++;
                        String detail = result.detail().isBlank()
                                ? "Spostato nel cestino della piattaforma"
                                : result.detail();
                        return new RowOutcome(ESITO_CESTINATO, detail);
                    }
                    if (result.status() == TrashResult.Status.UNAVAILABLE) {
                        context.saltate++;
                        String detail = result.detail().isBlank()
                                ? "Cestino non disponibile"
                                : result.detail();
                        return new RowOutcome(ESITO_SALTATO, detail + "; file conservato");
                    }
                    context.errori++;
                    return new RowOutcome(ESITO_ERRORE,
                            "Impossibile spostare nel cestino: " + result.detail());
                }
                case PERMANENT -> {
                    if (!command.permanentDeletionConfirmed()) {
                        context.saltate++;
                        return new RowOutcome(ESITO_SALTATO,
                                "Cancellazione permanente non confermata: file conservato");
                    }
                    try {
                        Files.delete(file);
                        context.eliminatiPermanentemente++;
                        return new RowOutcome(ESITO_ELIMINATO, "Cancellazione permanente eseguita");
                    } catch (IOException exc) {
                        context.errori++;
                        return new RowOutcome(ESITO_ERRORE,
                                "Impossibile eliminare definitivamente: " + exc.getMessage());
                    }
                }
                case MOVE_TO_FOLDER -> {
                    Path sourceRoot = command.sourceRoot().toAbsolutePath().normalize();
                    Path sourceFile = file.toAbsolutePath().normalize();
                    Path targetRoot = command.destination().toAbsolutePath().normalize();
                    try {
                        Path sourceRootReal = sourceRoot.toRealPath();
                        Path sourceReal = sourceFile.toRealPath();
                        if (!sourceFile.startsWith(sourceRoot) || sourceFile.equals(sourceRoot)
                                || !sourceReal.startsWith(sourceRootReal)
                                || hasSymbolicLinkComponent(sourceRoot, sourceFile)) {
                            context.errori++;
                            return new RowOutcome(ESITO_ERRORE,
                                    "File esterno alla cartella root selezionata o raggiunto tramite link: "
                                            + sourceFile);
                        }
                        Path relative = sourceRoot.relativize(sourceFile);
                        Path target = targetRoot.resolve(relative).normalize();
                        if (Files.isSymbolicLink(targetRoot)) {
                            context.errori++;
                            return new RowOutcome(ESITO_ERRORE,
                                    "La cartella di destinazione non può essere un link simbolico: "
                                            + targetRoot);
                        }
                        Path targetRootReal = targetRoot.toRealPath();
                        if (!target.startsWith(targetRoot)) {
                            context.errori++;
                            return new RowOutcome(ESITO_ERRORE,
                                    "Percorso di destinazione non valido: " + target);
                        }
                        verifyExistingDestinationPath(targetRoot, target.getParent(), targetRootReal);
                        FileTime fileTimestamp = Files.getLastModifiedTime(sourceFile, LinkOption.NOFOLLOW_LINKS);
                        rememberDirectoryTree(context, sourceRoot, sourceFile.getParent(), targetRoot);
                        Files.createDirectories(target.getParent());
                        Path uniqueTarget = uniqueDestination(target);
                        Files.move(sourceFile, uniqueTarget);
                        context.spostati++;
                        try {
                            Files.setLastModifiedTime(uniqueTarget, fileTimestamp);
                        } catch (IOException timestampError) {
                            String detail = "File spostato in " + uniqueTarget
                                    + ", ma impossibile ripristinare la data di ultima modifica: "
                                    + timestampError.getMessage();
                            context.fileMetadataErrors.add(detail);
                            return new RowOutcome(ESITO_ERRORE, detail);
                        }
                        return new RowOutcome(ESITO_SPOSTATO,
                                "Spostato in: " + uniqueTarget);
                    } catch (IOException exc) {
                        context.errori++;
                        return new RowOutcome(ESITO_ERRORE,
                                "Impossibile spostare il file: " + exc.getMessage());
                    }
                }
                default -> {
                    context.errori++;
                    return new RowOutcome(ESITO_ERRORE, "Modalità di cancellazione non riconosciuta");
                }
            }
        }

        private static String formatOf(CsvRow row) {
            return row.fields().length > 3 && !row.fields()[3].isBlank()
                    ? row.fields()[3]
                    : "(nessun formato)";
        }

        private List<CsvRow> readManifest(Path manifest) throws IOException {
            List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                throw new IOException("Il file CSV è vuoto");
            }
            String header = lines.get(0);
            if (!header.startsWith(BOM)) {
                throw new IOException(
                        "Il file CSV deve iniziare con il BOM UTF-8: contratto non conforme");
            }
            header = header.substring(1);
            if (!header.equals(String.join(String.valueOf(CSV_SEPARATOR), COLUMNS))) {
                throw new IOException("Intestazione del file CSV non conforme al contratto");
            }
            List<CsvRow> rows = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank()) {
                    continue;
                }
                rows.add(new CsvRow(parseCsvLine(line)));
            }
            return rows;
        }

        private static String[] parseCsvLine(String line) {
            List<String> fields = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean inQuotes = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (inQuotes) {
                    if (c == '"') {
                        if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                            current.append('"');
                            i++;
                        } else {
                            inQuotes = false;
                        }
                    } else {
                        current.append(c);
                    }
                } else if (c == CSV_SEPARATOR) {
                    fields.add(current.toString());
                    current.setLength(0);
                } else if (c == '"') {
                    inQuotes = true;
                } else {
                    current.append(c);
                }
            }
            fields.add(current.toString());
            return fields.toArray(String[]::new);
        }

        private void writeReport(Path manifest, List<CsvRow> rows, List<RowOutcome> outcomes)
                throws IOException {
            Path report = outputPaths.report();
            Path parent = report.getParent() == null ? Path.of(".") : report.getParent();
            Path temp = parent.resolve(report.getFileName().toString() + ".tmp." + System.nanoTime());
            try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                writer.write('\uFEFF');
                for (int i = 0; i < COLUMNS.length; i++) {
                    if (i > 0) {
                        writer.write(CSV_SEPARATOR);
                    }
                    writer.write(COLUMNS[i]);
                }
                writer.write(CSV_SEPARATOR);
                writer.write("Esito");
                writer.write(CSV_SEPARATOR);
                writer.write("Dettaglio");
                writer.write('\n');
                for (int i = 0; i < rows.size(); i++) {
                    String[] fields = rows.get(i).fields();
                    RowOutcome outcome = outcomes.get(i);
                    for (int j = 0; j < COLUMNS.length; j++) {
                        if (j > 0) {
                            writer.write(CSV_SEPARATOR);
                        }
                        writer.write(CsvFormat.escape(j < fields.length ? fields[j] : "", CSV_SEPARATOR));
                    }
                    writer.write(CSV_SEPARATOR);
                    writer.write(CsvFormat.escape(outcome.esito(), CSV_SEPARATOR));
                    writer.write(CSV_SEPARATOR);
                    writer.write(CsvFormat.escape(outcome.dettaglio(), CSV_SEPARATOR));
                    writer.write('\n');
                }
            }
            moveAtomically(temp, report);
        }

        private void writeAudit(Path manifest, OperationStatus status, Instant start, Instant end,
                                DeletionContext context, String note) throws IOException {
            ZoneId zone = clock.getZone();
            Path audit = outputPaths.audit();
            List<String> lines = new ArrayList<>();
            lines.add("ScanDeleteFiles — Audit Move/Delete");
            lines.add("");
            lines.add("Stato: " + CsvFormat.statoItaliano(status));
            lines.add("Inizio: " + LocalDateTime.ofInstant(start, zone).format(TIMESTAMP_FORMAT));
            lines.add("Fine: " + LocalDateTime.ofInstant(end, zone).format(TIMESTAMP_FORMAT));
            lines.add("Durata: " + String.format(Locale.ITALIAN, "%.3f s",
                    (end.toEpochMilli() - start.toEpochMilli()) / 1000.0));
            lines.add("Righe controllate: " + context.controllate);
            lines.add("Righe elaborate: " + context.elaborate);
            lines.add("Righe Da eliminare: " + context.daEliminare);
            lines.add("File cestinati: " + context.cestinati);
            lines.add("File eliminati permanentemente: " + context.eliminatiPermanentemente);
            lines.add("File spostati in cartella: " + context.spostati);
            lines.add("Righe saltate: " + context.saltate);
            lines.add("Errori: " + context.errori);
            lines.add("Percentuali per formato:");
            if (context.daEliminare == 0) {
                lines.add("  (nessuna riga Da eliminare)");
            } else {
                double total = context.daEliminare;
                for (Map.Entry<String, Integer> entry : context.perFormat.entrySet()) {
                    lines.add("  " + entry.getKey() + ": "
                            + String.format(Locale.ITALIAN, "%.1f%%", 100.0 * entry.getValue() / total));
                }
            }
            for (RowOutcome outcome : context.outcomes) {
                if (ESITO_ERRORE.equals(outcome.esito())) {
                    lines.add("  Errore: " + outcome.dettaglio());
                }
            }
            if (note != null) {
                lines.add("");
                lines.add("Note: " + note);
            }
            Files.write(audit, lines, StandardCharsets.UTF_8);
        }

        private static String messaggioFinale(OperationStatus status, DeletionContext context) {
            return switch (status) {
                case COMPLETED -> "Operazione completata: " + context.cestinati + " cestinati, "
                        + context.eliminatiPermanentemente + " eliminati definitivamente, "
                        + context.spostati + " spostati, "
                        + context.saltate + " saltati, " + context.errori + " errori";
                case CANCELLED -> "Operazione annullata: " + context.elaborate + " / "
                        + context.controllate + " righe elaborate, " + context.cestinati + " cestinati, "
                        + context.eliminatiPermanentemente + " eliminati definitivamente, "
                        + context.spostati + " spostati, "
                        + context.saltate + " saltati, " + context.errori + " errori";
                case FAILED -> "Operazione fallita";
            };
        }

        private static void validateMoveRoots(Path sourceRoot, Path destinationRoot) throws IOException {
            Path source = sourceRoot.toAbsolutePath().normalize();
            Path destination = destinationRoot.toAbsolutePath().normalize();
            if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Root sorgente e destinazione devono essere cartelle esistenti");
            }
            BasicFileAttributes sourceAttributes = Files.readAttributes(
                    source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes destinationAttributes = Files.readAttributes(
                    destination, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (Files.isSymbolicLink(source) || Files.isSymbolicLink(destination)
                    || sourceAttributes.isOther() || destinationAttributes.isOther()) {
                throw new IOException("Root sorgente e destinazione non possono essere link o reparse point");
            }
            Path sourceReal = source.toRealPath();
            Path destinationReal = destination.toRealPath();
            if (sourceReal.equals(destinationReal)) {
                throw new IOException("Root sorgente e destinazione non possono coincidere");
            }
        }

        private static boolean hasSymbolicLinkComponent(Path root, Path file) throws IOException {
            Path current = root;
            if (isLinkOrReparsePoint(current)) {
                return true;
            }
            for (Path segment : root.relativize(file)) {
                current = current.resolve(segment);
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                        && isLinkOrReparsePoint(current)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isLinkOrReparsePoint(Path path) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return Files.isSymbolicLink(path) || attributes.isOther();
        }

        private static void verifyExistingDestinationPath(Path targetRoot, Path targetParent,
                                                          Path targetRootReal) throws IOException {
            Path current = targetRoot;
            Path relative = targetRoot.relativize(targetParent);
            for (Path segment : relative) {
                current = current.resolve(segment);
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    if (isLinkOrReparsePoint(current)) {
                        throw new IOException("Una cartella di destinazione è un link o reparse point: " + current);
                    }
                    Path currentReal = current.toRealPath();
                    if (!currentReal.startsWith(targetRootReal)) {
                        throw new IOException("Una cartella di destinazione attraversa un link esterno: "
                                + current);
                    }
                }
            }
        }

        private void rememberDirectoryTree(DeletionContext context, Path sourceRoot,
                                           Path sourceDirectory, Path targetRoot) throws IOException {
            Path current = sourceDirectory;
            while (current != null && current.startsWith(sourceRoot)) {
                FileTime timestamp = context.sourceDirectoryTimes.get(current);
                if (timestamp == null) {
                    timestamp = Files.getLastModifiedTime(current, LinkOption.NOFOLLOW_LINKS);
                    context.sourceDirectoryTimes.put(current, timestamp);
                }
                Path relativeDirectory = sourceRoot.relativize(current);
                Path destinationDirectory = relativeDirectory.toString().isEmpty()
                        ? targetRoot : targetRoot.resolve(relativeDirectory).normalize();
                boolean destinationExisted = Files.exists(destinationDirectory, LinkOption.NOFOLLOW_LINKS);
                FileTime finalTimestamp = destinationExisted
                        ? Files.getLastModifiedTime(destinationDirectory, LinkOption.NOFOLLOW_LINKS)
                        : timestamp;
                if (!context.destinationDirectoryTimes.containsKey(destinationDirectory)) {
                    context.destinationDirectoryTimes.put(destinationDirectory, finalTimestamp);
                }
                if (current.equals(sourceRoot)) {
                    break;
                }
                current = current.getParent();
            }
        }

        private List<String> restoreSourceDirectoryTimes(DeletionContext context) {
            List<String> errors = new ArrayList<>();
            restoreDirectoryTimes(context.destinationDirectoryTimes, "di destinazione", errors);
            restoreDirectoryTimes(context.sourceDirectoryTimes, "sorgente", errors);
            return errors;
        }

        private void restoreDirectoryTimes(Map<Path, FileTime> timestamps, String kind,
                                           List<String> errors) {
            timestamps.entrySet().stream()
                    .sorted(Map.Entry.<Path, FileTime>comparingByKey(
                            Comparator.comparingInt(Path::getNameCount).reversed()))
                    .forEach(entry -> {
                        try {
                            directoryTimeRestorer.restore(entry.getKey(), entry.getValue());
                        } catch (IOException exc) {
                            errors.add("Impossibile ripristinare la data della cartella " + kind + " "
                                    + entry.getKey() + ": " + exc.getMessage());
                        }
                    });
        }

        private static String appendNote(String note, String addition) {
            return note == null || note.isBlank() ? addition : note + "; " + addition;
        }

        private static OperationProgress progress(DeletionContext context) {
            return OperationProgress.determinate(context.elaborate, context.controllate,
                    "Righe elaborate: " + context.elaborate + " / " + context.controllate
                            + "; cestinati: " + context.cestinati
                            + "; eliminati definitivamente: " + context.eliminatiPermanentemente
                            + "; spostati: " + context.spostati
                            + "; saltati: " + context.saltate
                            + "; errori: " + context.errori);
        }

        private static OutputPaths resolveOutputPaths(Path manifest, DeletionMode mode) {
            String name = manifest.getFileName().toString();
            Path parent = manifest.getParent() == null ? Path.of(".") : manifest.getParent();
            String base = name.toLowerCase(Locale.ROOT).endsWith(".csv")
                    ? name.substring(0, name.length() - 4)
                    : name;
            String suffix;
            if (base.equalsIgnoreCase("scansione")) {
                suffix = "";
            } else if (base.regionMatches(true, 0, "scansione_", 0, "scansione_".length())) {
                suffix = base.substring("scansione_".length());
            } else {
                suffix = base;
            }
            String stem = actionPrefix(mode) + (suffix.isBlank() ? "" : "_" + suffix);
            synchronized (OUTPUT_RESERVATION_LOCK) {
                for (int sequence = 1; ; sequence++) {
                    String candidateStem = sequence == 1 ? stem : stem + "_" + sequence;
                    Path report = parent.resolve(candidateStem + ".csv").toAbsolutePath().normalize();
                    Path audit = parent.resolve(candidateStem + ".log").toAbsolutePath().normalize();
                    if (!Files.exists(report) && !Files.exists(audit)
                            && !RESERVED_OUTPUTS.contains(report) && !RESERVED_OUTPUTS.contains(audit)) {
                        RESERVED_OUTPUTS.add(report);
                        RESERVED_OUTPUTS.add(audit);
                        return new OutputPaths(report, audit);
                    }
                }
            }
        }

        private static void releaseOutputPaths(OutputPaths paths) {
            if (paths == null) {
                return;
            }
            synchronized (OUTPUT_RESERVATION_LOCK) {
                RESERVED_OUTPUTS.remove(paths.report());
                RESERVED_OUTPUTS.remove(paths.audit());
            }
        }

        private static String actionPrefix(DeletionMode mode) {
            return switch (mode) {
                case TRASH -> "trash";
                case PERMANENT -> "deleting";
                case MOVE_TO_FOLDER -> "moving";
            };
        }

        private record OutputPaths(Path report, Path audit) {
        }

        private static void moveAtomically(Path temp, Path target) throws IOException {
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exc) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private static Path uniqueDestination(Path target) {
            if (!Files.exists(target)) {
                return target;
            }
            String name = target.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";
            for (int sequence = 2; ; sequence++) {
                Path candidate = target.resolveSibling(stem + "_" + sequence + ext);
                if (!Files.exists(candidate)) {
                    return candidate;
                }
            }
        }

        private void failed(Instant start, DeletionContext context, Exception exc) {
            context.errori++;
            completeFailureOutcomes(context, exc);
            String note = exc.getMessage();
            if (!context.rows.isEmpty()) {
                try {
                    writeReport(command.manifest(), context.rows, context.outcomes);
                } catch (Exception reportExc) {
                    note = note + "; impossibile generare il report: " + reportExc.getMessage();
                }
            }
            try {
                writeAudit(command.manifest(), OperationStatus.FAILED, start, clock.instant(), context,
                        note);
            } catch (IOException ioExc) {
                // il log di audit non è scrivibile: l'esito è comunque segnato come fallito
            }
            List<String> metadataErrors = restoreSourceDirectoryTimes(context);
            if (!metadataErrors.isEmpty()) {
                context.errori += metadataErrors.size();
                note = appendNote(note, String.join("; ", metadataErrors));
                try {
                    writeAudit(command.manifest(), OperationStatus.FAILED, start, clock.instant(), context,
                            note);
                } catch (IOException ignored) {
                    // Il fallimento resta comunque comunicato all'interfaccia.
                }
                restoreSourceDirectoryTimes(context);
            }
            notifyFinished(new OperationResult(OperationStatus.FAILED,
                    "Operazione fallita: " + exc.getMessage()));
        }

        private static void completeFailureOutcomes(DeletionContext context, Exception exc) {
            int nextRow = context.outcomes.size();
            if (nextRow < context.rows.size()) {
                context.outcomes.add(new RowOutcome(ESITO_ERRORE,
                        "Errore fatale durante l'elaborazione: " + exc.getMessage()));
                context.elaborate++;
                nextRow++;
            }
            while (nextRow < context.rows.size()) {
                context.outcomes.add(new RowOutcome(ESITO_NON_ELABORATO,
                        "Operazione interrotta da un errore fatale prima dell'elaborazione"));
                nextRow++;
            }
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
