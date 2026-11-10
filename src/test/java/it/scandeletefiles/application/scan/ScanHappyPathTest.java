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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScanHappyPathTest {

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
    void includiSoloTxtConMtimeStrettamentePrimaDellaSoglia(@TempDir Path root) throws Exception {
        Path a = root.resolve("a.txt");
        Path f = root.resolve("f.TXT");
        Path b = root.resolve("b.txt");
        Path c = root.resolve("c.txt");
        Path d = root.resolve("d.log");
        Path sub = Files.createDirectories(root.resolve("sub"));
        Path e = sub.resolve("e.txt");

        Files.writeString(a, "contenuto a");
        Files.writeString(f, "contenuto f");
        Files.writeString(b, "contenuto b");
        Files.writeString(c, "contenuto c");
        Files.writeString(d, "contenuto d");
        Files.writeString(e, "contenuto e");

        Instant before = Instant.parse("2026-11-09T23:00:00Z");
        Instant atThreshold = Instant.parse("2026-11-10T00:00:00Z");
        Instant after = Instant.parse("2026-11-10T00:30:00Z");
        Files.setLastModifiedTime(a, FileTime.from(before));
        Files.setLastModifiedTime(f, FileTime.from(before));
        Files.setLastModifiedTime(b, FileTime.from(atThreshold));
        Files.setLastModifiedTime(c, FileTime.from(after));
        Files.setLastModifiedTime(d, FileTime.from(before));
        Files.setLastModifiedTime(e, FileTime.from(before));

        ScanEngine engine = new ScanEngine(platform());
        Path manifest = root.resolve("manifest.csv");

        ScanCommand command = new ScanCommand(
                root, LocalDate.of(2026, 11, 10), Set.of("txt"), false, false, manifest);

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

        CancellableOperation operation = engine.start(command, observer);
        assertTrue(finished.await(30, TimeUnit.SECONDS), "la scansione non è terminata");

        assertEquals(OperationStatus.COMPLETED, result.get().status());
        assertFalse(progress.isEmpty(), "il servizio deve riportare l'avanzamento");
        assertFalse(operation.isCancellationRequested());
        assertTrue(Files.exists(manifest), "il manifest deve essere pubblicato");

        String content = Files.readString(manifest, StandardCharsets.UTF_8);
        assertTrue(content.startsWith("\uFEFF"), "il manifest deve iniziare con il BOM UTF-8");
        List<String> lines = content.substring(1).lines().toList();
        assertEquals("Nome file;Path cartella;Path completo;Formato file;Data ultima modifica;Delete",
                lines.get(0));

        Map<String, String> rows = lines.subList(1, lines.size()).stream()
                .filter(line -> !line.isBlank())
                .collect(Collectors.toMap(line -> line.split(";", -1)[0], line -> line));

        assertEquals(Set.of("a.txt", "e.txt", "f.TXT"), rows.keySet());

        rows.values().forEach(line -> {
            String[] parts = line.split(";", -1);
            assertEquals(6, parts.length, "ogni riga deve avere esattamente le sei colonne contrattuali");
            assertEquals("Yes", parts[5], "la colonna Delete deve essere inizializzata a Yes");
        });

        String[] rowA = rows.get("a.txt").split(";", -1);
        assertEquals(root.toString(), rowA[1]);
        assertEquals(root.resolve("a.txt").toString(), rowA[2]);
        assertEquals("TXT", rowA[3]);
        assertEquals("2026/11/09", rowA[4]);

        String[] rowE = rows.get("e.txt").split(";", -1);
        assertEquals(root.resolve("sub").toString(), rowE[1]);
        assertEquals("2026/11/09", rowE[4]);

        assertFalse(rows.containsKey("b.txt"), "il file esattamente alla soglia delle 00:00 deve essere escluso");
        assertFalse(rows.containsKey("c.txt"), "il file modificato dopo la soglia deve essere escluso");
        assertFalse(rows.containsKey("d.log"), "il file con estensione non selezionata deve essere escluso");
    }
}
