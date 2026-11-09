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
 * Ricontrollo di esistenza e ultima modifica {@code yyyy/MM/dd} prima dell'azione di cancellazione.
 * Il confronto della data avviene a granularità di giorno: una sovrascrittura del file avvenuta
 * nello stesso giorno del manifest non viene rilevata ed è il limite accettato del contratto.
 */
class DeletionRecheckTest {

    private static final Instant FIXED = Instant.parse("2026-11-09T10:00:00Z");
    private static final Instant MTIME_STESSO_GIORNO = Instant.parse("2026-11-09T23:00:00Z");
    private static final Instant MTIME_GIORNO_PRIMA = Instant.parse("2026-11-08T23:00:00Z");

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
        assertTrue(finished.await(30, TimeUnit.SECONDS), "l'operazione non è terminata");
        return result.get();
    }

    @Test
    void fileMancanteVieneSegnalatoComeErroreENonCancellato(@TempDir Path root) throws Exception {
        Path trashDir = root.resolve("cestino");
        Files.createDirectories(trashDir);
        Path mancante = root.resolve("mancante.txt");

        ControlledTrash trash = new ControlledTrash(trashDir);
        DeletionEngine engine = engine(root, trash);

        String rootPath = root.toString();
        Path manifest = root.resolve("mancante.csv");
        Files.writeString(manifest, manifestCsv(rootPath,
                row("mancante.txt", rootPath, mancante, "TXT", "2026/11/09", "Yes")),
                StandardCharsets.UTF_8);

        OperationResult result = esegui(engine, manifest);
        assertEquals(OperationStatus.COMPLETED, result.status());
        assertFalse(Files.exists(mancante), "il file non esisteva nemmeno prima");
        assertTrue(trash.moved.isEmpty(), "nessun file è stato spostato nel cestino");

        List<String> lines = Files.readString(root.resolve("mancante.report.csv"),
                StandardCharsets.UTF_8).substring(1).lines().toList();
        assertEquals("Errore", lines.get(1).split(";", -1)[6]);
        assertTrue(lines.get(1).contains("non trovato"), "il dettaglio deve segnalare il file mancante");
    }

    @Test
    void dataUltimaModificaDiversaVieneSaltataEFileConservato(@TempDir Path root) throws Exception {
        Path trashDir = root.resolve("cestino");
        Files.createDirectories(trashDir);
        Path file = root.resolve("modificato.txt");
        writeFile(root, "modificato.txt", MTIME_GIORNO_PRIMA);

        ControlledTrash trash = new ControlledTrash(trashDir);
        DeletionEngine engine = engine(root, trash);

        String rootPath = root.toString();
        Path manifest = root.resolve("data-diversa.csv");
        Files.writeString(manifest, manifestCsv(rootPath,
                row("modificato.txt", rootPath, file, "TXT", "2026/11/09", "Yes")),
                StandardCharsets.UTF_8);

        OperationResult result = esegui(engine, manifest);
        assertEquals(OperationStatus.COMPLETED, result.status());

        assertTrue(Files.exists(file), "la data non corrispondente conserva il file");
        assertTrue(trash.moved.isEmpty(), "nessun file spostato nel cestino");

        List<String> lines = Files.readString(root.resolve("data-diversa.report.csv"),
                StandardCharsets.UTF_8).substring(1).lines().toList();
        assertEquals("Saltato", lines.get(1).split(";", -1)[6]);
        assertTrue(lines.get(1).contains("non corrispondente"));
    }

    @Test
    void sostituzioneNelloStessoGiornoEAccettataLimiteGranularitaGiornaliera(@TempDir Path root)
            throws Exception {
        // Il confronto della data ultima modifica usa solo yyyy/MM/dd: un file riscritto
        // nello stesso giorno del manifest supera il ricontrollo. Questo è il limite accettato
        // del contratto: sostituzioni avvenute nello stesso giorno non vengono rilevate.
        Path trashDir = root.resolve("cestino");
        Files.createDirectories(trashDir);
        Path file = root.resolve("riscritto.txt");
        writeFile(root, "riscritto.txt", MTIME_STESSO_GIORNO);

        ControlledTrash trash = new ControlledTrash(trashDir);
        DeletionEngine engine = engine(root, trash);

        String rootPath = root.toString();
        Path manifest = root.resolve("stesso-giorno.csv");
        Files.writeString(manifest, manifestCsv(rootPath,
                row("riscritto.txt", rootPath, file, "TXT", "2026/11/09", "Yes")),
                StandardCharsets.UTF_8);

        OperationResult result = esegui(engine, manifest);
        assertEquals(OperationStatus.COMPLETED, result.status());

        assertFalse(Files.exists(file), "stesso giorno: il file viene comunque cestinato");
        assertEquals(1, trash.moved.size());

        List<String> lines = Files.readString(root.resolve("stesso-giorno.report.csv"),
                StandardCharsets.UTF_8).substring(1).lines().toList();
        assertEquals("Cestinato", lines.get(1).split(";", -1)[6]);
    }

    @Test
    void dataUltimaModificaNonValidaNelManifestGeneraErrore(@TempDir Path root) throws Exception {
        Path trashDir = root.resolve("cestino");
        Files.createDirectories(trashDir);
        Path file = root.resolve("file.txt");
        writeFile(root, "file.txt", MTIME_STESSO_GIORNO);

        ControlledTrash trash = new ControlledTrash(trashDir);
        DeletionEngine engine = engine(root, trash);

        String rootPath = root.toString();
        Path manifest = root.resolve("data-non-valida.csv");
        Files.writeString(manifest, manifestCsv(rootPath,
                row("file.txt", rootPath, file, "TXT", "non-una-data", "Yes")),
                StandardCharsets.UTF_8);

        OperationResult result = esegui(engine, manifest);
        assertEquals(OperationStatus.COMPLETED, result.status());

        assertTrue(Files.exists(file), "data non valida: il file resta conservato");
        assertTrue(trash.moved.isEmpty());

        List<String> lines = Files.readString(root.resolve("data-non-valida.report.csv"),
                StandardCharsets.UTF_8).substring(1).lines().toList();
        assertEquals("Errore", lines.get(1).split(";", -1)[6]);
        assertTrue(lines.get(1).contains("non valida"));
    }
}
