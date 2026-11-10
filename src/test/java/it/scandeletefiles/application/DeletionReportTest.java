package it.scandeletefiles.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.scandeletefiles.bootstrap.PlatformAdapters;
import it.scandeletefiles.application.OperationObserver;
import it.scandeletefiles.application.OperationProgress;
import it.scandeletefiles.application.OperationResult;
import it.scandeletefiles.application.OperationStatus;
import it.scandeletefiles.platform.SettingsAdapter;
import it.scandeletefiles.platform.TrashAdapter;
import it.scandeletefiles.platform.TrashResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Il report di cancellazione conserva ordine e valori originali del manifest e spiega l'esito di
 * ogni riga con colonna Esito e Dettaglio; l'audit di cancellazione viene scritto accanto al CSV.
 */
class DeletionReportTest {

    private static final Instant FIXED = Instant.parse("2026-11-09T10:00:00Z");
    private static final Instant MTIME = Instant.parse("2026-11-09T23:00:00Z");

    private static final class ControlledTrash implements TrashAdapter {
        private final Path trashDir;
        private final List<Path> moved = new CopyOnWriteArrayList<>();

        ControlledTrash(Path trashDir) {
            this.trashDir = trashDir;
        }

        @Override
        public TrashResult moveToTrash(Path file) {
            Path target = trashDir.resolve(file.getFileName().toString());
            int suffix = 0;
            while (Files.exists(target)) {
                target = trashDir.resolve(file.getFileName().toString() + "." + suffix++);
            }
            try {
                Files.move(file, target);
                moved.add(target);
            } catch (Exception exc) {
                return new TrashResult(TrashResult.Status.FAILED, exc.getMessage());
            }
            return new TrashResult(TrashResult.Status.MOVED, "spostato nel cestino di test");
        }
    }

    private static DeletionEngine engine(Path root, ControlledTrash trash) throws Exception {
        PlatformAdapters platform = new PlatformAdapters(
                FileSystems.getDefault(),
                trash,
                new SettingsAdapter() {
                    @Override
                    public Set<String> loadSelectedExtensions() {
                        return Set.of();
                    }

                    @Override
                    public void saveSelectedExtensions(Set<String> extensions) {
                    }
                },
                Clock.fixed(FIXED, ZoneOffset.UTC));
        return new DeletionEngine(trash, platform.clock());
    }

    private static void writeFile(Path root, String name, Instant mtime) throws Exception {
        Files.writeString(root.resolve(name), "contenuto " + name);
        Files.setLastModifiedTime(root.resolve(name), FileTime.from(mtime));
    }

    private static String manifestCsv(String rootPath, Object... rows) {
        StringBuilder csv = new StringBuilder("\uFEFFNome file;Path cartella;Path completo;Formato file;Data ultima modifica;Delete\n");
        for (Object row : rows) {
            csv.append(row).append("\n");
        }
        return csv.toString();
    }

    private static String row(String name, String rootPath, Path file, String format, String date, String delete) {
        return name + ";" + rootPath + ";" + file + ";" + format + ";" + date + ";" + delete;
    }

    private static OperationResult esegui(DeletionEngine engine, Path manifest) throws Exception {
        return esegui(engine, new DeletionCommand(manifest, DeletionMode.TRASH, false));
    }

    private static OperationResult esegui(DeletionEngine engine, DeletionCommand command) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<OperationResult> result = new AtomicReference<>();
        engine.start(command, new OperationObserver() {
            @Override
            public void onProgress(OperationProgress p) {
            }

            @Override
            public void onFinished(OperationResult r) {
                result.set(r);
                finished.countDown();
            }
        });
        assertTrue(finished.await(30, TimeUnit.SECONDS), "l'operazione non è terminata");
        return result.get();
    }

    @Test
    void reportConservaOrdineValoriOriginaliESpiegaOgniEsito(@TempDir Path root) throws Exception {
        Path trashDir = root.resolve("cestino");
        Files.createDirectories(trashDir);
        Path a = root.resolve("a.txt");
        Path b = root.resolve("b.txt");
        Path c = root.resolve("c.txt");
        writeFile(root, "a.txt", MTIME);
        writeFile(root, "b.txt", MTIME);
        writeFile(root, "c.txt", MTIME);

        ControlledTrash trash = new ControlledTrash(trashDir);
        DeletionEngine engine = engine(root, trash);

        String rootPath = root.toString();
        Path manifest = root.resolve("operazione.csv");
        // Righe in ordine: Yes (cestinato), No (saltato), vuoto (saltato), Forse (errore).
        Files.writeString(manifest, manifestCsv(rootPath,
                row("a.txt", rootPath, a, "TXT", "2026/11/09", "Yes"),
                row("b.txt", rootPath, b, "TXT", "2026/11/09", "No"),
                row("c.txt", rootPath, c, "TXT", "2026/11/09", ""),
                row("c.txt", rootPath, c, "TXT", "2026/11/09", "Forse")),
                StandardCharsets.UTF_8);

        OperationResult result = esegui(engine, manifest);
        assertEquals(OperationStatus.COMPLETED, result.status());

        Path report = root.resolve("trash_operazione.csv");
        assertTrue(Files.exists(report), "il report deve essere prodotto accanto al manifest");
        String content = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(content.startsWith("\uFEFF"), "il report deve iniziare con il BOM UTF-8");

        List<String> lines = content.substring(1).lines().toList();
        assertEquals("Nome file;Path cartella;Path completo;Formato file;Data ultima modifica;Delete;Esito;Dettaglio",
                lines.get(0), "l'intestazione del report aggiunge Esito e Dettaglio alle sei originali");
        assertEquals(5, lines.size(), "una riga di report per ogni riga di input, stesso ordine");

        // I sei valori originali sono conservati in ordine; Esito e Dettaglio spiegano l'esito.
        String[] rigaA = lines.get(1).split(";", -1);
        assertEquals("a.txt", rigaA[0]);
        assertEquals("TXT", rigaA[3]);
        assertEquals("2026/11/09", rigaA[4]);
        assertEquals("Yes", rigaA[5]);
        assertEquals("Cestinato", rigaA[6]);

        String[] rigaB = lines.get(2).split(";", -1);
        assertEquals("b.txt", rigaB[0]);
        assertEquals("No", rigaB[5]);
        assertEquals("Saltato", rigaB[6]);

        String[] rigaC = lines.get(3).split(";", -1);
        assertEquals("c.txt", rigaC[0]);
        assertEquals("", rigaC[5]);
        assertEquals("Saltato", rigaC[6]);

        String[] rigaD = lines.get(4).split(";", -1);
        assertEquals("c.txt", rigaD[0]);
        assertEquals("Forse", rigaD[5]);
        assertEquals("Errore", rigaD[6]);
        assertTrue(rigaD[7].contains("non ammesso"), "il dettaglio deve spiegare l'errore");

        // Ogni riga ha esattamente 8 colonne (6 originali + Esito + Dettaglio).
        for (String line : lines.subList(1, lines.size())) {
            assertEquals(8, line.split(";", -1).length, "ogni riga di report ha 8 colonne");
        }

        // Il file Yes è stato cestinato, gli altri restano intatti.
        assertFalse(Files.exists(a));
        assertTrue(Files.exists(b));
        assertTrue(Files.exists(c));
        assertEquals(1, trash.moved.size());

        Path audit = root.resolve("trash_operazione.log");
        assertTrue(Files.exists(audit), "l'audit deve essere scritto accanto al manifest CSV");
        String log = Files.readString(audit, StandardCharsets.UTF_8);
        assertTrue(log.contains("Stato: COMPLETATA"));
        assertTrue(log.contains("Righe controllate: 4"));
        assertTrue(log.contains("Righe Da eliminare: 1"));
        assertTrue(log.contains("File cestinati: 1"));
        assertTrue(log.contains("Righe saltate: 2"));
        assertTrue(log.contains("Errori: 1"));
    }

    @Test
    void cestinoUsaNomiTrashPerReportELog(@TempDir Path root) throws Exception {
        Path trashDir = Files.createDirectories(root.resolve("cestino"));
        Path file = root.resolve("a.txt");
        writeFile(root, "a.txt", MTIME);
        ControlledTrash trash = new ControlledTrash(trashDir);
        Path manifest = root.resolve("scansione_20261110_100000.csv");
        Files.writeString(manifest, manifestCsv(root.toString(),
                row("a.txt", root.toString(), file, "TXT", "2026/11/09", "Yes")),
                StandardCharsets.UTF_8);

        OperationResult result = esegui(engine(root, trash), manifest);

        assertEquals(OperationStatus.COMPLETED, result.status());
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(root.resolve("trash_20261110_100000.csv")));
        assertTrue(Files.exists(root.resolve("trash_20261110_100000.log")));
        assertFalse(Files.exists(root.resolve("scansione_20261110_100000.report.csv")));
        assertFalse(Files.exists(root.resolve("scansione_20261110_100000.csv.log")));
    }

    @Test
    void cancellazionePermanenteUsaNomiDeletingPerReportELog(@TempDir Path root) throws Exception {
        Path trashDir = Files.createDirectories(root.resolve("cestino"));
        Path file = root.resolve("a.txt");
        writeFile(root, "a.txt", MTIME);
        ControlledTrash trash = new ControlledTrash(trashDir);
        Path manifest = root.resolve("scansione_20261110_100000.csv");
        Files.writeString(manifest, manifestCsv(root.toString(),
                row("a.txt", root.toString(), file, "TXT", "2026/11/09", "Yes")),
                StandardCharsets.UTF_8);

        OperationResult result = esegui(engine(root, trash),
                new DeletionCommand(manifest, DeletionMode.PERMANENT, true));

        assertEquals(OperationStatus.COMPLETED, result.status());
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(root.resolve("deleting_20261110_100000.csv")));
        assertTrue(Files.exists(root.resolve("deleting_20261110_100000.log")));
        assertFalse(Files.exists(root.resolve("scansione_20261110_100000.csv.log")));
    }

    @Test
    void spostamentoUsaNomiMovingPerReportELog(@TempDir Path root) throws Exception {
        Path trashDir = Files.createDirectories(root.resolve("cestino"));
        Path destination = Files.createDirectories(root.resolve("destinazione"));
        Path file = root.resolve("a.txt");
        writeFile(root, "a.txt", MTIME);
        ControlledTrash trash = new ControlledTrash(trashDir);
        Path manifest = root.resolve("scansione_20261110_100000.csv");
        Files.writeString(manifest, manifestCsv(root.toString(),
                row("a.txt", root.toString(), file, "TXT", "2026/11/09", "Yes")),
                StandardCharsets.UTF_8);

        OperationResult result = esegui(engine(root, trash),
                new DeletionCommand(manifest, DeletionMode.MOVE_TO_FOLDER, false, destination));

        assertEquals(OperationStatus.COMPLETED, result.status());
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(destination.resolve("a.txt")));
        assertTrue(Files.exists(root.resolve("moving_20261110_100000.csv")));
        assertTrue(Files.exists(root.resolve("moving_20261110_100000.log")));
        assertFalse(Files.exists(root.resolve("scansione_20261110_100000.csv.log")));
    }

    @Test
    void outputEsistentiNonVengonoSovrascritti(@TempDir Path root) throws Exception {
        Path trashDir = Files.createDirectories(root.resolve("cestino"));
        Path file = root.resolve("a.txt");
        writeFile(root, "a.txt", MTIME);
        ControlledTrash trash = new ControlledTrash(trashDir);
        Path manifest = root.resolve("scansione_20261110_100000.csv");
        Files.writeString(manifest, manifestCsv(root.toString(),
                row("a.txt", root.toString(), file, "TXT", "2026/11/09", "Yes")),
                StandardCharsets.UTF_8);
        Path existingReport = root.resolve("trash_20261110_100000.csv");
        Path existingAudit = root.resolve("trash_20261110_100000.log");
        Files.writeString(existingReport, "report precedente", StandardCharsets.UTF_8);
        Files.writeString(existingAudit, "audit precedente", StandardCharsets.UTF_8);

        OperationResult result = esegui(engine(root, trash), manifest);

        assertEquals(OperationStatus.COMPLETED, result.status());
        assertEquals("report precedente", Files.readString(existingReport, StandardCharsets.UTF_8));
        assertEquals("audit precedente", Files.readString(existingAudit, StandardCharsets.UTF_8));
        assertTrue(Files.exists(root.resolve("trash_20261110_100000_2.csv")));
        assertTrue(Files.exists(root.resolve("trash_20261110_100000_2.log")));
    }

    @Test
    void operazioniConcorrentiRiservanoCoppieDiOutputDistinte(@TempDir Path root) throws Exception {
        Path file = root.resolve("a.txt");
        writeFile(root, "a.txt", MTIME);
        Path manifest = root.resolve("scansione_20261110_100000.csv");
        Files.writeString(manifest, manifestCsv(root.toString(),
                row("a.txt", root.toString(), file, "TXT", "2026/11/09", "Yes")),
                StandardCharsets.UTF_8);
        CountDownLatch actionsStarted = new CountDownLatch(2);
        TrashAdapter concurrentTrash = ignored -> {
            actionsStarted.countDown();
            try {
                if (!actionsStarted.await(10, TimeUnit.SECONDS)) {
                    return new TrashResult(TrashResult.Status.FAILED, "timeout nel test");
                }
            } catch (InterruptedException exc) {
                Thread.currentThread().interrupt();
                return new TrashResult(TrashResult.Status.FAILED, "test interrotto");
            }
            return new TrashResult(TrashResult.Status.FAILED, "errore controllato");
        };
        DeletionEngine engine = new DeletionEngine(concurrentTrash,
                Clock.fixed(FIXED, ZoneOffset.UTC));
        CountDownLatch finished = new CountDownLatch(2);
        List<OperationResult> results = new CopyOnWriteArrayList<>();
        OperationObserver observer = new OperationObserver() {
            @Override
            public void onProgress(OperationProgress progress) {
            }

            @Override
            public void onFinished(OperationResult result) {
                results.add(result);
                finished.countDown();
            }
        };

        engine.start(new DeletionCommand(manifest, DeletionMode.TRASH, false), observer);
        engine.start(new DeletionCommand(manifest, DeletionMode.TRASH, false), observer);

        assertTrue(finished.await(30, TimeUnit.SECONDS));
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(result -> result.status() == OperationStatus.COMPLETED));
        assertTrue(Files.exists(root.resolve("trash_20261110_100000.csv")));
        assertTrue(Files.exists(root.resolve("trash_20261110_100000.log")));
        assertTrue(Files.exists(root.resolve("trash_20261110_100000_2.csv")));
        assertTrue(Files.exists(root.resolve("trash_20261110_100000_2.log")));
    }
}
