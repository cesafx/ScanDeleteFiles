package it.scandeletefiles.application;

import it.scandeletefiles.platform.TrashAdapter;
import it.scandeletefiles.platform.TrashResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Esegue l'operazione di cancellazione a partire dal solo manifest CSV selezionato
 * dall'utente. {@code Path completo} della riga è il riferimento autorevole per il
 * file da trattare. Le righe marcate {@code Yes} (case-insensitive) vengono spostate
 * nel cestino tramite l'adattatore di piattaforma; {@code No} e il valore vuoto
 * saltano la riga; qualsiasi altro valore è un errore. Nessun sidecar e nessuno stato
 * di scansione è richiesto: il CSV è l'unico input.
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
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    private static final String BOM = "\uFEFF";

    private static final String ESITO_CESTINATO = "Cestinato";
    private static final String ESITO_ELIMINATO = "Eliminato definitivamente";
    private static final String ESITO_SALTATO = "Saltato";
    private static final String ESITO_ERRORE = "Errore";
    private static final String ESITO_NON_ELABORATO = "Non elaborato";

    private final TrashAdapter trash;
    private final Clock clock;

    public DeletionEngine(TrashAdapter trash, Clock clock) {
        this.trash = Objects.requireNonNull(trash, "trash");
        this.clock = Objects.requireNonNull(clock, "clock");
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
            try {
                return LocalDate.parse(fields[4].trim(), DATE_FORMAT);
            } catch (DateTimeParseException exc) {
                return null;
            }
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
        int daEliminare;
        int cestinati;
        int eliminatiPermanentemente;
        int saltate;
        int errori;
        final Map<String, Integer> perFormat = new TreeMap<>();
        final List<RowOutcome> outcomes = new ArrayList<>();
    }

    private final class DeletionTask implements CancellableOperation, Runnable {
        private final DeletionCommand command;
        private final OperationObserver observer;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private DeletionTask(DeletionCommand command, OperationObserver observer) {
            this.command = command;
            this.observer = observer;
        }

        @Override
        public void run() {
            Instant start = clock.instant();
            try {
                notifyProgress(OperationProgress.indeterminate(0, "Avvio cancellazione"));
                List<CsvRow> rows = readManifest(command.manifest());
                DeletionContext context = new DeletionContext();
                context.controllate = rows.size();
                for (CsvRow row : rows) {
                    if ("Yes".equalsIgnoreCase(row.deleteValue())) {
                        context.daEliminare++;
                        context.perFormat.merge(formatOf(row), 1, Integer::sum);
                    }
                }
                for (int i = 0; i < rows.size(); i++) {
                    if (cancelled.get()) {
                        for (int j = i; j < rows.size(); j++) {
                            context.outcomes.add(new RowOutcome(ESITO_NON_ELABORATO,
                                    "Operazione annullata prima dell'elaborazione"));
                        }
                        break;
                    }
                    context.outcomes.add(processRow(rows.get(i), context));
                    notifyProgress(OperationProgress.indeterminate(i + 1,
                            "Righe elaborate: " + (i + 1) + " / " + rows.size()));
                }
                OperationStatus status = cancelled.get()
                        ? OperationStatus.CANCELLED : OperationStatus.COMPLETED;
                writeReport(command.manifest(), rows, context.outcomes);
                writeAudit(command.manifest(), status, start, clock.instant(), context,
                        cancelled.get() ? "Annullata dall'utente prima del completamento" : null);
                notifyFinished(new OperationResult(status, messaggioFinale(status, context)));
            } catch (Exception exc) {
                failed(start, exc);
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
                        "Riga del manifest incoerente: Path completo, Nome file, Path cartella e Formato file non corrispondono; file conservato");
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
                            "Data ultima modifica non valida nel manifest: " + fullPath);
                }
                Instant modified = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant();
                LocalDate currentDate = LocalDateTime.ofInstant(modified, clock.getZone()).toLocalDate();
                if (!currentDate.equals(manifestDate)) {
                    context.saltate++;
                    return new RowOutcome(ESITO_SALTATO,
                            "Data ultima modifica non corrispondente al manifest: file conservato");
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
                throw new IOException("Il manifest CSV è vuoto");
            }
            String header = lines.get(0);
            if (!header.startsWith(BOM)) {
                throw new IOException(
                        "Il manifest CSV deve iniziare con il BOM UTF-8: contratto non conforme");
            }
            header = header.substring(1);
            if (!header.equals(String.join(String.valueOf(CSV_SEPARATOR), COLUMNS))) {
                throw new IOException("Intestazione del manifest CSV non conforme al contratto");
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
            Path report = reportPath(manifest);
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
                        writer.write(escapeCsv(j < fields.length ? fields[j] : ""));
                    }
                    writer.write(CSV_SEPARATOR);
                    writer.write(escapeCsv(outcome.esito()));
                    writer.write(CSV_SEPARATOR);
                    writer.write(escapeCsv(outcome.dettaglio()));
                    writer.write('\n');
                }
            }
            moveAtomically(temp, report);
        }

        private void writeAudit(Path manifest, OperationStatus status, Instant start, Instant end,
                                DeletionContext context, String note) throws IOException {
            ZoneId zone = clock.getZone();
            Path audit = manifest.resolveSibling(manifest.getFileName().toString() + ".log");
            List<String> lines = new ArrayList<>();
            lines.add("ScanDeleteFiles — Audit di cancellazione");
            lines.add("");
            lines.add("Stato: " + statoItaliano(status));
            lines.add("Inizio: " + LocalDateTime.ofInstant(start, zone).format(TIMESTAMP_FORMAT));
            lines.add("Fine: " + LocalDateTime.ofInstant(end, zone).format(TIMESTAMP_FORMAT));
            lines.add("Durata: " + String.format(Locale.ITALIAN, "%.3f s",
                    (end.toEpochMilli() - start.toEpochMilli()) / 1000.0));
            lines.add("Righe controllate: " + context.controllate);
            lines.add("Righe Da eliminare: " + context.daEliminare);
            lines.add("File cestinati: " + context.cestinati);
            lines.add("File eliminati permanentemente: " + context.eliminatiPermanentemente);
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

        private static String statoItaliano(OperationStatus status) {
            return switch (status) {
                case COMPLETED -> "COMPLETATA";
                case CANCELLED -> "ANNULLATA";
                case FAILED -> "FALLITA";
            };
        }

        private static String messaggioFinale(OperationStatus status, DeletionContext context) {
            return switch (status) {
                case COMPLETED -> "Cancellazione completata: " + context.cestinati + " cestinati, "
                        + context.eliminatiPermanentemente + " eliminati definitivamente, "
                        + context.saltate + " saltati, " + context.errori + " errori";
                case CANCELLED -> "Cancellazione annullata";
                case FAILED -> "Cancellazione fallita";
            };
        }

        private static Path reportPath(Path manifest) {
            String name = manifest.getFileName().toString();
            Path parent = manifest.getParent() == null ? Path.of(".") : manifest.getParent();
            if (name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
                return parent.resolve(name.substring(0, name.length() - 4) + ".report.csv");
            }
            return parent.resolve(name + ".report.csv");
        }

        private static String escapeCsv(String value) {
            if (value == null) {
                return "";
            }
            if (value.indexOf(CSV_SEPARATOR) >= 0 || value.indexOf('"') >= 0
                    || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                return '"' + value.replace("\"", "\"\"") + '"';
            }
            return value;
        }

        private static void moveAtomically(Path temp, Path target) throws IOException {
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exc) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private void failed(Instant start, Exception exc) {
            try {
                DeletionContext empty = new DeletionContext();
                empty.controllate = 0;
                writeAudit(command.manifest(), OperationStatus.FAILED, start, clock.instant(), empty,
                        exc.getMessage());
            } catch (IOException ioExc) {
                // il log di audit non \u00e8 scrivibile: l'esito \u00e8 comunque segnato come fallito
            }
            notifyFinished(new OperationResult(OperationStatus.FAILED,
                    "Cancellazione fallita: " + exc.getMessage()));
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
