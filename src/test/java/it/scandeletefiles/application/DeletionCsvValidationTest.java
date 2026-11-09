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
 * Valutazione del contratto del manifest CSV: BOM UTF-8, separatore {@code ;}, sei intestazioni
 * esatte, righe malformate e valori Delete non ammessi non devono mai autorizzare cancellazioni.
 */
class DeletionCsvValidationTest {

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

    private static void fileIntattoENonCestinato(Path file, ControlledTrash trash) {
        assertTrue(Files.exists(file), "il file deve restare intatto");
        assertTrue(trash.moved.isEmpty(), "nessun file deve essere spostato nel cestino");
    }

    @Test
    void intestazioneSenzaBomNonConformeOperazioneFallitaENessunFileToccato(@TempDir Path root)
            throws Exception {
        Path trashDir = root.resolve("cestino");
        Files.createDirectories(trashDir);
        Path file = root.resolve("file.txt");
        writeFile(root, "file.txt", MTIME);

        ControlledTrash trash = new ControlledTrash(trashDir);
        DeletionEngine engine = engine(root, trash);

        String rootPath = root.toString();
        String csv = "Nome file;Path cartella;Path completo;Formato file;Data ultima modifica;Delete\n"
                + "file.txt;" + rootPath + ";" + file + ";TXT;2026/11/09;Yes\n";
        Path manifest = root.resolve("senza-bom.csv");
        Files.writeString(manifest, csv, StandardCharsets.UTF_8);

        OperationResult result = esegui(engine, manifest);
        assertEquals(OperationStatus.FAILED, result.status());
        fileIntattoENonCestinato(file, trash);
        assertFalse(Files.exists(root.resolve("senza-bom.report.csv")),
                "nessun report deve essere prodotto per un manifest non conforme");
    }

    @Test
    void intestazioneColonneDiverseNonConformeOperazioneFallita(@TempDir Path root) throws Exception {
        Path trashDir = root.resolve("cestino");
        Files.createDirectories(trashDir);
        Path file = root.resolve("file.txt");
        writeFile(root, "file.txt", MTIME);

        ControlledTrash trash = new ControlledTrash(trashDir);
        DeletionEngine engine = engine(root, trash);

        String rootPath = root.toString();
        String csv = "\uFEFFNome file;Percorso;Path completo;Formato;Data;Elimina\n"
                + "file.txt;" + rootPath + ";" + file + ";TXT;2026/11/09;Yes\n";
        Path manifest = root.resolve("colonne-sbagliate.csv");
        Files.writeString(manifest, csv, StandardCharsets.UTF_8);

        OperationResult result = esegui(engine, manifest);
        assertEquals(OperationStatus.FAILED, result.status());
        fileIntattoENonCestinato(file, trash);
    }

    @Test
    void rigaMalformataConDeleteYesNonAutorizzaCancellazione(@TempDir Path root) throws Exception {
        Path trashDir = root.resolve("cestino");
        Files.createDirectories(trashDir);
        Path file = root.resolve("file.txt");
        writeFile(root, "file.txt", MTIME);

        ControlledTrash trash = new ControlledTrash(trashDir);
        DeletionEngine engine = engine(root, trash);

        String rootPath = root.toString();
        // Sette campi anziché sei: riga malformata pur avendo Delete = Yes.
        String csv = manifestCsv(rootPath,
                row("file.txt", rootPath, file, "TXT", "2026/11/09", "Yes;extra"));
        Path manifest = root.resolve("malformata.csv");
        Files.writeString(manifest, csv, StandardCharsets.UTF_8);

        OperationResult result = esegui(engine, manifest);
        assertEquals(OperationStatus.COMPLETED, result.status());
        fileIntattoENonCestinato(file, trash);

        Path report = root.resolve("malformata.report.csv");
        List<String> lines = Files.readString(report, StandardCharsets.UTF_8).substring(1).lines().toList();
        assertEquals(2, lines.size());
        String[] parts = lines.get(1).split(";", -1);
        assertEquals("Errore", parts[6], "la riga malformata con Delete = Yes deve avere Esito = Errore");
        assertTrue(parts[7].contains("incoerente"),
                "il dettaglio deve spiegare che la riga non è coerente");
    }

    @Test
    void valoreDeleteNonAmmessoGeneraErroreENonCancella(@TempDir Path root) throws Exception {
        Path trashDir = root.resolve("cestino");
        Files.createDirectories(trashDir);
        Path file = root.resolve("file.txt");
        writeFile(root, "file.txt", MTIME);

        ControlledTrash trash = new ControlledTrash(trashDir);
        DeletionEngine engine = engine(root, trash);

        String rootPath = root.toString();
        Path manifest = root.resolve("valore-sconosciuto.csv");
        Files.writeString(manifest, manifestCsv(rootPath,
                row("file.txt", rootPath, file, "TXT", "2026/11/09", "Forse")),
                StandardCharsets.UTF_8);

        OperationResult result = esegui(engine, manifest);
        assertEquals(OperationStatus.COMPLETED, result.status());
        fileIntattoENonCestinato(file, trash);

        Path report = root.resolve("valore-sconosciuto.report.csv");
        List<String> lines = Files.readString(report, StandardCharsets.UTF_8).substring(1).lines().toList();
        String[] parts = lines.get(1).split(";", -1);
        assertEquals("Errore", parts[6]);
        assertTrue(parts[7].contains("non ammesso"));
    }

    @Test
    void incoerenzaNomeFileGeneraErroreESalto(@TempDir Path root) throws Exception {
        Path trashDir = root.resolve("cestino");
        Files.createDirectories(trashDir);
        Path file = root.resolve("file.txt");
        writeFile(root, "file.txt", MTIME);

        ControlledTrash trash = new ControlledTrash(trashDir);
        DeletionEngine engine = engine(root, trash);

        String rootPath = root.toString();
        // Nome file della riga diverso dal nome del Path completo.
        Path manifest = root.resolve("incoerente-nome.csv");
        Files.writeString(manifest, manifestCsv(rootPath,
                row("documento.txt", rootPath, file, "TXT", "2026/11/09", "yes")),
                StandardCharsets.UTF_8);

        OperationResult result = esegui(engine, manifest);
        assertEquals(OperationStatus.COMPLETED, result.status());
        fileIntattoENonCestinato(file, trash);

        Path report = root.resolve("incoerente-nome.report.csv");
        List<String> lines = Files.readString(report, StandardCharsets.UTF_8).substring(1).lines().toList();
        assertEquals("Errore", lines.get(1).split(";", -1)[6]);
    }

    @Test
    void incoerenzaFormatoGeneraErroreESalto(@TempDir Path root) throws Exception {
        Path trashDir = root.resolve("cestino");
        Files.createDirectories(trashDir);
        Path file = root.resolve("file.txt");
        writeFile(root, "file.txt", MTIME);

        ControlledTrash trash = new ControlledTrash(trashDir);
        DeletionEngine engine = engine(root, trash);

        String rootPath = root.toString();
        Path manifest = root.resolve("incoerente-formato.csv");
        Files.writeString(manifest, manifestCsv(rootPath,
                row("file.txt", rootPath, file, "PDF", "2026/11/09", "YES")),
                StandardCharsets.UTF_8);

        OperationResult result = esegui(engine, manifest);
        assertEquals(OperationStatus.COMPLETED, result.status());
        fileIntattoENonCestinato(file, trash);

        Path report = root.resolve("incoerente-formato.report.csv");
        assertEquals("Errore",
                Files.readString(report, StandardCharsets.UTF_8).substring(1).lines().toList().get(1)
                        .split(";", -1)[6]);
    }

    @Test
    void righeValideYesNoEVuotoVengonoTrattateCorrettamente(@TempDir Path root) throws Exception {
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
        Path manifest = root.resolve("valide.csv");
        Files.writeString(manifest, manifestCsv(rootPath,
                row("a.txt", rootPath, a, "TXT", "2026/11/09", "yes"),
                row("b.txt", rootPath, b, "TXT", "2026/11/09", "No"),
                row("c.txt", rootPath, c, "TXT", "2026/11/09", "")),
                StandardCharsets.UTF_8);

        OperationResult result = esegui(engine, manifest);
        assertEquals(OperationStatus.COMPLETED, result.status());

        assertFalse(Files.exists(a), "Delete = yes (case-insensitive) cestina il file");
        assertTrue(Files.exists(b), "Delete = No conserva il file");
        assertTrue(Files.exists(c), "Delete vuoto conserva il file");
        assertEquals(1, trash.moved.size(), "una sola riga Yes deve essere cestinata");

        Path report = root.resolve("valide.report.csv");
        List<String> lines = Files.readString(report, StandardCharsets.UTF_8).substring(1).lines().toList();
        assertEquals(4, lines.size());
        assertEquals("Cestinato", lines.get(1).split(";", -1)[6]);
        assertEquals("Saltato", lines.get(2).split(";", -1)[6]);
        assertEquals("Saltato", lines.get(3).split(";", -1)[6]);
    }
}
