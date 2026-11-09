package it.scandeletefiles.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.scandeletefiles.platform.FileSettingsAdapter;
import it.scandeletefiles.platform.SettingsAdapter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifica la persistenza delle selezioni delle estensioni attraverso il seam di
 * configurazione, con lo storage isolato in directory temporanee.
 *
 * <p>Copre aggiunta, rimozione, ripristino dei default, sopravvivenza tra avvii e
 * comportamento con configurazione corrotta (default + avviso).
 */
class ExtensionPreferencesTest {

    private SettingsAdapter storage(Path dir) {
        return new FileSettingsAdapter(dir.resolve("selected-extensions.txt"));
    }

    @Test
    void primoAvvioRitornaDefault(@TempDir Path dir) {
        ExtensionPreferences prefs = new ExtensionPreferences(storage(dir));
        assertEquals(ExtensionCatalog.defaultExtensions(), prefs.selectedExtensions(),
                "al primo utilizzo si applicano i valori predefiniti di SPEC");
    }

    @Test
    void selezionePersisteTraAvvii(@TempDir Path dir) {
        ExtensionPreferences first = new ExtensionPreferences(storage(dir));
        first.remove("PDF");
        first.add("MP3");

        Set<String> prima = first.selectedExtensions();

        ExtensionPreferences second = new ExtensionPreferences(storage(dir));
        assertEquals(prima, second.selectedExtensions(),
                "la selezione deve sopravvivere al riavvio dell'applicazione");
        assertTrue(second.isSelected("MP3"), "l'estensione aggiunta deve essere ancora selezionata");
        assertFalse(second.isSelected("PDF"), "l'estensione rimossa non deve essere più selezionata");
    }

    @Test
    void aggiuntaERimozione(@TempDir Path dir) {
        ExtensionPreferences prefs = new ExtensionPreferences(storage(dir));

        prefs.add("MP3");
        assertTrue(prefs.isSelected("MP3"), "l'estensione aggiunta deve risultare selezionata");

        prefs.remove("TXT");
        assertFalse(prefs.isSelected("TXT"), "l'estensione rimossa non deve risultare più selezionata");
        assertTrue(prefs.isSelected("PDF"), "le altre estensioni devono rimanere selezionate");
    }

    @Test
    void ripristinoDefault(@TempDir Path dir) {
        ExtensionPreferences prefs = new ExtensionPreferences(storage(dir));
        prefs.remove("PDF");
        prefs.add("MP3");

        prefs.resetToDefaults();

        assertEquals(ExtensionCatalog.defaultExtensions(), prefs.selectedExtensions(),
                "il ripristino deve riportare esattamente i default di SPEC");
        assertTrue(prefs.isSelected("PDF"));
        assertFalse(prefs.isSelected("MP3"));
    }

    @Test
    void configurazioneCorrottaApplicaDefaultEAvisa(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("selected-extensions.txt");
        // Scrive byte che non formano un UTF-8 valido per simulare un file corrotto.
        Files.write(file, new byte[] {(byte) 0xFF, (byte) 0xFE, 0x41, 0x42});

        ExtensionPreferences prefs = new ExtensionPreferences(storage(dir));

        assertEquals(ExtensionCatalog.defaultExtensions(), prefs.selectedExtensions(),
                "una configurazione corrotta deve applicare i valori predefiniti");
        assertTrue(prefs.hasWarnings(), "una configurazione corrotta deve generare un avviso");
        assertFalse(prefs.warnings().isEmpty(), "l'avviso deve essere presente");
    }
}
