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
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeTraversalTest {

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

    private static ScanResult esegui(ScanEngine engine, ScanCommand command) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<OperationResult> result = new AtomicReference<>();
        List<OperationProgress> progress = new CopyOnWriteArrayList<>();
        OperationObserver observer = new OperationObserver() {
            @Override
            public void onProgress(OperationProgress p) {
                progress.add(p);
            }

            @Override
            public void onFinished(OperationResult r) {
                result.set(r);
                finished.countDown();
            }
        };
        engine.start(command, observer);
        assertTrue(finished.await(30, TimeUnit.SECONDS), "la scansione non è terminata");
        return new ScanResult(result.get(), progress);
    }

    private static final class ScanResult {
        final OperationResult result;
        final List<OperationProgress> progress;

        ScanResult(OperationResult result, List<OperationProgress> progress) {
            this.result = result;
            this.progress = progress;
        }
    }

    private static List<String> righeManifest(Path manifest) throws Exception {
        String content = Files.readString(manifest, StandardCharsets.UTF_8);
        assertTrue(content.startsWith("\uFEFF"), "il manifest deve iniziare con il BOM UTF-8");
        return content.substring(1).lines().filter(line -> !line.isBlank()).toList();
    }

    @Test
    void symlinkADirectoryNonVieneAttraversato(@TempDir Path root) throws Exception {
        Path destinazione = Files.createTempDirectory("symlink-target");
        Path dentro = destinazione.resolve("dentro.txt");
        Files.writeString(dentro, "dentro");
        Files.setLastModifiedTime(dentro, FileTime.from(Instant.parse("2026-11-09T23:00:00Z")));

        // Il target sta fuori dall'albero scansionato: è raggiungibile solo tramite il symlink.
        Files.createSymbolicLink(root.resolve("linkdir"), destinazione);

        ScanEngine engine = new ScanEngine(platform());
        Path manifest = root.resolve("manifest.csv");
        ScanCommand command = new ScanCommand(
                root, LocalDate.of(2026, 11, 10), Set.of("txt"), false, manifest);

        ScanResult esito = esegui(engine, command);
        assertEquals(OperationStatus.COMPLETED, esito.result.status());
        assertFalse(esito.progress.isEmpty(), "il servizio deve riportare l'avanzamento");

        List<String> righe = righeManifest(manifest);
        List<String> dati = righe.subList(1, righe.size());
        assertTrue(dati.isEmpty(),
                "la directory raggiungibile solo tramite symlink non deve essere attraversata");
    }

    @Test
    void symlinkAFileNonVieneIncluso(@TempDir Path root) throws Exception {
        Path reale = root.resolve("reale.txt");
        Files.writeString(reale, "reale");
        Files.setLastModifiedTime(reale, FileTime.from(Instant.parse("2026-11-09T23:00:00Z")));

        Path collegamento = Files.createSymbolicLink(root.resolve("link.txt"), reale);

        ScanEngine engine = new ScanEngine(platform());
        Path manifest = root.resolve("manifest.csv");
        ScanCommand command = new ScanCommand(
                root, LocalDate.of(2026, 11, 10), Set.of("txt"), false, manifest);

        ScanResult esito = esegui(engine, command);
        assertEquals(OperationStatus.COMPLETED, esito.result.status());

        List<String> righe = righeManifest(manifest);
        List<String> dati = righe.subList(1, righe.size());
        assertEquals(1, dati.size());
        assertTrue(dati.get(0).startsWith("reale.txt;"),
                "il file reale deve essere incluso");
        assertFalse(dati.get(0).contains("link.txt"),
                "il symlink al file non deve essere incluso");
    }

    @Test
    void erroreDiAccessoNonFermaLaScansioneECompareNellAudit(@TempDir Path root) throws Exception {
        Path libero = root.resolve("libero.txt");
        Files.writeString(libero, "libero");
        Files.setLastModifiedTime(libero, FileTime.from(Instant.parse("2026-11-09T23:00:00Z")));

        Path bloccato = Files.createDirectory(root.resolve("bloccato"));
        Path segreto = bloccato.resolve("segreto.txt");
        Files.writeString(segreto, "segreto");
        Files.setLastModifiedTime(segreto, FileTime.from(Instant.parse("2026-11-09T23:00:00Z")));
        Files.setPosixFilePermissions(bloccato, Set.of());

        try {
            ScanEngine engine = new ScanEngine(platform());
            Path manifest = root.resolve("manifest.csv");
            ScanCommand command = new ScanCommand(
                    root, LocalDate.of(2026, 11, 10), Set.of("txt"), false, manifest);

            ScanResult esito = esegui(engine, command);
            assertEquals(OperationStatus.COMPLETED, esito.result.status());

            List<String> righe = righeManifest(manifest);
            List<String> dati = righe.subList(1, righe.size());
            assertEquals(1, dati.size());
            assertTrue(dati.get(0).startsWith("libero.txt;"),
                    "i file accessibili devono essere inclusi nonostante l'errore altrove");

            Path audit = root.resolve("manifest.csv.log");
            assertTrue(Files.exists(audit), "l'audit deve essere prodotto anche in presenza di errori di accesso");
            String log = Files.readString(audit, StandardCharsets.UTF_8);
            assertTrue(log.contains("Errori di accesso: 1"),
                    "l'audit deve censire l'errore di accesso invece di fermare la scansione");
        } finally {
            Files.setPosixFilePermissions(bloccato, Set.of(PosixFilePermission.values()));
        }
    }
}
