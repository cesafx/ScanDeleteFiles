package it.scandeletefiles.application.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.scandeletefiles.application.CancellableOperation;
import it.scandeletefiles.application.OperationObserver;
import it.scandeletefiles.application.OperationProgress;
import it.scandeletefiles.application.OperationResult;
import it.scandeletefiles.application.OperationStatus;
import it.scandeletefiles.application.ScanCommand;
import it.scandeletefiles.bootstrap.PlatformAdapters;
import it.scandeletefiles.platform.SettingsAdapter;
import it.scandeletefiles.platform.TrashResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScanAuditLogTest {

    private static PlatformAdapters platform() {
        return new PlatformAdapters(
                FileSystems.getDefault(),
                file -> new TrashResult(TrashResult.Status.FAILED, "non usato nella scansione"),
                new SettingsAdapter() {
                    @Override
                    public Set<String> loadSelectedExtensions() {
                        return Set.of();
                    }

                    @Override
                    public void saveSelectedExtensions(Set<String> extensions) {
                    }
                },
                Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void registraStatoInizioFineDurataVisitedConformiEPercantage(@TempDir Path root) throws Exception {
        Path a = root.resolve("alfa.txt");
        Path b = root.resolve("beta.txt");
        Path gamma = root.resolve("gamma.txt");
        Path documento = root.resolve("documento.log");

        Files.writeString(a, "a");
        Files.writeString(b, "b");
        Files.writeString(gamma, "g");
        Files.writeString(documento, "d");

        Instant before = Instant.parse("2026-11-09T23:00:00Z");
        Instant after = Instant.parse("2026-11-10T00:30:00Z");
        Files.setLastModifiedTime(a, FileTime.from(before));
        Files.setLastModifiedTime(b, FileTime.from(before));
        Files.setLastModifiedTime(gamma, FileTime.from(after));
        Files.setLastModifiedTime(documento, FileTime.from(before));

        ScanEngine engine = new ScanEngine(platform());
        Path manifest = root.resolve("esito.csv");

        ScanCommand command = new ScanCommand(
                root, LocalDate.of(2026, 11, 10), Set.of("TXT"), false, manifest);

        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<OperationResult> result = new AtomicReference<>();
        OperationObserver observer = new OperationObserver() {
            @Override
            public void onProgress(OperationProgress p) {
            }

            @Override
            public void onFinished(OperationResult r) {
                result.set(r);
                finished.countDown();
            }
        };

        engine.start(command, observer);
        assertTrue(finished.await(30, TimeUnit.SECONDS), "la scansione non è terminata");
        assertEquals(OperationStatus.COMPLETED, result.get().status());

        Path audit = root.resolve("esito.csv.log");
        assertTrue(Files.exists(audit), "il log di audit deve essere scritto accanto al manifest");

        String log = Files.readString(audit, StandardCharsets.UTF_8);
        assertTrue(log.contains("Stato: COMPLETATA"), "l'audit deve dichiarare lo stato finale");
        assertTrue(log.contains("Inizio:"), "l'audit deve riportare l'inizio");
        assertTrue(log.contains("Fine:"), "l'audit deve riportare la fine");
        assertTrue(log.contains("Durata:"), "l'audit deve riportare la durata");
        assertTrue(log.contains("Elementi visitati: 4"), "l'audit deve contare gli elementi visitati");
        assertTrue(log.contains("File corrispondenti: 2"), "l'audit deve contare i file conformi");
        assertTrue(log.contains("Percentuali per formato:"), "l'audit deve riportare le percentuali per formato");
        assertTrue(log.contains("TXT: 100,0%"), "l'audit deve riportare la percentuale del formato TXT");
    }

    @Test
    void annullataNonPubblicaManifestMaScriveAudit(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("alberone"));
        for (int i = 0; i < 5000; i++) {
            Files.writeString(root.resolve("alberone").resolve("file" + i + ".txt"), "x");
        }

        ScanEngine engine = new ScanEngine(platform());
        Path manifest = root.resolve("da-annullare.csv");

        ScanCommand command = new ScanCommand(
                root, LocalDate.of(2026, 11, 10), Set.of("TXT"), false, manifest);

        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<OperationResult> result = new AtomicReference<>();
        AtomicReference<CancellableOperation> operation = new AtomicReference<>();
        OperationObserver observer = new OperationObserver() {
            @Override
            public void onProgress(OperationProgress p) {
            }

            @Override
            public void onFinished(OperationResult r) {
                result.set(r);
                finished.countDown();
            }
        };

        CancellableOperation op = engine.start(command, observer);
        operation.set(op);
        op.requestCancellation();

        assertTrue(finished.await(30, TimeUnit.SECONDS), "la scansione non è terminata");
        assertEquals(OperationStatus.CANCELLED, result.get().status());
        assertTrue(operation.get().isCancellationRequested());
        assertFalse(Files.exists(manifest), "una scansione annullata non deve pubblicare un manifest parziale");

        Path audit = root.resolve("da-annullare.csv.log");
        assertTrue(Files.exists(audit), "il log di audit deve essere prodotto anche per la scansione annullata");
        assertTrue(Files.readString(audit, StandardCharsets.UTF_8).contains("Stato: ANNULLATA"),
                "l'audit deve dichiarare lo stato annullato");
    }
}
