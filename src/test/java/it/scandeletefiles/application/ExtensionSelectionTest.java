package it.scandeletefiles.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifica il catalogo predefinito delle estensioni definito in {@code docs/SPEC.md}.
 *
 * <p>I test sono parametrizzati in modo che ciascun formato del catalogo venga coperto
 * singolarmente e il confronto delle estensioni sia verificato senza distinzione tra
 * maiuscole e minuscole.
 */
class ExtensionSelectionTest {

    private static final String[] FORMATI_SPEC = {
        // Testi
        "TXT", "LOG", "PDF",
        // Immagini
        "JPG", "JPEG", "PNG", "GIF", "BMP", "TIF", "TIFF", "WEBP", "HEIC",
        // Documenti Office
        "DOC", "DOCX", "DOCM", "DOT", "DOTX", "DOTM", "RTF",
        // Fogli di calcolo
        "XLS", "XLSX", "XLSM", "XLSB", "XLT", "XLTX", "XLTM", "CSV",
        // Presentazioni
        "PPT", "PPTX", "PPTM", "POT", "POTX", "POTM", "PPS", "PPSX", "PPSM",
        // OpenDocument
        "ODT", "ODS", "ODP",
        // Archivi
        "ZIP", "RAR", "7Z"
    };

    @ParameterizedTest
    @ValueSource(strings = {
        "TXT", "LOG", "PDF",
        "JPG", "JPEG", "PNG", "GIF", "BMP", "TIF", "TIFF", "WEBP", "HEIC",
        "DOC", "DOCX", "DOCM", "DOT", "DOTX", "DOTM", "RTF",
        "XLS", "XLSX", "XLSM", "XLSB", "XLT", "XLTX", "XLTM", "CSV",
        "PPT", "PPTX", "PPTM", "POT", "POTX", "POTM", "PPS", "PPSX", "PPSM",
        "ODT", "ODS", "ODP",
        "ZIP", "RAR", "7Z"
    })
    void ogniFormatoDelCatalogoSpecRiconosciuto(String extension) {
        assertTrue(ExtensionCatalog.isSupported(extension),
                "il formato " + extension + " deve essere supportato");
        assertTrue(ExtensionCatalog.isSupported(extension.toLowerCase(java.util.Locale.ROOT)),
                "il matching di " + extension + " deve essere case-insensitive");
        assertTrue(ExtensionCatalog.isSupported(extension.toUpperCase(java.util.Locale.ROOT)),
                "il matching di " + extension + " deve essere case-insensitive");
    }

    @Test
    void defaultEsattiDefinitiInSPEC() {
        Set<String> defaults = ExtensionCatalog.defaultExtensions();
        assertEquals(Set.of(FORMATI_SPEC), defaults,
                "il catalogo predefinito deve contenere esattamente i formati definiti in SPEC");
        assertEquals(FORMATI_SPEC.length, defaults.size(),
                "il catalogo predefinito deve contenere " + FORMATI_SPEC.length + " formati");
    }

    @ParameterizedTest
    @ValueSource(strings = {"exe", "mp3", "mp4", "avi", "iso", "tar", "gz", "", "txt.bak"})
    void formatiNonDelCatalogoNonSonoSupportati(String extension) {
        assertFalse(ExtensionCatalog.isSupported(extension),
                "il formato " + extension + " non deve essere supportato");
    }

    @Test
    void normalizeRestituisceMaiuscole() {
        assertEquals("PDF", ExtensionCatalog.normalize("pdf"));
        assertEquals("PDF", ExtensionCatalog.normalize("Pdf"));
        assertEquals("TXT", ExtensionCatalog.normalize("TXT"));
    }
}
