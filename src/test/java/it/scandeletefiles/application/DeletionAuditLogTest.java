package it.scandeletefiles.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.scandeletefiles.bootstrap.PlatformAdapters;
import it.scandeletefiles.application.CancellableOperation;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeletionAuditLogTest {

    private static final Instant FIXED = Instant.parse("2026-11-09T10:00:00Z");

    private static final class ControlledTrash implements TrashAdapter {
        private final Path trashDir;
        private final long delayMillis;
        private final List<Path> moved = new CopyOnWriteArrayList<>();
        private volatile boolean unavailable;

        ControlledTrash(Path trashDir) {
            this(trashDir, 0);
        }

        ControlledTrash(Path trashDir, long delayMillis) {
            this.trashDir = trashDir;
            this.delayMillis = delayMillis;
        }

        void setUnavailable(boolean unavailable) {
            this.unavailable = unavailable;
        }

        @Override
        public TrashResult moveToTrash(Path file) {
            if (unavailable) {
                return new TrashResult(TrashResult.Status.UNAVAILABLE, "cestino non disponibile");
            }
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException exc) {
                    Thread.currentThread().interrupt();
                    return new TrashResult(TrashResult.Status.FAILED, "interrotto");
                }
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

        Path report = root.resolve("operazione.report.csv");
        assertTrue(Files.exists(report));
        String content = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(content.startsWith("\uFEFF"));
        List<String> lines = content.substring(1).lines().toList();
        assertEquals("Nome file;Path cartella;Path completo;Formato file;Data ultima modifica;Delete;Esito;Dettaglio",
                lines.get(0));
        assertEquals(5, lines.size());

        Path audit = root.resolve("operazione.csv.log");
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

        Path report = root.resolve("rischi.report.csv");
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

        Path audit = root.resolve("rischi.csv.log");
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

        Path report = root.resolve("bloccato.report.csv");
        List<String> lines = Files.readString(report, StandardCharsets.UTF_8).substring(1).lines().toList();
        assertEquals(2, lines.size());
        assertEquals("Errore", lines.get(1).split(";", -1)[6]);
        assertTrue(lines.get(1).contains("cestino non disponibile"));

        Path audit = root.resolve("bloccato.csv.log");
        String log = Files.readString(audit, StandardCharsets.UTF_8);
        assertTrue(log.contains("Errori: 1"));
        assertTrue(log.contains("File cestinati: 0"));
    }

    @Test
    void cancellazioneProduceAuditAnnullataEReggeSoloRigheElaborate(@TempDir Path root) throws Exception {
        Path trashDir = root.resolve("cestino");
        Files.createDirectories(trashDir);
        List<Path> files = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 6; i++) {
            Path file = root.resolve("file" + i + ".txt");
            Files.writeString(file, "contenuto " + i);
            Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-11-09T23:00:00Z")));
            files.add(file);
        }

        ControlledTrash slow = new ControlledTrash(trashDir, 100);
        PlatformAdapters platform = new PlatformAdapters(
                FileSystems.getDefault(), slow,
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
        Path manifest = root.resolve("da-annullare.csv");
        StringBuilder csv = new StringBuilder("\uFEFFNome file;Path cartella;Path completo;Formato file;Data ultima modifica;Delete\n");
        for (Path file : files) {
            csv.append(row(file.getFileName().toString(), rootPath, file, "TXT", "2026/11/09", "Yes")).append("\n");
        }
        Files.writeString(manifest, csv.toString(), StandardCharsets.UTF_8);

        DeletionEngine engine = new DeletionEngine(slow, platform.clock());

        CountDownLatch finished = new CountDownLatch(1);
        CountDownLatch firstProgress = new CountDownLatch(1);
        AtomicReference<OperationResult> result = new AtomicReference<>();
        CancellableOperation operation = engine.start(new DeletionCommand(manifest, DeletionMode.TRASH, false),
                new OperationObserver() {
                    @Override
                    public void onProgress(OperationProgress p) {
                        if (p.completed() >= 1) {
                            firstProgress.countDown();
                        }
                    }

                    @Override
                    public void onFinished(OperationResult r) {
                        result.set(r);
                        finished.countDown();
                    }
                });

        assertTrue(firstProgress.await(30, TimeUnit.SECONDS), "il servizio non ha riportato avanzamento");
        operation.requestCancellation();
        assertTrue(finished.await(60, TimeUnit.SECONDS), "la cancellazione non è terminata");

        assertEquals(OperationStatus.CANCELLED, result.get().status());
        assertTrue(slow.moved.size() >= 1, "almeno una riga deve essere stata cestinata prima dell'annullamento");
        assertTrue(slow.moved.size() < 6, "non tutte le righe devono essere state cestinate");

        Path report = root.resolve("da-annullare.report.csv");
        assertTrue(Files.exists(report), "il report deve essere prodotto anche in annullamento");
        List<String> lines = Files.readString(report, StandardCharsets.UTF_8).substring(1).lines().toList();
        assertEquals(7, lines.size(), "intestazione + una riga di report per ogni riga di input");
        long cestinati = lines.stream().map(l -> l.split(";", -1)).filter(p -> "Cestinato".equals(p[6])).count();
        long nonElaborate = lines.stream().map(l -> l.split(";", -1)).filter(p -> "Non elaborato".equals(p[6])).count();
        assertEquals(slow.moved.size(), cestinati);
        assertEquals(6, cestinati + nonElaborate);

        Path audit = root.resolve("da-annullare.csv.log");
        String log = Files.readString(audit, StandardCharsets.UTF_8);
        assertTrue(log.contains("Stato: ANNULLATA"), "l'audit deve dichiarare lo stato annullato");
        assertTrue(log.contains("Righe controllate: 6"));
        assertTrue(log.contains("Righe Da eliminare: 6"));
    }
}
