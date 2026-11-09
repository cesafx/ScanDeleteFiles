package it.scandeletefiles.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.scandeletefiles.bootstrap.PlatformAdapters;
import it.scandeletefiles.application.CancellableOperation;
import it.scandeletefiles.application.OperationObserver;
import it.scandeletefiles.application.OperationProgress;
import it.scandeletefiles.application.OperationResult;
import it.scandeletefiles.application.OperationStatus;
import it.scandeletefiles.platform.SettingsAdapter;
import it.scandeletefiles.platform.TrashAdapter;
import it.scandeletefiles.platform.TrashResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrashDeletionHappyPathTest {

    private static final Instant FIXED = Instant.parse("2026-11-09T10:00:00Z");

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
            } catch (IOException exc) {
                return new TrashResult(TrashResult.Status.FAILED, exc.getMessage());
            }
            return new TrashResult(TrashResult.Status.MOVED, "spostato nel cestino di test");
        }
    }

    private static PlatformAdapters platform(Path trashDir, ControlledTrash trash) throws Exception {
        return new PlatformAdapters(
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
    }

    @Test
    void processaSoloRigheYesSpostandoleNelCestino(@TempDir Path root) throws Exception {
        Path trashDir = root.resolve("cestino");
        Files.createDirectories(trashDir);

        Path a = root.resolve("a.txt");
        Path b = root.resolve("b.TXT");
        Path c = root.resolve("c.txt");
        Path d = root.resolve("d.txt");
        Files.writeString(a, "contenuto a");
        Files.writeString(b, "contenuto b");
        Files.writeString(c, "contenuto c");
        Files.writeString(d, "contenuto d");
        Instant mtime = Instant.parse("2026-11-09T23:00:00Z");
        for (Path file : List.of(a, b, c, d)) {
            Files.setLastModifiedTime(file, FileTime.from(mtime));
        }

        String rootPath = root.toString();
        String csv = "\uFEFF"
                + "Nome file;Path cartella;Path completo;Formato file;Data ultima modifica;Delete\n"
                + "a.txt;" + rootPath + ";" + a + ";TXT;2026/11/09;yes\n"
                + "b.TXT;" + rootPath + ";" + b + ";TXT;2026/11/09;No\n"
                + "c.txt;" + rootPath + ";" + c + ";TXT;2026/11/09;\n"
                + "d.txt;" + rootPath + ";" + d + ";TXT;2026/11/09;YES\n";
        Path manifest = root.resolve("manifest.csv");
        Files.writeString(manifest, csv, StandardCharsets.UTF_8);

        PlatformAdapters platform = platform(trashDir, new ControlledTrash(trashDir));
        ControlledTrash trash = (ControlledTrash) platform.trash();
        DeletionEngine engine = new DeletionEngine(trash, platform.clock());

        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<OperationResult> result = new AtomicReference<>();
        List<OperationProgress> progress = new CopyOnWriteArrayList<>();
        engine.start(new DeletionCommand(manifest, DeletionMode.TRASH, false), new OperationObserver() {
            @Override
            public void onProgress(OperationProgress p) {
                progress.add(p);
            }

            @Override
            public void onFinished(OperationResult r) {
                result.set(r);
                finished.countDown();
            }
        });

        assertTrue(finished.await(30, TimeUnit.SECONDS), "la cancellazione non è terminata");
        assertEquals(OperationStatus.COMPLETED, result.get().status());
        assertFalse(progress.isEmpty(), "il servizio deve riportare l'avanzamento");

        // a.txt e d.txt (Yes / YES) sono stati cestinati; b.TXT (No) e c.txt (vuoto) restano intatti
        List<Path> moved = trash.moved;
        assertTrue(moved.stream().anyMatch(p -> p.getFileName().toString().equals("a.txt")));
        assertFalse(Files.exists(a), "il file Yes deve essere stato spostato nel cestino");
        assertFalse(Files.exists(d), "il file YES deve essere stato spostato nel cestino");
        assertTrue(Files.exists(b), "il file No deve essere conservato");
        assertTrue(Files.exists(c), "il file con Delete vuoto deve essere conservato");

        // Report accanto all'input: sei colonne originali + Esito e Dettaglio
        Path report = root.resolve("manifest.report.csv");
        assertTrue(Files.exists(report), "il report deve essere scritto accanto al manifest");
        String content = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(content.startsWith("\uFEFF"), "il report deve iniziare con il BOM UTF-8");
        List<String> lines = content.substring(1).lines().toList();
        assertEquals("Nome file;Path cartella;Path completo;Formato file;Data ultima modifica;Delete;Esito;Dettaglio",
                lines.get(0));
        assertEquals(5, lines.size(), "una riga di report per ogni riga di input");

        Map<String, String[]> byName = new java.util.HashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] parts = line.split(";", -1);
            byName.put(parts[0], parts);
        }
        assertEquals("Cestinato", byName.get("a.txt")[6]);
        assertEquals("Saltato", byName.get("b.TXT")[6]);
        assertEquals("Saltato", byName.get("c.txt")[6]);
        assertEquals("Cestinato", byName.get("d.txt")[6]);
        byName.values().forEach(parts -> assertEquals(8, parts.length, "ogni riga deve avere 6 colonne + Esito + Dettaglio"));

        // Audit accanto al CSV di input
        Path audit = root.resolve("manifest.csv.log");
        assertTrue(Files.exists(audit), "il log di audit deve essere scritto accanto al manifest");
        String log = Files.readString(audit, StandardCharsets.UTF_8);
        assertTrue(log.contains("Stato: COMPLETATA"), "l'audit deve dichiarare lo stato finale");
        assertTrue(log.contains("Inizio:"), "l'audit deve riportare l'inizio");
        assertTrue(log.contains("Fine:"), "l'audit deve riportare la fine");
        assertTrue(log.contains("Durata:"), "l'audit deve riportare la durata");
        assertTrue(log.contains("Righe controllate: 4"), "l'audit deve contare le righe controllate");
        assertTrue(log.contains("Righe Da eliminare: 2"), "l'audit deve contare le righe Delete=Yes");
        assertTrue(log.contains("File cestinati: 2"), "l'audit deve contare i file cestinati");
        assertTrue(log.contains("File eliminati permanentemente: 0"), "l'audit deve contare le eliminazioni definitive");
        assertTrue(log.contains("Righe saltate: 2"), "l'audit deve contare le righe saltate");
        assertTrue(log.contains("Errori: 0"), "l'audit deve contare gli errori");
        assertTrue(log.contains("Percentuali per formato:"), "l'audit deve riportare le percentuali per formato");
        assertTrue(log.contains("TXT: 100,0%"), "l'audit deve riportare la percentuale del formato TXT");
    }

    @Test
    void rigaIncoerenteNonVieneCancellataERegistrataComeErrore(@TempDir Path root) throws Exception {
        Path trashDir = root.resolve("cestino");
        Files.createDirectories(trashDir);

        Path file = root.resolve("real.txt");
        Files.writeString(file, "contenuto");
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-11-09T23:00:00Z")));

        String rootPath = root.toString();
        String csv = "\uFEFF"
                + "Nome file;Path cartella;Path completo;Formato file;Data ultima modifica;Delete\n"
                + "real.txt;" + rootPath + ";" + root.resolve("altro.txt") + ";TXT;2026/11/09;yes\n";
        Path manifest = root.resolve("manifest.csv");
        Files.writeString(manifest, csv, StandardCharsets.UTF_8);

        PlatformAdapters platform = platform(trashDir, new ControlledTrash(trashDir));
        ControlledTrash trash = (ControlledTrash) platform.trash();
        DeletionEngine engine = new DeletionEngine(trash, platform.clock());

        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<OperationResult> result = new AtomicReference<>();
        engine.start(new DeletionCommand(manifest, DeletionMode.TRASH, false), new OperationObserver() {
            @Override
            public void onProgress(OperationProgress p) {
            }

            @Override
            public void onFinished(OperationResult r) {
                result.set(r);
                finished.countDown();
            }
        });

        assertTrue(finished.await(30, TimeUnit.SECONDS), "la cancellazione non è terminata");
        assertEquals(OperationStatus.COMPLETED, result.get().status());

        // La riga incoerente non produce cancellazione né spostamento nel cestino
        assertTrue(Files.exists(file), "la riga incoerente non deve cancellare il file");
        assertTrue(trash.moved.isEmpty(), "nessun file deve essere spostato nel cestino");

        Path report = root.resolve("manifest.report.csv");
        String content = Files.readString(report, StandardCharsets.UTF_8);
        List<String> lines = content.substring(1).lines().toList();
        assertEquals(2, lines.size(), "header del report più una sola riga di esito");
        String[] parts = lines.get(1).split(";", -1);
        assertEquals("Errore", parts[6], "la riga incoerente deve avere Esito = Errore");

        Path audit = root.resolve("manifest.csv.log");
        String log = Files.readString(audit, StandardCharsets.UTF_8);
        assertTrue(log.contains("Righe Da eliminare: 1"), "l'audit conta la riga Yes tra le da eliminare");
        assertTrue(log.contains("Errori: 1"), "l'audit conta la riga incoerente come errore");
    }
}
