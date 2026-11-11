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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoveToFolderDeletionTest {

    private static final Instant FIXED = Instant.parse("2026-11-09T10:00:00Z");

    private static PlatformAdapters platform() {
        return new PlatformAdapters(
                FileSystems.getDefault(),
                file -> {
                    // Non usato nello spostamento in cartella
                    return null;
                },
                new SettingsAdapter() {
                    @Override
                    public Set<String> loadSelectedExtensions() {
                        return Set.of();
                    }

                    @Override
                    public void saveSelectedExtensions(Set<String> extensions) {
                    }
                },
                Clock.fixed(FIXED, java.time.ZoneOffset.UTC));
    }

    @Test
    void spostaFileYesInCartellaDestinazioneERestanoIntattiNoEVuoto(@TempDir Path root) throws Exception {
        Path destination = root.resolve("destinazione");
        Files.createDirectories(destination);

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

        DeletionEngine engine = new DeletionEngine(platform().trash(), platform().clock());

        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<OperationResult> result = new AtomicReference<>();
        engine.start(new DeletionCommand(manifest, DeletionMode.MOVE_TO_FOLDER, false, destination),
                new OperationObserver() {
                    @Override
                    public void onProgress(OperationProgress p) {
                    }

                    @Override
                    public void onFinished(OperationResult r) {
                        result.set(r);
                        finished.countDown();
                    }
                });

        assertTrue(finished.await(30, TimeUnit.SECONDS), "lo spostamento non è terminato");
        assertEquals(OperationStatus.COMPLETED, result.get().status());

        // a.txt e d.txt sono stati spostati; b.TXT (No) e c.txt (vuoto) restano intatti
        assertFalse(Files.exists(a), "il file Yes deve essere stato spostato");
        assertFalse(Files.exists(d), "il file YES deve essere stato spostato");
        assertTrue(Files.exists(destination.resolve("a.txt")), "a.txt deve trovarsi nella cartella di destinazione");
        assertTrue(Files.exists(destination.resolve("d.txt")), "d.txt deve trovarsi nella cartella di destinazione");
        assertTrue(Files.exists(b), "il file No deve essere conservato");
        assertTrue(Files.exists(c), "il file con Delete vuoto deve essere conservato");

        Path report = root.resolve("moving_manifest.csv");
        assertTrue(Files.exists(report), "il report deve essere scritto accanto al manifest");
        String content = Files.readString(report, StandardCharsets.UTF_8);
        List<String> lines = content.substring(1).lines().toList();
        assertEquals("Nome file;Path cartella;Path completo;Formato file;Data ultima modifica;Delete;Esito;Dettaglio",
                lines.get(0));
        assertEquals(5, lines.size(), "una riga di report per ogni riga di input");

        Map<String, String[]> byName = new java.util.HashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] parts = line.split(";", -1);
            byName.put(parts[0], parts);
        }
        assertEquals("Spostato", byName.get("a.txt")[6]);
        assertEquals("Saltato", byName.get("b.TXT")[6]);
        assertEquals("Saltato", byName.get("c.txt")[6]);
        assertEquals("Spostato", byName.get("d.txt")[6]);

        Path audit = root.resolve("moving_manifest.log");
        assertTrue(Files.exists(audit), "il log di audit deve essere scritto accanto al manifest");
        String log = Files.readString(audit, StandardCharsets.UTF_8);
        assertTrue(log.contains("Stato: COMPLETATA"));
        assertTrue(log.contains("Righe Da eliminare: 2"));
        assertTrue(log.contains("File spostati in cartella: 2"));
        assertTrue(log.contains("File cestinati: 0"));
        assertTrue(log.contains("File eliminati permanentemente: 0"));
        assertTrue(log.contains("Righe saltate: 2"));
        assertTrue(log.contains("Errori: 0"));
    }

    @Test
    void evitaSovrascritturaFileGiùPresenteNellaDestinazione(@TempDir Path root) throws Exception {
        Path destination = root.resolve("destinazione");
        Files.createDirectories(destination);

        Path sorgente = root.resolve("duplicato.txt");
        Files.writeString(sorgente, "contenuto sorgente");
        Files.setLastModifiedTime(sorgente, FileTime.from(Instant.parse("2026-11-09T23:00:00Z")));

        Path giaPresente = destination.resolve("duplicato.txt");
        Files.writeString(giaPresente, "contenuto gia presente");
        Files.setLastModifiedTime(giaPresente, FileTime.from(Instant.parse("2026-11-08T23:00:00Z")));

        String rootPath = root.toString();
        String csv = "\uFEFF"
                + "Nome file;Path cartella;Path completo;Formato file;Data ultima modifica;Delete\n"
                + "duplicato.txt;" + rootPath + ";" + sorgente + ";TXT;2026/11/09;Yes\n";
        Path manifest = root.resolve("manifest.csv");
        Files.writeString(manifest, csv, StandardCharsets.UTF_8);

        DeletionEngine engine = new DeletionEngine(platform().trash(), platform().clock());

        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<OperationResult> result = new AtomicReference<>();
        engine.start(new DeletionCommand(manifest, DeletionMode.MOVE_TO_FOLDER, false, destination),
                new OperationObserver() {
                    @Override
                    public void onProgress(OperationProgress p) {
                    }

                    @Override
                    public void onFinished(OperationResult r) {
                        result.set(r);
                        finished.countDown();
                    }
                });

        assertTrue(finished.await(30, TimeUnit.SECONDS), "lo spostamento non è terminato");
        assertEquals(OperationStatus.COMPLETED, result.get().status());

        assertFalse(Files.exists(sorgente), "il file sorgente non deve più esistere");
        assertTrue(Files.exists(giaPresente), "il file già presente non deve essere sovrascritto");
        assertTrue(Files.exists(destination.resolve("duplicato_2.txt")),
                "il file spostato deve assumere il nome duplicato_2.txt");

        Path report = root.resolve("moving_manifest.csv");
        String content = Files.readString(report, StandardCharsets.UTF_8);
        List<String> lines = content.substring(1).lines().toList();
        String[] parts = lines.get(1).split(";", -1);
        assertEquals("Spostato", parts[6]);
        assertTrue(parts[7].contains("duplicato_2.txt"), "il dettaglio deve indicare la destinazione effettiva");
    }

    @Test
    void spostamentoRipristinaDataModificaDellaCartellaSorgente(@TempDir Path root) throws Exception {
        Path source = Files.createDirectory(root.resolve("sorgente"));
        Path destination = Files.createDirectory(root.resolve("destinazione"));
        Path file = source.resolve("documento.txt");
        Files.writeString(file, "contenuto");
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-11-09T23:00:00Z")));
        Path inputCsv = source.resolve("elenco.csv");
        Files.writeString(inputCsv, "\uFEFF"
                + "Nome file;Path cartella;Path completo;Formato file;Data ultima modifica;Delete\n"
                + "documento.txt;" + source + ";" + file + ";TXT;2026/11/09;Yes\n",
                StandardCharsets.UTF_8);
        FileTime originalFolderTime = FileTime.from(Instant.parse("2024-03-04T05:06:07Z"));
        Files.setLastModifiedTime(source, originalFolderTime);

        DeletionEngine engine = new DeletionEngine(platform().trash(), platform().clock());
        CountDownLatch finished = new CountDownLatch(1);
        engine.start(new DeletionCommand(inputCsv, DeletionMode.MOVE_TO_FOLDER, false, destination),
                new OperationObserver() {
                    @Override
                    public void onProgress(OperationProgress progress) {
                    }

                    @Override
                    public void onFinished(OperationResult result) {
                        finished.countDown();
                    }
                });

        assertTrue(finished.await(30, TimeUnit.SECONDS));
        assertEquals(originalFolderTime, Files.getLastModifiedTime(source),
                "la cartella sorgente deve conservare la propria data di modifica");
        assertTrue(Files.exists(destination.resolve("documento.txt")));
    }

    @Test
    void erroreNelRipristinoDataRendeOperazioneFallitaELoRegistra(@TempDir Path root) throws Exception {
        Path source = Files.createDirectory(root.resolve("sorgente"));
        Path destination = Files.createDirectory(root.resolve("destinazione"));
        Path file = source.resolve("documento.txt");
        Files.writeString(file, "contenuto");
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-11-09T23:00:00Z")));
        Path inputCsv = source.resolve("elenco.csv");
        Files.writeString(inputCsv, "\uFEFF"
                + "Nome file;Path cartella;Path completo;Formato file;Data ultima modifica;Delete\n"
                + "documento.txt;" + source + ";" + file + ";TXT;2026/11/09;Yes\n",
                StandardCharsets.UTF_8);
        DeletionEngine engine = new DeletionEngine(platform().trash(), platform().clock(),
                (directory, timestamp) -> {
                    throw new IOException("ripristino non consentito");
                });
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<OperationResult> result = new AtomicReference<>();

        engine.start(new DeletionCommand(inputCsv, DeletionMode.MOVE_TO_FOLDER, false, destination),
                new OperationObserver() {
                    @Override
                    public void onProgress(OperationProgress progress) {
                    }

                    @Override
                    public void onFinished(OperationResult operationResult) {
                        result.set(operationResult);
                        finished.countDown();
                    }
                });

        assertTrue(finished.await(30, TimeUnit.SECONDS));
        assertEquals(OperationStatus.FAILED, result.get().status());
        String audit = Files.readString(source.resolve("moving_elenco.log"), StandardCharsets.UTF_8);
        assertTrue(audit.contains("Stato: FALLITA"));
        assertTrue(audit.contains("ripristino non consentito"));
    }

    @Test
    void spostaMantenendoAlberaturaDellaRootETimestamp(@TempDir Path root) throws Exception {
        Path sourceRoot = Files.createDirectory(root.resolve("archivio"));
        Path clienti = Files.createDirectory(sourceRoot.resolve("clienti"));
        Path rossi = Files.createDirectory(clienti.resolve("rossi"));
        Path destination = Files.createDirectory(root.resolve("destinazione"));
        Path destinationClienti = Files.createDirectory(destination.resolve("clienti"));
        Path file = rossi.resolve("documento.txt");
        Files.writeString(file, "contenuto");

        FileTime fileTime = FileTime.from(Instant.parse("2025-01-02T03:04:05Z"));
        FileTime rootTime = FileTime.from(Instant.parse("2024-01-02T03:04:05Z"));
        FileTime clientiTime = FileTime.from(Instant.parse("2024-02-03T04:05:06Z"));
        FileTime rossiTime = FileTime.from(Instant.parse("2024-03-04T05:06:07Z"));
        FileTime destinationClientiTime = FileTime.from(Instant.parse("2023-04-05T06:07:08Z"));
        FileTime destinationRootTime = FileTime.from(Instant.parse("2023-01-02T03:04:05Z"));
        Files.setLastModifiedTime(file, fileTime);
        Files.setLastModifiedTime(sourceRoot, rootTime);
        Files.setLastModifiedTime(clienti, clientiTime);
        Files.setLastModifiedTime(rossi, rossiTime);
        Files.setLastModifiedTime(destinationClienti, destinationClientiTime);
        Files.setLastModifiedTime(destination, destinationRootTime);

        Path inputCsv = root.resolve("elenco.csv");
        Files.writeString(inputCsv, "\uFEFF"
                + "Nome file;Path cartella;Path completo;Formato file;Data ultima modifica;Delete\n"
                + "documento.txt;" + rossi + ";" + file + ";TXT;2025/01/02;Yes\n",
                StandardCharsets.UTF_8);

        DeletionEngine engine = new DeletionEngine(platform().trash(), platform().clock());
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<OperationResult> result = new AtomicReference<>();
        engine.start(new DeletionCommand(inputCsv, DeletionMode.MOVE_TO_FOLDER, false,
                        destination, sourceRoot),
                new OperationObserver() {
                    @Override
                    public void onProgress(OperationProgress progress) {
                    }

                    @Override
                    public void onFinished(OperationResult operationResult) {
                        result.set(operationResult);
                        finished.countDown();
                    }
                });

        assertTrue(finished.await(30, TimeUnit.SECONDS));
        assertEquals(OperationStatus.COMPLETED, result.get().status());
        Path moved = destination.resolve("clienti/rossi/documento.txt");
        assertTrue(Files.exists(moved));
        assertEquals(fileTime, Files.getLastModifiedTime(moved));
        assertEquals(rootTime, Files.getLastModifiedTime(sourceRoot));
        assertEquals(clientiTime, Files.getLastModifiedTime(clienti));
        assertEquals(rossiTime, Files.getLastModifiedTime(rossi));
        assertEquals(destinationRootTime, Files.getLastModifiedTime(destination));
        assertEquals(destinationClientiTime, Files.getLastModifiedTime(destination.resolve("clienti")));
        assertEquals(rossiTime, Files.getLastModifiedTime(destination.resolve("clienti/rossi")));
    }

    @Test
    void comandoSenzaDestinazioneInModalitaSpostaVieneRifiutato() {
        assertThrows(NullPointerException.class,
                () -> new DeletionCommand(rootPath("manifest.csv"), DeletionMode.MOVE_TO_FOLDER, false, null));
    }

    private static Path rootPath(String name) {
        return Path.of("/tmp/test", name);
    }
}
