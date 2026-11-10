package it.scandeletefiles.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.scandeletefiles.bootstrap.PlatformAdapters;
import it.scandeletefiles.application.OperationObserver;
import it.scandeletefiles.application.OperationProgress;
import it.scandeletefiles.application.OperationResult;
import it.scandeletefiles.application.OperationStatus;
import it.scandeletefiles.platform.SettingsAdapter;
import it.scandeletefiles.platform.TrashAdapter;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeletionAuditLogTest {

    private static final Instant FIXED = Instant.parse("2026-11-09T10:00:00Z");

    private static final class ControlledTrash implements TrashAdapter {
        private final Path trashDir;
        private final List<Path> moved = new CopyOnWriteArrayList<>();
        private volatile boolean unavailable;

        ControlledTrash(Path trashDir) {
            this.trashDir = trashDir;
        }

        void setUnavailable(boolean unavailable) {
            this.unavailable = unavailable;
        }

        @Override
        public TrashResult moveToTrash(Path file) {
            if (unavailable) {
                return new TrashResult(TrashResult.Status.UNAVAILABLE, "cestino non disponibile");
            }
            Path target = trashDir.resolve(file.getFileName().toString());
            int suffix = 0;
            while (Files.exists(target)) {
                target = trashDir.resolve(file.getFileName().toString() + "." + suffix++);
            }
            try {
                Files.move(file, target);
                moved.add(target);
            } catch (Exception exc) {
                return new TrashResult(TrashResult.Status.FAILED, exc.getMessage());
            }
            return new TrashResult(TrashResult.Status.MOVED, "spostato nel cestino di test");
        }
    }

    private static PlatformAdapters platform(Path trashDir, ControlledTrash trash) throws Exception {
        return new PlatformAdapters(
                FileSystems.getDefault(),
                trash,
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

    private static void writeFile(Path root, String name, Instant mtime) throws Exception {
        Files.writeString(root.resolve(name), "contenuto " + name);
        Files.setLastModifiedTime(root.resolve(name), FileTime.from(mtime));
    }

    private static String manifestCsv(String rootPath, Object... rows) {
        StringBuilder csv = new StringBuilder("\uFEFFNome file;Path cartella;Path completo;Formato file;Data ultima modifica;Delete\n");
        for (Object row : rows) {
            csv.append(row).append("\n");
        }
        return csv.toString();
    }

    private static String row(String name, String rootPath, Path file, String format, String date, String delete) {
        return name + ";" + rootPath + ";" + file + ";" + format + ";" + date + ";" + delete;
    }

    @Test
    @SuppressWarnings("unused")
    void reportContaRigheEsitoDettaglioEPercantagePerFormato(@TempDir Path root) throws Exception {
        Path trashDir = root.resolve("cestino");
        Files.createDirectories(trashDir);
        Path a = root.resolve("a.txt");
        Path b = root.resolve("b.pdf");
        Path c = root.resolve("c.TXT");
        Path d = root.resolve("d.log");
        Instant mtime = Instant.parse("2026-11-09T23:00:00Z");
        writeFile(root, "a.txt", mtime);
        writeFile(root, "b.pdf", mtime);
        writeFile(root, "c.TXT", mtime);
        writeFile(root, "d.log", mtime);

        String rootPath = root.toString();
        Path manifest = root.resolve("operazione.csv");
        Files.writeString(manifest, manifestCsv(rootPath,
                row("a.txt", rootPath, a, "TXT", "2026/11/09", "Yes"),
                row("b.pdf", rootPath, b, "PDF", "2026/11/09", "yes"),
                row("c.TXT", rootPath, c, "TXT", "2026/11/09", "YES"),
                row("d.log", rootPath, d, "LOG", "2026/11/09", "No")),
                StandardCharsets.UTF_8);

        ControlledTrash trash = new ControlledTrash(trashDir);
        PlatformAdapters platform = platform(trashDir, trash);
        DeletionEngine engine = new DeletionEngine(trash, platform.clock());

        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<OperationResult> result = new AtomicReference<>();
        engine.start(new DeletionCommand(manifest, DeletionMode.TRASH, false), new OperationObserver() {
            @Override
            public void onProgress(OperationProgress p) {
            }

            @Override
            public void onFinished(OperationResult r) {
                result.set(r);
                finished.countDown();
            }
        });
        assertTrue(finished.await(30, TimeUnit.SECONDS));
        assertEquals(OperationStatus.COMPLETED, result.get().status());

        List<Path> moved = trash.moved;
        assertEquals(3, moved.size());
        assertFalse(Files.exists(a));
        assertFalse(Files.exists(b));
        assertFalse(Files.exists(c));
        assertTrue(Files.exists(d));

        Path report = root.resolve("trash_operazione.csv");
        assertTrue(Files.exists(report));
        String content = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(content.startsWith("\uFEFF"));
        List<String> lines = content.substring(1).lines().toList();
        assertEquals("Nome file;Path cartella;Path completo;Formato file;Data ultima modifica;Delete;Esito;Dettaglio",
                lines.get(0));
        assertEquals(5, lines.size());

        Path audit = root.resolve("trash_operazione.log");
        assertTrue(Files.exists(audit));
        String log = Files.readString(audit, StandardCharsets.UTF_8);
        assertTrue(log.contains("Stato: COMPLETATA"));
        assertTrue(log.contains("Inizio:"));
        assertTrue(log.contains("Fine:"));
        assertTrue(log.contains("Durata:"));
        assertTrue(log.contains("Righe controllate: 4"));
        assertTrue(log.contains("Righe Da eliminare: 3"));
        assertTrue(log.contains("File cestinati: 3"));
        assertTrue(log.contains("File eliminati permanentemente: 0"));
        assertTrue(log.contains("Righe saltate: 1"));
        assertTrue(log.contains("Errori: 0"));
        assertTrue(log.contains("Percentuali per formato:"));
        assertTrue(log.contains("TXT: 66,7%"));
        assertTrue(log.contains("PDF: 33,3%"));
    }

    @Test
    void righeNonValideNonGeneranoCancellazioni(@TempDir Path root) throws Exception {
        Path trashDir = root.resolve("cestino");
        Files.createDirectories(trashDir);
        Path esistente = root.resolve("esistente.txt");
        writeFile(root, "esistente.txt", Instant.parse("2026-11-09T23:00:00Z"));
        Path mancante = root.resolve("mancante.txt");
        Path modificato = root.resolve("modificato.txt");
        writeFile(root, "modificato.txt", Instant.parse("2026-11-09T23:00:00Z"));

        String rootPath = root.toString();
        Path manifest = root.resolve("rischi.csv");
        Files.writeString(manifest, manifestCsv(rootPath,
                row("mancante.txt", rootPath, mancante, "TXT", "2026/11/09", "Yes"),
                row("inesistente.log", rootPath, root.resolve("inesistente.log"), "LOG", "2026/11/09", "yes"),
                row("modificato.txt", rootPath, modificato, "TXT", "2026/11/10", "Yes"),
                row("esistente.txt", rootPath, esistente, "TXT", "2026/11/09", "Forse")),
                StandardCharsets.UTF_8);

        ControlledTrash trash = new ControlledTrash(trashDir);
        PlatformAdapters platform = platform(trashDir, trash);
        DeletionEngine engine = new DeletionEngine(trash, platform.clock());

        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<OperationResult> result = new AtomicReference<>();
        engine.start(new DeletionCommand(manifest, DeletionMode.TRASH, false), new OperationObserver() {
            @Override
            public void onProgress(OperationProgress p) {
            }

            @Override
            public void onFinished(OperationResult r) {
                result.set(r);
                finished.countDown();
            }
        });
        assertTrue(finished.await(30, TimeUnit.SECONDS));
        assertEquals(OperationStatus.COMPLETED, result.get().status());

        // Nessun file è stato toccato
        assertTrue(Files.exists(esistente));
        assertTrue(Files.exists(modificato));

        Path report = root.resolve("trash_rischi.csv");
        List<String> lines = Files.readString(report, StandardCharsets.UTF_8).substring(1).lines().toList();
        assertEquals(5, lines.size());
        Map<String, String[]> byName = new java.util.HashMap<>();
        for (String line : lines) {
            String[] parts = line.split(";", -1);
            byName.put(parts[0], parts);
        }
        assertEquals("Errore", byName.get("mancante.txt")[6]);
        assertTrue(byName.get("mancante.txt")[7].contains("non trovato"));
        assertEquals("Errore", byName.get("inesistente.log")[6]);
        assertEquals("Saltato", byName.get("modificato.txt")[6]);
        assertTrue(byName.get("modificato.txt")[7].contains("non corrispondente"));
        assertEquals("Errore", byName.get("esistente.txt")[6]);
        assertTrue(byName.get("esistente.txt")[7].contains("non ammesso"));

        Path audit = root.resolve("trash_rischi.log");
        String log = Files.readString(audit, StandardCharsets.UTF_8);
        assertTrue(log.contains("Righe controllate: 4"));
        assertTrue(log.contains("Righe Da eliminare: 3"));
        assertTrue(log.contains("File cestinati: 0"));
        assertTrue(log.contains("Righe saltate: 1"));
        assertTrue(log.contains("Errori: 3"));
    }

    @Test
    void cestinoNonDisponibileNonAttivaFallbackPermanente(@TempDir Path root) throws Exception {
        Path trashDir = root.resolve("cestino");
        Files.createDirectories(trashDir);
        Path file = root.resolve("file.txt");
        writeFile(root, "file.txt", Instant.parse("2026-11-09T23:00:00Z"));

        ControlledTrash trash = new ControlledTrash(trashDir);
        trash.setUnavailable(true);
        PlatformAdapters platform = new PlatformAdapters(
                FileSystems.getDefault(), trash,
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

        String rootPath = root.toString();
        Path manifest = root.resolve("bloccato.csv");
        Files.writeString(manifest, manifestCsv(rootPath,
                row("file.txt", rootPath, file, "TXT", "2026/11/09", "Yes")),
                StandardCharsets.UTF_8);

        DeletionEngine engine = new DeletionEngine(trash, platform.clock());

        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<OperationResult> result = new AtomicReference<>();
        engine.start(new DeletionCommand(manifest, DeletionMode.TRASH, false), new OperationObserver() {
            @Override
            public void onProgress(OperationProgress p) {
            }

            @Override
            public void onFinished(OperationResult r) {
                result.set(r);
                finished.countDown();
            }
        });
        assertTrue(finished.await(30, TimeUnit.SECONDS));
        assertEquals(OperationStatus.COMPLETED, result.get().status());
        assertTrue(Files.exists(file), "il file deve rimanere intatto quando il cestino non è disponibile");

        Path report = root.resolve("trash_bloccato.csv");
        List<String> lines = Files.readString(report, StandardCharsets.UTF_8).substring(1).lines().toList();
        assertEquals(2, lines.size());
        assertEquals("Saltato", lines.get(1).split(";", -1)[6]);
        assertTrue(lines.get(1).contains("cestino non disponibile"));

        Path audit = root.resolve("trash_bloccato.log");
        String log = Files.readString(audit, StandardCharsets.UTF_8);
        assertTrue(log.contains("Righe saltate: 1"));
        assertTrue(log.contains("Errori: 0"));
        assertTrue(log.contains("File cestinati: 0"));
    }

    @Test
    void csvRifiutatoProduceComunqueAuditFallita(@TempDir Path root) throws Exception {
        Path trashDir = Files.createDirectory(root.resolve("cestino"));
        ControlledTrash trash = new ControlledTrash(trashDir);
        Path manifest = root.resolve("scansione_20261110_100001.csv");
        Files.writeString(manifest,
                "Nome file;Path cartella;Path completo;Formato file;Data ultima modifica;Delete\n",
                StandardCharsets.UTF_8);

        OperationResult result = execute(new DeletionEngine(trash, Clock.fixed(FIXED, ZoneOffset.UTC)), manifest);

        assertEquals(OperationStatus.FAILED, result.status());
        String audit = Files.readString(root.resolve("trash_20261110_100001.log"), StandardCharsets.UTF_8);
        assertTrue(audit.contains("Stato: FALLITA"));
        assertTrue(audit.contains("BOM UTF-8"));
    }

    @Test
    void erroreFataleDopoUnaRigaProduceReportEAuditConEffettiReali(@TempDir Path root)
            throws Exception {
        Path trashDir = Files.createDirectory(root.resolve("cestino"));
        Path completato = root.resolve("completato.txt");
        Path fatale = root.resolve("fatale.txt");
        Path nonElaborato = root.resolve("non-elaborato.txt");
        writeFile(root, "completato.txt", Instant.parse("2026-11-09T23:00:00Z"));
        writeFile(root, "fatale.txt", Instant.parse("2026-11-09T23:00:00Z"));
        writeFile(root, "non-elaborato.txt", Instant.parse("2026-11-09T23:00:00Z"));
        Path manifest = root.resolve("fatale.csv");
        Files.writeString(manifest, manifestCsv(root.toString(),
                row("completato.txt", root.toString(), completato, "TXT", "2026/11/09", "Yes"),
                row("fatale.txt", root.toString(), fatale, "TXT", "2026/11/09", "Yes"),
                row("non-elaborato.txt", root.toString(), nonElaborato, "TXT", "2026/11/09", "Yes")),
                StandardCharsets.UTF_8);
        AtomicInteger calls = new AtomicInteger();
        TrashAdapter fatalAfterFirst = file -> {
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("guasto fatale controllato");
            }
            try {
                Files.move(file, trashDir.resolve(file.getFileName()));
                return new TrashResult(TrashResult.Status.MOVED, "cestinato prima del guasto");
            } catch (Exception exc) {
                return new TrashResult(TrashResult.Status.FAILED, exc.getMessage());
            }
        };

        OperationResult result = execute(
                new DeletionEngine(fatalAfterFirst, Clock.fixed(FIXED, ZoneOffset.UTC)), manifest);

        assertEquals(OperationStatus.FAILED, result.status());
        assertFalse(Files.exists(completato), "la prima riga già conclusa deve restare cestinata");
        assertTrue(Files.exists(fatale), "la riga del guasto fatale non deve risultare cancellata");
        assertTrue(Files.exists(nonElaborato), "le righe successive al guasto non devono essere avviate");

        List<String> report = Files.readString(root.resolve("trash_fatale.csv"), StandardCharsets.UTF_8)
                .substring(1).lines().toList();
        assertEquals(4, report.size());
        assertEquals("Cestinato", report.get(1).split(";", -1)[6]);
        assertEquals("Errore", report.get(2).split(";", -1)[6]);
        assertTrue(report.get(2).split(";", -1)[7].contains("guasto fatale controllato"));
        assertEquals("Non elaborato", report.get(3).split(";", -1)[6]);

        String audit = Files.readString(root.resolve("trash_fatale.log"), StandardCharsets.UTF_8);
        assertTrue(audit.contains("Stato: FALLITA"));
        assertTrue(audit.contains("Righe controllate: 3"));
        assertTrue(audit.contains("Righe elaborate: 2"));
        assertTrue(audit.contains("Righe Da eliminare: 3"));
        assertTrue(audit.contains("File cestinati: 1"));
        assertTrue(audit.contains("Errori: 1"));
        assertTrue(audit.contains("TXT: 100,0%"));
        assertTrue(audit.contains("guasto fatale controllato"));
    }

    private static OperationResult execute(DeletionEngine engine, Path manifest) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<OperationResult> result = new AtomicReference<>();
        engine.start(new DeletionCommand(manifest, DeletionMode.TRASH, false), new OperationObserver() {
            @Override
            public void onProgress(OperationProgress progress) {
            }

            @Override
            public void onFinished(OperationResult operationResult) {
                result.set(operationResult);
                finished.countDown();
            }
        });
        assertTrue(finished.await(10, TimeUnit.SECONDS));
        return result.get();
    }
}
