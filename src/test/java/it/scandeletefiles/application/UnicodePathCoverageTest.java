package it.scandeletefiles.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.scandeletefiles.application.scan.ScanEngine;
import it.scandeletefiles.bootstrap.PlatformAdapters;
import it.scandeletefiles.platform.SettingsAdapter;
import it.scandeletefiles.platform.TrashAdapter;
import it.scandeletefiles.platform.TrashResult;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Copertura di spazi, accenti e caratteri Unicode nei percorsi (criterio di
 * accettazione ACME-35) e verifica esterna del percorso Scan
 * &rarr; modifica del CSV &rarr; Delete, senza dati nascosti tra le due
 * operazioni.
 */
class UnicodePathCoverageTest {

    private static final Instant FIXED = Instant.parse("2026-11-09T10:00:00Z");
    private static final Instant MTIME = Instant.parse("2026-11-09T23:00:00Z");

    private static final class ControlledTrash implements TrashAdapter {
        private final Path trashDir;
        private final List<Path> moved = new ArrayList<>();

        ControlledTrash(Path trashDir) {
            this.trashDir = trashDir;
        }

        @Override
        public TrashResult moveToTrash(Path file) {
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
        Path file = root.resolve(name);
        Files.writeString(file, "contenuto " + name);
        Files.setLastModifiedTime(file, FileTime.from(mtime));
    }

    private static DeletionEngine engine(Path root, ControlledTrash trash) throws Exception {
        return new DeletionEngine(trash, platform(root.resolve("cestino"), trash).clock());
    }

    private static OperationResult esegui(DeletionEngine engine, Path manifest) throws Exception {
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
        assertTrue(finished.await(30, TimeUnit.SECONDS), "l'operazione non è terminata");
        return result.get();
    }

    private static OperationResult eseguiScan(ScanEngine engine, ScanCommand command) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<OperationResult> result = new AtomicReference<>();
        engine.start(command, new OperationObserver() {
            @Override
            public void onProgress(OperationProgress p) {
            }

            @Override
            public void onFinished(OperationResult r) {
                result.set(r);
                finished.countDown();
            }
        });
        assertTrue(finished.await(30, TimeUnit.SECONDS), "la scansione non è terminata");
        return result.get();
    }

    /**
     * Partizione di una riga CSV nei campi, gestendo virgolette doppie e
     * delimitatore protetto da virgolette (stile contratto SPEC).
     */
    private static String[] dividiCsv(String riga) {
        List<String> campi = new ArrayList<>();
        StringBuilder corrente = new StringBuilder();
        boolean traVirgolette = false;
        for (int i = 0; i < riga.length(); i++) {
            char c = riga.charAt(i);
            if (traVirgolette) {
                if (c == '"') {
                    if (i + 1 < riga.length() && riga.charAt(i + 1) == '"') {
                        corrente.append('"');
                        i++;
                    } else {
                        traVirgolette = false;
                    }
                } else {
                    corrente.append(c);
                }
            } else if (c == ';') {
                campi.add(corrente.toString());
                corrente.setLength(0);
            } else if (c == '"') {
                traVirgolette = true;
            } else {
                corrente.append(c);
            }
        }
        campi.add(corrente.toString());
        return campi.toArray(String[]::new);
    }

    @Test
    void scanAccoglieSpaziAccentiEUniCodeNelManifest(@TempDir Path root) throws Exception {
        List<String> nomiConSpecialiList = new ArrayList<>(List.of(
                "documento.txt",
                "file con spazi.txt",
                "café.txt",
                "報告.txt",
                "файл.txt",
                "a;b.txt"));
        if (File.separatorChar != '\\') {
            nomiConSpecialiList.add("strano\"file.txt");
        }
        String[] nomiConSpeciali = nomiConSpecialiList.toArray(String[]::new);
        String[] nomiEsclusi = {"escluso.log"};
        for (String nome : nomiConSpeciali) {
            writeFile(root, nome, MTIME);
        }
        for (String nome : nomiEsclusi) {
            writeFile(root, nome, MTIME);
        }

        Path trashDir = root.resolve("cestino");
        Files.createDirectories(trashDir);
        ScanEngine scan = new ScanEngine(platform(trashDir, new ControlledTrash(trashDir)));
        Path manifest = root.resolve("manifest.csv");

        OperationResult esito = eseguiScan(scan,
                new ScanCommand(root, LocalDate.of(2026, 11, 10), Set.of("txt"), false, manifest));
        assertEquals(OperationStatus.COMPLETED, esito.status());

        String contenuto = Files.readString(manifest, StandardCharsets.UTF_8);
        assertTrue(contenuto.startsWith("\uFEFF"), "il manifest deve iniziare con il BOM UTF-8");
        List<String> righe = contenuto.substring(1).lines().filter(line -> !line.isBlank()).toList();
        assertEquals("Nome file;Path cartella;Path completo;Formato file;Data ultima modifica;Delete",
                righe.get(0), "intestazione contrattuale esatta");

        Set<String> attesi = Set.of(nomiConSpeciali);
        for (String riga : righe.subList(1, righe.size())) {
            String[] campi = dividiCsv(riga);
            assertEquals(6, campi.length, "ogni riga deve avere esattamente le sei colonne");
            assertEquals("Yes", campi[5], "Delete deve essere inizializzato a Yes");
            String nome = campi[0];
            assertTrue(attesi.contains(nome), "riga inattesa nel manifest: " + nome);
            assertEquals(root.resolve(nome).toString(), campi[2],
                    "Path completo deve identificare il file esatto per " + nome);
            assertEquals("TXT", campi[3]);
            assertEquals("2026/11/09", campi[4]);
        }
        assertEquals(attesi.size(), righe.size() - 1,
                "dei file selezionati devono comparire nel manifest, estensione .log esclusa");

        // Il manifest prodotto dalla scansione viene consumato direttamente dal Delete:
        // tutte le righe Yes vengono cestinate, percorsi speciali inclusi.
        ControlledTrash trash = new ControlledTrash(trashDir);
        OperationResult eliminazione = esegui(engine(root, trash), manifest);
        assertEquals(OperationStatus.COMPLETED, eliminazione.status());
        assertEquals(nomiConSpeciali.length, trash.moved.size(), "tutti i file Yes devono essere cestinati");
        for (String nome : nomiConSpeciali) {
            assertFalse(Files.exists(root.resolve(nome)), "il file " + nome + " deve essere stato cestinato");
        }
        for (String nome : nomiEsclusi) {
            assertTrue(Files.exists(root.resolve(nome)), "il file escluso " + nome + " deve restare intatto");
        }

        Path report = root.resolve("manifest.report.csv");
        assertTrue(Files.exists(report));
        String reportContent = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(reportContent.startsWith("\uFEFF"));
        List<String> reportRighe = reportContent.substring(1).lines().toList();
        assertEquals(nomiConSpeciali.length + 1, reportRighe.size(),
                "header più una riga per ogni file cestinato");
        for (String riga : reportRighe.subList(1, reportRighe.size())) {
            assertEquals("Cestinato", dividiCsv(riga)[6]);
        }
    }

    @Test
    void deleteConsumaManifestConPercorsiUnicodeEValoriDeleteModificati(@TempDir Path root) throws Exception {
        String[] nomi = {"documento.txt", "file con spazi.txt", "café.txt", "файл.txt"};
        for (String nome : nomi) {
            writeFile(root, nome, MTIME);
        }

        Path trashDir = root.resolve("cestino");
        Files.createDirectories(trashDir);
        ControlledTrash trash = new ControlledTrash(trashDir);
        DeletionEngine elimina = engine(root, trash);

        String rootPath = root.toString();
        // L'utente ha modificato il CSV: Yes/yes/YES cestinano, No e vuoto salvano.
        String csv = "\uFEFFNome file;Path cartella;Path completo;Formato file;Data ultima modifica;Delete\n"
                + "documento.txt;" + rootPath + ";" + root.resolve("documento.txt") + ";TXT;2026/11/09;Yes\n"
                + "file con spazi.txt;" + rootPath + ";" + root.resolve("file con spazi.txt") + ";TXT;2026/11/09;yes\n"
                + "café.txt;" + rootPath + ";" + root.resolve("café.txt") + ";TXT;2026/11/09;No\n"
                + "файл.txt;" + rootPath + ";" + root.resolve("файл.txt") + ";TXT;2026/11/09;\n";
        Path manifest = root.resolve("modificato.csv");
        Files.writeString(manifest, csv, StandardCharsets.UTF_8);

        OperationResult esito = esegui(elimina, manifest);
        assertEquals(OperationStatus.COMPLETED, esito.status());

        assertFalse(Files.exists(root.resolve("documento.txt")));
        assertFalse(Files.exists(root.resolve("file con spazi.txt")));
        assertTrue(Files.exists(root.resolve("café.txt")));
        assertTrue(Files.exists(root.resolve("файл.txt")));
        assertEquals(2, trash.moved.size());

        Path report = root.resolve("modificato.report.csv");
        List<String> righe = Files.readString(report, StandardCharsets.UTF_8).substring(1).lines().toList();
        assertEquals(5, righe.size());
        assertEquals("Cestinato", dividiCsv(righe.get(1))[6]);
        assertEquals("Cestinato", dividiCsv(righe.get(2))[6]);
        assertEquals("Saltato", dividiCsv(righe.get(3))[6]);
        assertEquals("Saltato", dividiCsv(righe.get(4))[6]);
    }

    @Test
    void manifestDiScanVieneConsumatoDirettamenteDaDeleteDopoModificaUtente(@TempDir Path root)
            throws Exception {
        String[] nomi = {"alfa.txt", "bravo.txt", "charlie.txt"};
        for (String nome : nomi) {
            writeFile(root, nome, MTIME);
        }

        Path trashDir = root.resolve("cestino");
        Files.createDirectories(trashDir);
        ScanEngine scan = new ScanEngine(platform(trashDir, new ControlledTrash(trashDir)));
        Path manifest = root.resolve("scan.csv");

        OperationResult esito = eseguiScan(scan,
                new ScanCommand(root, LocalDate.of(2026, 11, 10), Set.of("txt"), false, manifest));
        assertEquals(OperationStatus.COMPLETED, esito.status());

        // L'utente apre il manifest prodotto dalla scansione e imposta bravo.txt su No:
        // il Delete deve leggere esattamente quel CSV, nessun dato nascosto.
        String contenuto = Files.readString(manifest, StandardCharsets.UTF_8);
        assertTrue(contenuto.startsWith("\uFEFF"));
        List<String> righe = contenuto.substring(1).lines().filter(line -> !line.isBlank()).toList();
        StringBuilder modificato = new StringBuilder("\uFEFF").append(righe.get(0)).append('\n');
        for (int i = 1; i < righe.size(); i++) {
            String[] campi = dividiCsv(righe.get(i));
            if ("bravo.txt".equals(campi[0])) {
                campi[5] = "No";
            }
            modificato.append(String.join(";", campi)).append('\n');
        }
        Path manifestModificato = root.resolve("scan-modificato.csv");
        Files.writeString(manifestModificato, modificato.toString(), StandardCharsets.UTF_8);

        ControlledTrash trash = new ControlledTrash(trashDir);
        OperationResult eliminazione = esegui(engine(root, trash), manifestModificato);
        assertEquals(OperationStatus.COMPLETED, eliminazione.status());

        assertFalse(Files.exists(root.resolve("alfa.txt")), "Delete = Yes cestina il file");
        assertTrue(Files.exists(root.resolve("bravo.txt")), "Delete = No conserva il file");
        assertFalse(Files.exists(root.resolve("charlie.txt")), "Delete = Yes cestina il file");

        Path report = root.resolve("scan-modificato.report.csv");
        List<String> reportRighe = Files.readString(report, StandardCharsets.UTF_8).substring(1).lines().toList();
        assertEquals(4, reportRighe.size());
        assertEquals("alfa.txt", dividiCsv(reportRighe.get(1))[0]);
        assertEquals("Cestinato", dividiCsv(reportRighe.get(1))[6]);
        assertEquals("bravo.txt", dividiCsv(reportRighe.get(2))[0]);
        assertEquals("Saltato", dividiCsv(reportRighe.get(2))[6]);
        assertEquals("charlie.txt", dividiCsv(reportRighe.get(3))[0]);
        assertEquals("Cestinato", dividiCsv(reportRighe.get(3))[6]);

        Path audit = root.resolve("scan-modificato.csv.log");
        assertTrue(Files.exists(audit));
        String log = Files.readString(audit, StandardCharsets.UTF_8);
        assertTrue(log.contains("Stato: COMPLETATA"));
        assertTrue(log.contains("Righe Da eliminare: 2"));
        assertTrue(log.contains("File cestinati: 2"));
        assertTrue(log.contains("Righe saltate: 1"));
    }
}
