package it.scandeletefiles.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

class PermanentDeletionConfirmationTest {

    private static final Instant MODIFIED = Instant.parse("2026-11-09T23:00:00Z");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void richiestaDiConfermaDichiaraCheLaCancellazioneEIrreversibile() {
        assertTrue(PermanentDeletionConfirmation.WARNING.toLowerCase().contains("irreversibile"));
    }

    @Test
    void confermaEsplicitaConsenteLaCancellazioneIrreversibile(@TempDir Path root) throws Exception {
        Path file = fileAndManifest(root, "confermata.csv");

        OperationResult result = execute(root.resolve("confermata.csv"), true);

        assertEquals(OperationStatus.COMPLETED, result.status());
        assertFalse(Files.exists(file), "la conferma esplicita deve consentire la cancellazione permanente");
        assertEquals("Eliminato definitivamente", reportRow(root.resolve("deleting_confermata.csv"))[6]);
    }

    @Test
    void rifiutoOChiusuraDellaConfermaConservaIlFile(@TempDir Path root) throws Exception {
        Path file = fileAndManifest(root, "rifiutata.csv");

        OperationResult result = execute(root.resolve("rifiutata.csv"), false);

        assertEquals(OperationStatus.COMPLETED, result.status());
        assertTrue(Files.exists(file), "senza conferma il file deve restare intatto");
        String[] reportRow = reportRow(root.resolve("deleting_rifiutata.csv"));
        assertEquals("Saltato", reportRow[6]);
        assertTrue(reportRow[7].contains("non confermata"));
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

    private static OperationResult execute(Path manifest, boolean confirmed) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<OperationResult> result = new AtomicReference<>();
        new DeletionEngine(
                ignored -> new TrashResult(TrashResult.Status.FAILED, "non usato"), CLOCK)
                .start(new DeletionCommand(manifest, DeletionMode.PERMANENT, confirmed),
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
