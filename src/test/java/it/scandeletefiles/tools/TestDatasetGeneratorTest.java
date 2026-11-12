package it.scandeletefiles.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestDatasetGeneratorTest {

    @Test
    void generaMilleDocumentiConAlberaturaFormatiEDateVari(@TempDir Path root) throws Exception {
        TestDatasetGenerator.generate(root);

        Path source = root.resolve("CartellaCollaudo_Sorgente");
        Path destination = root.resolve("CartellaCollaudo_Destinazione");
        try (var stream = Files.walk(source)) {
            var files = stream.filter(Files::isRegularFile).toList();
            assertEquals(TestDatasetGenerator.DOCUMENT_COUNT, files.size());
            Set<String> extensions = files.stream()
                    .map(path -> path.getFileName().toString())
                    .map(name -> name.substring(name.lastIndexOf('.') + 1))
                    .collect(Collectors.toSet());
            assertTrue(extensions.size() >= 30, "il dataset deve coprire molti formati");
            Instant oldest = files.stream().map(TestDatasetGeneratorTest::lastModified)
                    .min(Instant::compareTo).orElseThrow();
            Instant newest = files.stream().map(TestDatasetGeneratorTest::lastModified)
                    .max(Instant::compareTo).orElseThrow();
            assertTrue(oldest.isBefore(Instant.parse("2020-01-01T00:00:00Z")));
            assertTrue(newest.isAfter(Instant.parse("2025-01-01T00:00:00Z")));
        }
        try (var stream = Files.walk(source)) {
            assertTrue(stream.filter(Files::isDirectory).count() >= 100,
                    "il dataset deve avere un'alberatura ampia");
        }
        assertTrue(Files.exists(destination.resolve("_CARTELLA_DESTINAZIONE.txt")));
        assertTrue(Files.readString(root.resolve("LEGGIMI_COLLAUDO.txt"))
                .contains("esattamente 1000 documenti"));

        Path secondPackage = root.resolve("seconda-generazione");
        TestDatasetGenerator.generate(secondPackage);
        assertEquals(directoryTimes(source),
                directoryTimes(secondPackage.resolve("CartellaCollaudo_Sorgente")),
                "le date delle cartelle devono essere indipendenti dal percorso di build");
    }

    private static Map<String, Instant> directoryTimes(Path source) throws Exception {
        try (var stream = Files.walk(source)) {
            return stream.filter(Files::isDirectory)
                    .collect(Collectors.toMap(
                            path -> source.relativize(path).toString().replace('\\', '/'),
                            TestDatasetGeneratorTest::lastModified));
        }
    }

    private static Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (java.io.IOException exc) {
            throw new java.io.UncheckedIOException(exc);
        }
    }
}
