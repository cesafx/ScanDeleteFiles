package it.scandeletefiles.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeletionCancellationTest {

    private static final Instant MODIFIED = Instant.parse("2026-11-09T23:00:00Z");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void annullamentoDuranteUnaRigaCompletaQuellaRigaENonAvviaLeSuccessive(@TempDir Path root)
            throws Exception {
        Path trashDir = Files.createDirectory(root.resolve("cestino"));
        Path first = file(root, "primo.txt");
        Path second = file(root, "secondo.txt");
        Path third = file(root, "terzo.txt");
        Path manifest = root.resolve("annullamento.csv");
        Files.writeString(manifest, csv(root, first, second, third), StandardCharsets.UTF_8);

        BlockingTrash trash = new BlockingTrash(trashDir);
        DeletionEngine engine = new DeletionEngine(trash, CLOCK);
        AtomicReference<OperationResult> result = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        CancellableOperation operation = engine.start(
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

        assertTrue(trash.firstRowStarted.await(10, TimeUnit.SECONDS));
        operation.requestCancellation();
        trash.allowFirstRowToFinish.countDown();
        assertTrue(finished.await(10, TimeUnit.SECONDS));

        assertEquals(OperationStatus.CANCELLED, result.get().status());
        assertEquals(1, trash.calls.get(), "dopo la richiesta non devono iniziare nuove azioni");
        assertFalse(Files.exists(first), "la riga già in corso conserva il proprio esito");
        assertTrue(Files.exists(second));
        assertTrue(Files.exists(third));

        List<String> report = Files.readString(root.resolve("annullamento.report.csv"), StandardCharsets.UTF_8)
                .substring(1).lines().toList();
        assertEquals("Cestinato", report.get(1).split(";", -1)[6]);
        assertEquals("Non elaborato", report.get(2).split(";", -1)[6]);
        assertEquals("Non elaborato", report.get(3).split(";", -1)[6]);

        String audit = Files.readString(root.resolve("annullamento.csv.log"), StandardCharsets.UTF_8);
        assertTrue(audit.contains("Stato: ANNULLATA"));
        assertTrue(audit.contains("Righe elaborate: 1"));
        assertTrue(audit.contains("File cestinati: 1"));
        assertTrue(result.get().message().contains("1 / 3 righe elaborate"));
    }

    private static final class BlockingTrash implements TrashAdapter {
        private final Path trashDir;
        private final CountDownLatch firstRowStarted = new CountDownLatch(1);
        private final CountDownLatch allowFirstRowToFinish = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();

        private BlockingTrash(Path trashDir) {
            this.trashDir = trashDir;
        }

        @Override
        public TrashResult moveToTrash(Path file) {
            calls.incrementAndGet();
            firstRowStarted.countDown();
            try {
                if (!allowFirstRowToFinish.await(10, TimeUnit.SECONDS)) {
                    return new TrashResult(TrashResult.Status.FAILED, "timeout nel test");
                }
                Files.move(file, trashDir.resolve(file.getFileName()));
                return new TrashResult(TrashResult.Status.MOVED, "cestinato");
            } catch (Exception exc) {
                return new TrashResult(TrashResult.Status.FAILED, exc.getMessage());
            }
        }
    }

    private static Path file(Path root, String name) throws Exception {
        Path file = root.resolve(name);
        Files.writeString(file, "contenuto");
        Files.setLastModifiedTime(file, FileTime.from(MODIFIED));
        return file;
    }

    private static String csv(Path root, Path... files) {
        StringBuilder csv = new StringBuilder(
                "\uFEFFNome file;Path cartella;Path completo;Formato file;Data ultima modifica;Delete\n");
        for (Path file : files) {
            csv.append(file.getFileName()).append(';')
                    .append(root).append(';')
                    .append(file).append(";TXT;2026/11/09;Yes\n");
        }
        return csv.toString();
    }
}
