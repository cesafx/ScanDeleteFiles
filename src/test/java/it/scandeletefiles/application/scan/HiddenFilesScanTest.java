package it.scandeletefiles.application.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.scandeletefiles.application.OperationObserver;
import it.scandeletefiles.application.OperationProgress;
import it.scandeletefiles.application.OperationResult;
import it.scandeletefiles.application.OperationStatus;
import it.scandeletefiles.application.ScanCommand;
import it.scandeletefiles.bootstrap.PlatformAdapters;
import it.scandeletefiles.platform.SettingsAdapter;
import it.scandeletefiles.platform.TrashResult;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileTime;
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

class HiddenFilesScanTest {

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

    private static void scrivi(Path file, String contenuto, Instant quando) throws Exception {
        Files.writeString(file, contenuto);
        Files.setLastModifiedTime(file, FileTime.from(quando));
    }

    private static void rendiNascostoSuWindows(Path path) throws Exception {
        if (File.separatorChar == '\\') {
            Files.getFileAttributeView(path, DosFileAttributeView.class).setHidden(true);
        }
    }

    private static List<String> nomiCorrispondenti(Path manifest) throws Exception {
        String content = Files.readString(manifest, StandardCharsets.UTF_8);
        assertTrue(content.startsWith("\uFEFF"), "il manifest deve iniziare con il BOM UTF-8");
        List<String> righe = content.substring(1).lines().filter(line -> !line.isBlank()).toList();
        return righe.subList(1, righe.size()).stream()
                .map(riga -> riga.split(";", -1)[0])
                .toList();
    }

    @Test
    void escludeFileNascostiDiDefault(@TempDir Path root) throws Exception {
        Instant prima = Instant.parse("2026-11-09T23:00:00Z");
        scrivi(root.resolve("visibile.txt"), "v", prima);
        Path nascosto = root.resolve(".nascosto.txt");
        scrivi(nascosto, "n", prima);
        rendiNascostoSuWindows(nascosto);

        ScanEngine engine = new ScanEngine(platform());
        Path manifest = root.resolve("manifest.csv");
        ScanCommand command = new ScanCommand(
                root, LocalDate.of(2026, 11, 10), Set.of("txt"), false, false, manifest);

        ScanResult esito = esegui(engine, command);
        assertEquals(OperationStatus.COMPLETED, esito.result.status());

        assertEquals(List.of("visibile.txt"), nomiCorrispondenti(manifest),
                "il file nascosto deve essere escluso quando il flag non è attivo");
    }

    @Test
    void includeFileNascostiQuandoIlFlagEAttivo(@TempDir Path root) throws Exception {
        Instant prima = Instant.parse("2026-11-09T23:00:00Z");
        scrivi(root.resolve("visibile.txt"), "v", prima);
        Path nascosto = root.resolve(".nascosto.txt");
        scrivi(nascosto, "n", prima);
        rendiNascostoSuWindows(nascosto);

        ScanEngine engine = new ScanEngine(platform());
        Path manifest = root.resolve("manifest.csv");
        ScanCommand command = new ScanCommand(
                root, LocalDate.of(2026, 11, 10), Set.of("txt"), true, false, manifest);

        ScanResult esito = esegui(engine, command);
        assertEquals(OperationStatus.COMPLETED, esito.result.status());

        assertEquals(List.of(".nascosto.txt", "visibile.txt"), nomiCorrispondenti(manifest),
                "il file nascosto deve essere incluso quando il flag è attivo");
    }

    @Test
    void escludeContenutoDiDirectoryNascosteDiDefault(@TempDir Path root) throws Exception {
        Instant prima = Instant.parse("2026-11-09T23:00:00Z");
        Path nascosta = Files.createDirectories(root.resolve(".nascosta"));
        rendiNascostoSuWindows(nascosta);
        scrivi(nascosta.resolve("dentro.txt"), "d", prima);
        scrivi(root.resolve("visibile.txt"), "v", prima);

        ScanEngine engine = new ScanEngine(platform());
        Path manifest = root.resolve("manifest.csv");
        ScanCommand command = new ScanCommand(
                root, LocalDate.of(2026, 11, 10), Set.of("txt"), false, false, manifest);

        ScanResult esito = esegui(engine, command);
        assertEquals(OperationStatus.COMPLETED, esito.result.status());

        assertEquals(List.of("visibile.txt"), nomiCorrispondenti(manifest),
                "il contenuto delle directory nascoste deve essere escluso di default");
    }

    @Test
    void includeContenutoDiDirectoryNascosteQuandoIlFlagEAttivo(@TempDir Path root) throws Exception {
        Instant prima = Instant.parse("2026-11-09T23:00:00Z");
        Path nascosta = Files.createDirectories(root.resolve(".nascosta"));
        rendiNascostoSuWindows(nascosta);
        scrivi(nascosta.resolve("dentro.txt"), "d", prima);
        scrivi(root.resolve("visibile.txt"), "v", prima);

        ScanEngine engine = new ScanEngine(platform());
        Path manifest = root.resolve("manifest.csv");
        ScanCommand command = new ScanCommand(
                root, LocalDate.of(2026, 11, 10), Set.of("txt"), true, false, manifest);

        ScanResult esito = esegui(engine, command);
        assertEquals(OperationStatus.COMPLETED, esito.result.status());

        assertEquals(List.of("dentro.txt", "visibile.txt"), nomiCorrispondenti(manifest),
                "il contenuto delle directory nascoste deve essere incluso quando il flag è attivo");
    }
}
