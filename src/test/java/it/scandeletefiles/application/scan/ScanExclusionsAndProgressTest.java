package it.scandeletefiles.application.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScanExclusionsAndProgressTest {

    private static final Instant FIXED = Instant.parse("2026-11-09T10:00:00Z");

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
                Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    @Test
    void auditContieneEsclusioniPerFormatoConPercentuali(@TempDir Path root) throws Exception {
        Path txt1 = root.resolve("a.txt");
        Path txt2 = root.resolve("b.txt");
        Path pdf = root.resolve("c.pdf");
        Path xls = root.resolve("d.xls");
        Path jpg = root.resolve("e.jpg");
        Path sub = Files.createDirectories(root.resolve("sub"));
        Path txtSub = sub.resolve("f.txt");

        Files.writeString(txt1, "a");
        Files.writeString(txt2, "b");
        Files.writeString(pdf, "c");
        Files.writeString(xls, "d");
        Files.writeString(jpg, "e");
        Files.writeString(txtSub, "f");

        Instant before = Instant.parse("2026-11-09T23:00:00Z");
        for (Path file : List.of(txt1, txt2, pdf, xls, jpg, txtSub)) {
            Files.setLastModifiedTime(file, FileTime.from(before));
        }

        ScanEngine engine = new ScanEngine(platform());
        Path manifest = root.resolve("manifest.csv");

        ScanCommand command = new ScanCommand(
                root, LocalDate.of(2026, 11, 10), Set.of("TXT"), false, false, manifest);

        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<OperationResult> result = new AtomicReference<>();
        engine.start(command, new OperationObserver() {
            @Override
            public void onProgress(OperationProgress progress) {
            }

            @Override
            public void onFinished(OperationResult r) {
                result.set(r);
                finished.countDown();
            }
        });

        assertTrue(finished.await(30, TimeUnit.SECONDS), "la scansione non è terminata");
        assertEquals(OperationStatus.COMPLETED, result.get().status());

        Path audit = root.resolve("manifest.csv.log");
        assertTrue(Files.exists(audit), "il log di audit deve essere scritto accanto al manifest");
        String log = Files.readString(audit, StandardCharsets.UTF_8);

        assertTrue(log.contains("Esclusioni per formato (file scartati perché non inclusi nei filtri):"),
                "l'audit deve contenere la sezione di esclusioni");
        assertTrue(log.contains("PDF: 1"), "l'audit deve contare le esclusioni PDF");
        assertTrue(log.contains("XLS: 1"), "l'audit deve contare le esclusioni XLS");
        assertTrue(log.contains("JPG: 1"), "l'audit deve contare le esclusioni JPG");
        assertTrue(log.contains("Totale esclusi: 3"), "l'audit deve riportare il totale delle esclusioni");
        // 6 file visitati, 3 esclusi -> 50,0%
        assertTrue(log.contains("50,0% del totale visitato"), "l'audit deve riportare la percentuale di esclusione");
    }

    @Test
    void progressVieneAggiornatoAnchePerFileNonCorrispondenti(@TempDir Path root) throws Exception {
        Path txt = root.resolve("a.txt");
        Path pdf = root.resolve("b.pdf");
        Path doc = root.resolve("c.doc");

        Files.writeString(txt, "a");
        Files.writeString(pdf, "b");
        Files.writeString(doc, "c");

        Instant before = Instant.parse("2026-11-09T23:00:00Z");
        for (Path file : List.of(txt, pdf, doc)) {
            Files.setLastModifiedTime(file, FileTime.from(before));
        }

        ScanEngine engine = new ScanEngine(platform());
        Path manifest = root.resolve("manifest.csv");

        ScanCommand command = new ScanCommand(
                root, LocalDate.of(2026, 11, 10), Set.of("TXT"), false, false, manifest);

        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<OperationResult> result = new AtomicReference<>();
        List<OperationProgress> progress = new CopyOnWriteArrayList<>();
        engine.start(command, new OperationObserver() {
            @Override
            public void onProgress(OperationProgress p) {
                progress.add(p);
            }

            @Override
            public void onFinished(OperationResult r) {
                result.set(r);
                finished.countDown();
            }
        });

        assertTrue(finished.await(30, TimeUnit.SECONDS), "la scansione non è terminata");
        assertEquals(OperationStatus.COMPLETED, result.get().status());

        // Il progress deve includere aggiornamenti anche per i file non corrispondenti (pdf, doc)
        boolean sawNonMatchingProgress = progress.stream()
                .anyMatch(p -> p.message().contains("esclusi") && p.message().contains("2"));
        assertTrue(sawNonMatchingProgress,
                "il progress deve segnalare i file esclusi anche quando non corrispondono ai filtri");

        // Gli stati intermedi di scrittura devono essere presenti
        boolean sawManifestWriting = progress.stream()
                .anyMatch(p -> p.message().contains("Scrittura file CSV in corso"));
        boolean sawAuditWriting = progress.stream()
                .anyMatch(p -> p.message().contains("Scrittura log di audit in corso"));
        assertTrue(sawManifestWriting, "l'interfaccia deve ricevere lo stato di scrittura del file CSV");
        assertTrue(sawAuditWriting, "l'interfaccia deve ricevere lo stato di scrittura del log di audit");
    }

    @Test
    void scanSenzaEsclusioniRiportaNessunaEsclusione(@TempDir Path root) throws Exception {
        Path txt1 = root.resolve("a.txt");
        Path txt2 = root.resolve("b.txt");

        Files.writeString(txt1, "a");
        Files.writeString(txt2, "b");

        Instant before = Instant.parse("2026-11-09T23:00:00Z");
        for (Path file : List.of(txt1, txt2)) {
            Files.setLastModifiedTime(file, FileTime.from(before));
        }

        ScanEngine engine = new ScanEngine(platform());
        Path manifest = root.resolve("manifest.csv");

        ScanCommand command = new ScanCommand(
                root, LocalDate.of(2026, 11, 10), Set.of("TXT"), false, false, manifest);

        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<OperationResult> result = new AtomicReference<>();
        engine.start(command, new OperationObserver() {
            @Override
            public void onProgress(OperationProgress progress) {
            }

            @Override
            public void onFinished(OperationResult r) {
                result.set(r);
                finished.countDown();
            }
        });

        assertTrue(finished.await(30, TimeUnit.SECONDS), "la scansione non è terminata");
        assertEquals(OperationStatus.COMPLETED, result.get().status());

        Path audit = root.resolve("manifest.csv.log");
        String log = Files.readString(audit, StandardCharsets.UTF_8);
        assertTrue(log.contains("Esclusioni per formato: nessuna"),
                "quando non ci sono esclusioni l'audit deve riportarlo");
    }

    @Test
    void scanAllFilesIgnoraFiltroFormati(@TempDir Path root) throws Exception {
        Path txt = root.resolve("a.txt");
        Path pdf = root.resolve("b.pdf");
        Path jpg = root.resolve("c.jpg");
        Files.writeString(txt, "a");
        Files.writeString(pdf, "b");
        Files.writeString(jpg, "c");

        Instant before = Instant.parse("2026-11-09T23:00:00Z");
        for (Path file : List.of(txt, pdf, jpg)) {
            Files.setLastModifiedTime(file, FileTime.from(before));
        }

        ScanEngine engine = new ScanEngine(platform());
        Path manifest = root.resolve("manifest.csv");

        ScanCommand command = new ScanCommand(
                root, LocalDate.of(2026, 11, 10), Set.of("TXT"), false, true, manifest);

        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<OperationResult> result = new AtomicReference<>();
        engine.start(command, new OperationObserver() {
            @Override
            public void onProgress(OperationProgress progress) {
            }

            @Override
            public void onFinished(OperationResult r) {
                result.set(r);
                finished.countDown();
            }
        });

        assertTrue(finished.await(30, TimeUnit.SECONDS), "la scansione non è terminata");
        assertEquals(OperationStatus.COMPLETED, result.get().status());

        String contenuto = Files.readString(manifest, StandardCharsets.UTF_8);
        List<String> righe = contenuto.substring(1).lines()
                .filter(line -> !line.isBlank()).toList();
        // Header + 3 file: tutti inclusi nonostante il filtro sia solo TXT
        assertEquals(4, righe.size(), "con scanAllFiles tutti i file devono essere inclusi");
        assertTrue(righe.stream().anyMatch(r -> r.startsWith("a.txt;")));
        assertTrue(righe.stream().anyMatch(r -> r.startsWith("b.pdf;")));
        assertTrue(righe.stream().anyMatch(r -> r.startsWith("c.jpg;")));

        Path audit = root.resolve("manifest.csv.log");
        String log = Files.readString(audit, StandardCharsets.UTF_8);
        assertTrue(log.contains("Esclusioni per formato: nessuna"),
                "con scanAllFiles non deve esserci alcuna esclusione per formato");
    }
}
