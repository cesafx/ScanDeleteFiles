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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeletionLifecycleTest {

    private static final Instant MODIFIED = Instant.parse("2026-11-09T23:00:00Z");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void progressoMostraRigheEdEsitiMentreLaUiRestaDelegataAlThreadDiBackground(@TempDir Path root)
            throws Exception {
        Path trashDir = Files.createDirectory(root.resolve("cestino"));
        Path cestinato = file(root, "cestinato.txt");
        Path saltato = file(root, "saltato.txt");
        Path erroreCestino = file(root, "errore.txt");
        Path deleteNonValido = file(root, "invalido.txt");
        Path manifest = root.resolve("lifecycle.csv");
        Files.writeString(manifest, csv(root,
                row(root, cestinato, "Yes"),
                row(root, saltato, "No"),
                row(root, erroreCestino, "Yes"),
                row(root, deleteNonValido, "Forse")), StandardCharsets.UTF_8);

        TrashAdapter trash = path -> {
            if (path.equals(erroreCestino)) {
                return new TrashResult(TrashResult.Status.FAILED, "errore controllato");
            }
            try {
                Files.move(path, trashDir.resolve(path.getFileName()));
                return new TrashResult(TrashResult.Status.MOVED, "cestinato");
            } catch (Exception exc) {
                return new TrashResult(TrashResult.Status.FAILED, exc.getMessage());
            }
        };
        DeletionEngine engine = new DeletionEngine(trash, CLOCK);
        List<OperationProgress> progress = new CopyOnWriteArrayList<>();
        AtomicReference<OperationResult> result = new AtomicReference<>();
        AtomicReference<String> callbackThread = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);

        String callerThread = Thread.currentThread().getName();
        engine.start(new DeletionCommand(manifest, DeletionMode.TRASH, false), new OperationObserver() {
            @Override
            public void onProgress(OperationProgress update) {
                progress.add(update);
                callbackThread.set(Thread.currentThread().getName());
            }

            @Override
            public void onFinished(OperationResult operationResult) {
                result.set(operationResult);
                finished.countDown();
            }
        });

        assertTrue(finished.await(10, TimeUnit.SECONDS));
        OperationProgress last = progress.getLast();
        assertEquals(4, last.completed());
        assertEquals(4, last.total().orElseThrow());
        assertEquals(
                "Righe elaborate: 4 / 4; cestinati: 1; eliminati definitivamente: 0; spostati: 0; saltati: 1; errori: 2",
                last.message());
        assertEquals(OperationStatus.COMPLETED, result.get().status());
        assertTrue(result.get().message().contains("1 cestinati"));
        assertTrue(result.get().message().contains("1 saltati"));
        assertTrue(result.get().message().contains("2 errori"));
        assertFalse(callerThread.equals(callbackThread.get()), "il lavoro non deve bloccare il thread chiamante");
    }

    private static Path file(Path root, String name) throws Exception {
        Path file = root.resolve(name);
        Files.writeString(file, "contenuto");
        Files.setLastModifiedTime(file, FileTime.from(MODIFIED));
        return file;
    }

    private static String csv(Path root, String... rows) {
        return "\uFEFFNome file;Path cartella;Path completo;Formato file;Data ultima modifica;Delete\n"
                + String.join("\n", rows) + "\n";
    }

    private static String row(Path root, Path file, String delete) {
        return file.getFileName() + ";" + root + ";" + file + ";TXT;2026/11/09;" + delete;
    }
}
