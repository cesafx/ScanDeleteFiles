package it.scandeletefiles.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Implementazione {@link SettingsAdapter} che memorizza le estensioni selezionate in un
 * file di testo UTF-8, un'estensione per riga. I valori vengono memorizzati in forma
 * maiuscola e ordinati, per ottenere un contenuto deterministico e facile da ispezionare.
 *
 * <p>Un file assente viene trattato come "nessuna configurazione memorizzata" (selezione
 * vuota). Un file illeggibile (ad esempio corrotto o con codifica non valida) provoca il
 * fallimento del caricamento, lasciando al chiamante il compito di applicare i default.
 */
public final class FileSettingsAdapter implements SettingsAdapter {

    private final Path file;

    public FileSettingsAdapter(Path file) {
        this.file = file;
    }

    @Override
    public Set<String> loadSelectedExtensions() {
        if (!Files.exists(file)) {
            return Set.of();
        }
        try {
            Set<String> extensions = new LinkedHashSet<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String value = line.trim();
                if (!value.isEmpty()) {
                    extensions.add(value.toUpperCase(java.util.Locale.ROOT));
                }
            }
            return extensions;
        } catch (IOException exc) {
            throw new IllegalStateException(
                    "Impossibile leggere la configurazione delle estensioni: " + file, exc);
        }
    }

    @Override
    public void saveSelectedExtensions(Set<String> extensions) {
        Path parent = file.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException exc) {
                throw new IllegalStateException(
                        "Impossibile creare la directory di configurazione: " + parent, exc);
            }
        }
        Set<String> sorted = new TreeSet<>(extensions);
        StringBuilder content = new StringBuilder();
        for (String extension : sorted) {
            content.append(extension).append('\n');
        }
        try {
            Files.write(file, content.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException exc) {
            throw new IllegalStateException(
                    "Impossibile salvare la configurazione delle estensioni: " + file, exc);
        }
    }
}
