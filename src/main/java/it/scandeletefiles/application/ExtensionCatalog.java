package it.scandeletefiles.application;

import java.util.Locale;
import java.util.Set;

/**
 * Catalogo predefinito delle estensioni supportate, come definito in {@code docs/SPEC.md}
 * (catalogo predefinito della checklist dei formati).
 *
 * <p>Il confronto delle estensioni è case-insensitive: {@code .PDF}, {@code .pdf} e {@code .Pdf}
 * trattano lo stesso formato. Il catalogo è il riferimento per i valori predefiniti applicati
 * al primo utilizzo e per il ripristino delle impostazioni.
 */
public final class ExtensionCatalog {

    private static final Set<String> DEFAULTS = Set.of(
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
    );

    private ExtensionCatalog() {
    }

    /**
     * Restituisce le estensioni predefinite definite in SPEC, in ordine non significativo.
     */
    public static Set<String> defaultExtensions() {
        return Set.copyOf(DEFAULTS);
    }

    /**
     * Normalizza un'estensione in forma maiuscola per il confronto case-insensitive.
     */
    public static String normalize(String extension) {
        return extension.toUpperCase(Locale.ROOT);
    }

    /**
     * Indica se l'estensione è presente nel catalogo predefinito, senza distinguere
     * tra maiuscole e minuscole.
     */
    public static boolean isSupported(String extension) {
        return extension != null && DEFAULTS.contains(normalize(extension));
    }
}
