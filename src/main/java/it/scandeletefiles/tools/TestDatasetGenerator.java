package it.scandeletefiles.tools;

import it.scandeletefiles.application.ExtensionCatalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Genera il dataset dimostrativo incluso nel pacchetto Windows. */
public final class TestDatasetGenerator {
    public static final int DOCUMENT_COUNT = 1_000;
    private static final long SEED = 20_261_111L;
    private static final Instant START = Instant.parse("2018-01-01T08:00:00Z");
    private static final int DATE_RANGE_DAYS = 3_200;
    private static final List<String> AREAS = List.of(
            "Amministrazione", "Archivio", "Clienti", "Contratti", "Fornitori", "Immagini",
            "Progetti", "Report", "Risorse_Umane", "Scansioni", "Tecnico", "Varie");
    private static final List<String> EXTENSIONS = ExtensionCatalog.defaultExtensions().stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .sorted()
            .toList();

    private TestDatasetGenerator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Specificare la cartella radice del pacchetto");
        }
        generate(Path.of(args[0]));
    }

    public static void generate(Path packageRoot) throws IOException {
        Path root = packageRoot.toAbsolutePath().normalize();
        Path source = root.resolve("CartellaCollaudo_Sorgente");
        Path destination = root.resolve("CartellaCollaudo_Destinazione");
        recreateDirectory(source);
        recreateDirectory(destination);

        Random random = new Random(SEED);
        List<Path> folders = createFolders(source, random);
        for (int index = 1; index <= DOCUMENT_COUNT; index++) {
            Path folder = folders.get(random.nextInt(folders.size()));
            String extension = EXTENSIONS.get(random.nextInt(EXTENSIONS.size()));
            Path file = folder.resolve(String.format(Locale.ROOT, "documento_%04d.%s", index, extension));
            int contentSize = 128 + random.nextInt(897);
            String header = "Documento segnaposto per il collaudo ScanDeleteFiles\n"
                    + "Numero: " + index + "\nFormato simulato: " + extension + "\n";
            StringBuilder content = new StringBuilder(header);
            while (content.length() < contentSize) {
                content.append("Dati di prova deterministici. ");
            }
            Files.writeString(file, content.substring(0, contentSize), StandardCharsets.UTF_8);
            Instant timestamp = START.plus(random.nextInt(DATE_RANGE_DAYS), ChronoUnit.DAYS)
                    .plus(random.nextInt(86_400), ChronoUnit.SECONDS);
            Files.setLastModifiedTime(file, FileTime.from(timestamp));
        }

        restoreFolderDates(source);
        Files.writeString(destination.resolve("_CARTELLA_DESTINAZIONE.txt"),
                "Cartella destinata alle prove di Move. Il file può essere lasciato al suo posto.\n",
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve("LEGGIMI_COLLAUDO.txt"), guideText(), StandardCharsets.UTF_8);
    }

    private static List<Path> createFolders(Path source, Random random) throws IOException {
        List<Path> folders = new ArrayList<>();
        for (int index = 0; index < 160; index++) {
            String area = AREAS.get(random.nextInt(AREAS.size()));
            int year = 2018 + random.nextInt(9);
            Path folder = source.resolve(area).resolve(Integer.toString(year));
            if (index % 3 != 0) {
                folder = folder.resolve(String.format(Locale.ROOT, "Lotto_%03d", index));
            }
            if (index % 5 == 0) {
                folder = folder.resolve("Sezione_" + (char) ('A' + random.nextInt(8)));
            }
            Files.createDirectories(folder);
            folders.add(folder);
        }
        return folders.stream().distinct().toList();
    }

    private static void restoreFolderDates(Path source) throws IOException {
        List<Path> allFolders;
        try (var stream = Files.walk(source)) {
            allFolders = stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .toList();
        }
        for (Path folder : allFolders) {
            String relative = source.relativize(folder).toString().replace('\\', '/');
            long offset = Math.floorMod(relative.hashCode(), DATE_RANGE_DAYS);
            Files.setLastModifiedTime(folder, FileTime.from(START.plus(offset, ChronoUnit.DAYS)));
        }
    }

    private static void recreateDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (var stream = Files.walk(directory)) {
                for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(directory);
    }

    private static String guideText() {
        return """
                DATI DI COLLAUDO SCANDELETEFILES

                CartellaCollaudo_Sorgente contiene esattamente 1000 documenti segnaposto,
                distribuiti in un'alberatura deterministica con numerosi formati e date comprese
                fra il 2018 e il 2026. I contenuti sono segnaposto: servono a collaudare scansione,
                filtri, CSV, Move e ripristino, non ad aprire i file nelle applicazioni Office.

                Collaudo consigliato:
                1. usare CartellaCollaudo_Sorgente come root della scansione;
                2. scegliere una data soglia e i formati desiderati;
                3. usare CartellaCollaudo_Destinazione per il Move;
                4. usare il file moving_*.undo.csv per provare Ripristina uno spostamento.

                Per ricominciare da zero, estrarre nuovamente il pacchetto ZIP.
                """;
    }
}
