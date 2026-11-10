package it.scandeletefiles.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.scandeletefiles.platform.TrashAdapter;
import it.scandeletefiles.platform.TrashResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnsupportedTrashTest {

    private static final Instant MODIFIED = Instant.parse("2026-11-09T23:00:00Z");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void cestinoNonDisponibileSaltaLaRigaSenzaToccareIlFile(@TempDir Path root) throws Exception {
        Path file = fileAndManifest(root, "non-disponibile.csv");
        TrashAdapter unavailable = ignored -> new TrashResult(
                TrashResult.Status.UNAVAILABLE, "cestino non disponibile");

        OperationResult result = execute(root.resolve("non-disponibile.csv"), unavailable);

        assertEquals(OperationStatus.COMPLETED, result.status());
        assertTrue(Files.exists(file), "il file deve restare intatto");
        String[] reportRow = reportRow(root.resolve("trash_non-disponibile.csv"));
        assertEquals("Saltato", reportRow[6]);
        assertTrue(reportRow[7].contains("cestino non disponibile"));
        String audit = Files.readString(root.resolve("trash_non-disponibile.log"));
        assertTrue(audit.contains("Righe saltate: 1"));
        assertTrue(audit.contains("Errori: 0"));
    }

    @Test
    void erroreDelCestinoConservaIlFileERegistraErrore(@TempDir Path root) throws Exception {
        Path file = fileAndManifest(root, "errore.csv");
        TrashAdapter failed = ignored -> new TrashResult(
                TrashResult.Status.FAILED, "accesso negato");

        OperationResult result = execute(root.resolve("errore.csv"), failed);

        assertEquals(OperationStatus.COMPLETED, result.status());
        assertTrue(Files.exists(file), "un errore del cestino non deve eliminare il file");
        String[] reportRow = reportRow(root.resolve("trash_errore.csv"));
        assertEquals("Errore", reportRow[6]);
        assertTrue(reportRow[7].contains("accesso negato"));
    }

    private static Path fileAndManifest(Path root, String manifestName) throws Exception {
        Path file = root.resolve("file.txt");
        Files.writeString(file, "contenuto");
        Files.setLastModifiedTime(file, FileTime.from(MODIFIED));
        String csv = "\uFEFFNome file;Path cartella;Path completo;Formato file;Data ultima modifica;Delete\n"
                + "file.txt;" + root + ";" + file + ";TXT;2026/11/09;Yes\n";
        Files.writeString(root.resolve(manifestName), csv, StandardCharsets.UTF_8);
        return file;
    }

    private static OperationResult execute(Path manifest, TrashAdapter trash) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<OperationResult> result = new AtomicReference<>();
        new DeletionEngine(trash, CLOCK).start(
                new DeletionCommand(manifest, DeletionMode.TRASH, false),
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
        assertTrue(finished.await(30, TimeUnit.SECONDS), "l'operazione non è terminata");
        return result.get();
    }

    private static String[] reportRow(Path report) throws Exception {
        List<String> lines = Files.readString(report, StandardCharsets.UTF_8).substring(1).lines().toList();
        assertEquals(2, lines.size());
        return lines.get(1).split(";", -1);
    }
}
