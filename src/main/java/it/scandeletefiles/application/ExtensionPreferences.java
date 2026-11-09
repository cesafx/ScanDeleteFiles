package it.scandeletefiles.application;

import it.scandeletefiles.platform.SettingsAdapter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gestisce la selezione delle estensioni della checklist, persistendola tramite un
 * {@link SettingsAdapter} e applicando i valori predefiniti di {@link ExtensionCatalog}
 * al primo utilizzo o quando la configurazione memorizzata è illeggibile.
 *
 * <p>Consente aggiunta, rimozione e ripristino dei valori predefiniti. Ogni mutazione
 * viene immediatamente salvata sul storage. Quando la configurazione caricata non è
 * utilizzabile, si applicano i valori predefiniti e viene registrato un avviso.
 */
public final class ExtensionPreferences {

    private final SettingsAdapter storage;
    private final Set<String> selected;
    private final List<String> warnings;

    public ExtensionPreferences(SettingsAdapter storage) {
        this.storage = storage;
        this.selected = new LinkedHashSet<>();
        this.warnings = new ArrayList<>();
        Set<String> loaded = load();
        if (loaded == null || loaded.isEmpty()) {
            // Primo utilizzo o assenza di configurazione: si applicano i default SPEC.
            selected.addAll(ExtensionCatalog.defaultExtensions());
        } else {
            selected.addAll(loaded);
        }
    }

    /**
     * Restituisce una copia immutabile delle estensioni attualmente selezionate.
     */
    public Set<String> selectedExtensions() {
        return Set.copyOf(selected);
    }

    /**
     * Indica se l'estensione indicata è selezionata (confronto case-insensitive).
     */
    public boolean isSelected(String extension) {
        return selected.contains(ExtensionCatalog.normalize(extension));
    }

    /**
     * Aggiunge un'estensione alla selezione e la persiste.
     */
    public void add(String extension) {
        String normalized = ExtensionCatalog.normalize(extension);
        if (selected.add(normalized)) {
            save();
        }
    }

    /**
     * Rimuove un'estensione dalla selezione e la persiste.
     */
    public void remove(String extension) {
        String normalized = ExtensionCatalog.normalize(extension);
        if (selected.remove(normalized)) {
            save();
        }
    }

    /**
     * Ripristina le estensioni predefinite di SPEC e le persiste.
     */
    public void resetToDefaults() {
        selected.clear();
        selected.addAll(ExtensionCatalog.defaultExtensions());
        save();
    }

    /**
     * Sostituisce l'intera selezione con le estensioni indicate e le persiste.
     */
    public void replaceSelection(Set<String> extensions) {
        selected.clear();
        selected.addAll(
                extensions.stream()
                        .map(ExtensionCatalog::normalize)
                        .collect(Collectors.toCollection(LinkedHashSet::new)));
        save();
    }

    /**
     * Restituisce una copia immutabile degli avvisi generati (es. configurazione
     * corrotta sostituita con i valori predefiniti).
     */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /**
     * Indica se è stato generato almeno un avviso durante il caricamento.
     */
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    private Set<String> load() {
        try {
            return storage.loadSelectedExtensions();
        } catch (Throwable exc) {
            warnings.add("Configurazione delle estensioni illeggibile: si applicano i valori predefiniti.");
            return null;
        }
    }

    private void save() {
        storage.saveSelectedExtensions(Set.copyOf(selected));
    }
}
