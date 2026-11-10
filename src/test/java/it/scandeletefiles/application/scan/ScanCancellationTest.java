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

class ScanCancellationTest {

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
    void scansioneAnnullataScriveCsvParzialeEAudit(@TempDir Path root)
            throws Exception {
        Path alberone = Files.createDirectories(root.resolve("alberone"));
        for (int i = 0; i < 5000; i++) {
            Files.writeString(alberone.resolve("file" + i + ".txt"), "x");
        }

        ScanEngine engine = new ScanEngine(platform());
        Path manifest = root.resolve("da-annullare.csv");

        ScanCommand command = new ScanCommand(
                root, LocalDate.of(2026, 11, 10), Set.of("TXT"), false, false, manifest);

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
        assertTrue(Files.exists(manifest),
                "una scansione annullata deve pubblicare il file CSV con i dati parziali");

        Path audit = root.resolve("da-annullare.csv.log");
        assertTrue(Files.exists(audit),
                "il log di audit deve essere prodotto anche per la scansione annullata");
        assertTrue(Files.readString(audit, StandardCharsets.UTF_8).contains("Stato: ANNULLATA"),
                "l'audit deve dichiarare lo stato annullato");
    }
}
