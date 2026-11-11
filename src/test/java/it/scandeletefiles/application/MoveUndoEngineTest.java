package it.scandeletefiles.application;

import it.scandeletefiles.application.move.MoveUndoCommand;
import it.scandeletefiles.application.move.MoveUndoEngine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MoveUndoEngineTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC);
    private static final FileTime FILE_TIME = FileTime.from(Instant.parse("2025-01-02T03:04:05Z"));

    @Test
    void ripristinaGerarchiaFileETimestampERimuoveSoloCartelleCreate(@TempDir Path root) throws Exception {
        Path source = Files.createDirectory(root.resolve("source"));
        Path nested = Files.createDirectories(source.resolve("clienti/rossi"));
        Path destination = Files.createDirectory(root.resolve("destination"));
        Path existingDestinationDirectory = Files.createDirectory(destination.resolve("clienti"));
        Path file = nested.resolve("documento.txt");
        Files.writeString(file, "contenuto originale");
        Files.setLastModifiedTime(file, FILE_TIME);
        FileTime sourceTime = FileTime.from(Instant.parse("2024-01-02T03:04:05Z"));
        FileTime nestedTime = FileTime.from(Instant.parse("2024-03-04T05:06:07Z"));
        FileTime destinationTime = FileTime.from(Instant.parse("2023-01-02T03:04:05Z"));
        FileTime existingDestinationTime = FileTime.from(Instant.parse("2023-04-05T06:07:08Z"));
        Files.setLastModifiedTime(source, sourceTime);
        Files.setLastModifiedTime(nested, nestedTime);
        Files.setLastModifiedTime(destination, destinationTime);
        Files.setLastModifiedTime(existingDestinationDirectory, existingDestinationTime);

        Path journal = move(root, source, destination, file);
        Path moved = destination.resolve("clienti/rossi/documento.txt");
        assertTrue(Files.exists(moved));

        OperationResult result = undo(journal);

        assertEquals(OperationStatus.COMPLETED, result.status());
        assertTrue(Files.exists(file));
        assertEquals("contenuto originale", Files.readString(file));
        assertEquals(FILE_TIME, Files.getLastModifiedTime(file));
        assertFalse(Files.exists(destination.resolve("clienti/rossi")));
        assertTrue(Files.isDirectory(existingDestinationDirectory));
        assertEquals(sourceTime, Files.getLastModifiedTime(source));
        assertEquals(nestedTime, Files.getLastModifiedTime(nested));
        assertEquals(destinationTime, Files.getLastModifiedTime(destination));
        assertEquals(existingDestinationTime, Files.getLastModifiedTime(existingDestinationDirectory));
        assertTrue(Files.exists(root.resolve("undoing_manifest.csv")));
        assertTrue(Files.readString(root.resolve("undoing_manifest.log")).contains("Stato: COMPLETATA"));
    }

    @Test
    void ripristinaDataCartellaAncheSeJournalEOutputSonoNellaSorgente(@TempDir Path root) throws Exception {
        Path source = Files.createDirectory(root.resolve("source"));
        Path destination = Files.createDirectory(root.resolve("destination"));
        Path file = source.resolve("documento.txt");
        Files.writeString(file, "originale");
        Files.setLastModifiedTime(file, FILE_TIME);
        Path manifest = source.resolve("manifest.csv");
        Files.writeString(manifest, "\uFEFFNome file;Path cartella;Path completo;Formato file;Data ultima modifica;Delete\n"
                + "documento.txt;" + source + ";" + file + ";TXT;2025/01/02;Yes\n",
                StandardCharsets.UTF_8);
        FileTime sourceTime = FileTime.from(Instant.parse("2024-06-07T08:09:10Z"));
        Files.setLastModifiedTime(source, sourceTime);

        DeletionEngine moveEngine = new DeletionEngine(path -> null, CLOCK);
        OperationResult moveResult = await(observer -> moveEngine.start(
                new DeletionCommand(manifest, DeletionMode.MOVE_TO_FOLDER, false, destination, source), observer));
        assertEquals(OperationStatus.COMPLETED, moveResult.status());
        Path journal = source.resolve("moving_manifest.undo.csv");

        assertEquals(OperationStatus.COMPLETED, undo(journal).status());
        assertTrue(Files.exists(file));
        assertEquals(sourceTime, Files.getLastModifiedTime(source));
        assertTrue(Files.exists(source.resolve("undoing_manifest.csv")));
        assertTrue(Files.exists(source.resolve("undoing_manifest.log")));
    }

    @Test
    void rifiutaRootDestinazioneCheEUnLinkSimbolico(@TempDir Path root) throws Exception {
        Path source = Files.createDirectory(root.resolve("source"));
        Path realDestination = Files.createDirectory(root.resolve("real-destination"));
        Path linkedDestination = root.resolve("linked-destination");
        try {
            Files.createSymbolicLink(linkedDestination, realDestination);
        } catch (UnsupportedOperationException | java.io.IOException exc) {
            assumeTrue(false, "Il filesystem non consente link simbolici");
        }
        Path file = source.resolve("documento.txt");
        Files.writeString(file, "originale");
        Files.setLastModifiedTime(file, FILE_TIME);
        Path manifest = root.resolve("manifest.csv");
        Files.writeString(manifest, "\uFEFFNome file;Path cartella;Path completo;Formato file;Data ultima modifica;Delete\n"
                + "documento.txt;" + source + ";" + file + ";TXT;2025/01/02;Yes\n",
                StandardCharsets.UTF_8);

        DeletionEngine engine = new DeletionEngine(path -> null, CLOCK);
        OperationResult result = await(observer -> engine.start(
                new DeletionCommand(manifest, DeletionMode.MOVE_TO_FOLDER, false,
                        linkedDestination, source), observer));

        assertEquals(OperationStatus.FAILED, result.status());
        assertTrue(Files.exists(file));
        assertFalse(Files.exists(realDestination.resolve("documento.txt")));
        assertTrue(Files.exists(root.resolve("moving_manifest.log")));
    }

    @Test
    void nonSovrascriveUnFileRicreatoNelPercorsoOriginale(@TempDir Path root) throws Exception {
        Path source = Files.createDirectory(root.resolve("source"));
        Path destination = Files.createDirectory(root.resolve("destination"));
        Path file = source.resolve("documento.txt");
        Files.writeString(file, "originale");
        Files.setLastModifiedTime(file, FILE_TIME);
        Path journal = move(root, source, destination, file);
        Files.writeString(file, "nuovo file");

        OperationResult result = undo(journal);

        assertEquals(OperationStatus.FAILED, result.status());
        assertEquals("nuovo file", Files.readString(file));
        assertTrue(Files.exists(destination.resolve("documento.txt")));
    }

    @Test
    void journalRegistraERipristinaIlTargetEffettivoConSuffisso(@TempDir Path root) throws Exception {
        Path source = Files.createDirectory(root.resolve("source"));
        Path destination = Files.createDirectory(root.resolve("destination"));
        Path existing = destination.resolve("documento.txt");
        Files.writeString(existing, "già presente");
        Path file = source.resolve("documento.txt");
        Files.writeString(file, "originale");
        Files.setLastModifiedTime(file, FILE_TIME);

        Path journal = move(root, source, destination, file);
        Path actualTarget = destination.resolve("documento_2.txt");
        assertTrue(Files.exists(actualTarget));
        assertTrue(Files.readString(journal).contains(actualTarget.toString()));

        assertEquals(OperationStatus.COMPLETED, undo(journal).status());
        assertEquals("originale", Files.readString(file));
        assertEquals("già presente", Files.readString(existing));
        assertFalse(Files.exists(actualTarget));
    }

    @Test
    void rifiutaTargetModificatoDopoLoSpostamento(@TempDir Path root) throws Exception {
        Path source = Files.createDirectory(root.resolve("source"));
        Path destination = Files.createDirectory(root.resolve("destination"));
        Path file = source.resolve("documento.txt");
        Files.writeString(file, "originale");
        Files.setLastModifiedTime(file, FILE_TIME);
        Path journal = move(root, source, destination, file);
        Path moved = destination.resolve("documento.txt");
        Files.writeString(moved, "alterato");
        Files.setLastModifiedTime(moved, FILE_TIME);

        OperationResult result = undo(journal);

        assertEquals(OperationStatus.FAILED, result.status());
        assertFalse(Files.exists(file));
        assertEquals("alterato", Files.readString(moved));
    }

    @Test
    void riservaJournalInsiemeAReportEAuditEUsaLoStessoSuffisso(@TempDir Path root) throws Exception {
        Path source = Files.createDirectory(root.resolve("source"));
        Path destination = Files.createDirectory(root.resolve("destination"));
        Path file = source.resolve("documento.txt");
        Files.writeString(file, "originale");
        Files.setLastModifiedTime(file, FILE_TIME);
        Files.writeString(root.resolve("moving_manifest.csv"), "report precedente");
        Files.writeString(root.resolve("moving_manifest.log"), "audit precedente");
        Files.writeString(root.resolve("moving_manifest.undo.csv"), "journal precedente");

        executeMove(root, source, destination, file);

        assertTrue(Files.exists(root.resolve("moving_manifest_2.csv")));
        assertTrue(Files.exists(root.resolve("moving_manifest_2.log")));
        Path journal = root.resolve("moving_manifest_2.undo.csv");
        assertTrue(Files.exists(journal));
        String content = Files.readString(journal, StandardCharsets.UTF_8);
        assertTrue(content.startsWith("\uFEFFVersion;RecordType;State"));
        assertTrue(content.contains(";FILE;MOVED;"));
        assertTrue(content.contains(destination.resolve("documento.txt").toString()));
    }

    @Test
    void annullamentoSalvaIlCheckpointERiprendeSenzaRipetereFile(@TempDir Path root) throws Exception {
        Path source = Files.createDirectory(root.resolve("source"));
        Path destination = Files.createDirectory(root.resolve("destination"));
        Path first = source.resolve("primo.txt");
        Path second = source.resolve("secondo.txt");
        Files.writeString(first, "primo");
        Files.writeString(second, "secondo");
        Files.setLastModifiedTime(first, FILE_TIME);
        Files.setLastModifiedTime(second, FILE_TIME);
        Path manifest = root.resolve("manifest.csv");
        Files.writeString(manifest, "\uFEFFNome file;Path cartella;Path completo;Formato file;Data ultima modifica;Delete\n"
                + "primo.txt;" + source + ";" + first + ";TXT;2025/01/02;Yes\n"
                + "secondo.txt;" + source + ";" + second + ";TXT;2025/01/02;Yes\n");
        DeletionEngine moveEngine = new DeletionEngine(path -> null, CLOCK);
        assertEquals(OperationStatus.COMPLETED, await(observer -> moveEngine.start(
                new DeletionCommand(manifest, DeletionMode.MOVE_TO_FOLDER, false, destination, source), observer)).status());

        Path journal = root.resolve("moving_manifest.undo.csv");
        MoveUndoEngine undoEngine = new MoveUndoEngine(CLOCK);
        CountDownLatch operationReady = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<CancellableOperation> operation = new AtomicReference<>();
        AtomicReference<OperationResult> cancelledResult = new AtomicReference<>();
        CancellableOperation handle = undoEngine.start(new MoveUndoCommand(journal), new OperationObserver() {
            @Override public void onProgress(OperationProgress progress) {
                if (progress.completed() == 1) {
                    try {
                        assertTrue(operationReady.await(10, TimeUnit.SECONDS));
                        operation.get().requestCancellation();
                    } catch (InterruptedException exc) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            @Override public void onFinished(OperationResult result) {
                cancelledResult.set(result);
                finished.countDown();
            }
        });
        operation.set(handle);
        operationReady.countDown();

        assertTrue(finished.await(30, TimeUnit.SECONDS));
        assertEquals(OperationStatus.CANCELLED, cancelledResult.get().status());
        assertEquals(1, (Files.exists(first) ? 1 : 0) + (Files.exists(second) ? 1 : 0));

        assertEquals(OperationStatus.COMPLETED, undo(journal).status());
        assertTrue(Files.exists(first));
        assertTrue(Files.exists(second));
        assertFalse(Files.exists(destination.resolve("primo.txt")));
        assertFalse(Files.exists(destination.resolve("secondo.txt")));
        assertEquals(2, Files.readString(journal).split(";FILE;RESTORED;", -1).length - 1);
    }

    private static Path move(Path root, Path source, Path destination, Path file) throws Exception {
        executeMove(root, source, destination, file);
        Path journal = root.resolve("moving_manifest.undo.csv");
        assertTrue(Files.exists(journal));
        return journal;
    }

    private static void executeMove(Path root, Path source, Path destination, Path file) throws Exception {
        Path manifest = root.resolve("manifest.csv");
        Files.writeString(manifest, "\uFEFFNome file;Path cartella;Path completo;Formato file;Data ultima modifica;Delete\n"
                + file.getFileName() + ";" + file.getParent() + ";" + file + ";TXT;2025/01/02;Yes\n",
                StandardCharsets.UTF_8);
        DeletionEngine engine = new DeletionEngine(path -> null, CLOCK);
        OperationResult result = await(observer -> engine.start(
                new DeletionCommand(manifest, DeletionMode.MOVE_TO_FOLDER, false, destination, source), observer));
        assertEquals(OperationStatus.COMPLETED, result.status());
    }

    private static OperationResult undo(Path journal) throws Exception {
        MoveUndoEngine engine = new MoveUndoEngine(CLOCK);
        return await(observer -> engine.start(new MoveUndoCommand(journal), observer));
    }

    private static OperationResult await(Starter starter) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<OperationResult> result = new AtomicReference<>();
        starter.start(new OperationObserver() {
            @Override public void onProgress(OperationProgress progress) { }
            @Override public void onFinished(OperationResult value) {
                result.set(value);
                finished.countDown();
            }
        });
        assertTrue(finished.await(30, TimeUnit.SECONDS));
        return result.get();
    }

    @FunctionalInterface
    private interface Starter {
        CancellableOperation start(OperationObserver observer);
    }
}
