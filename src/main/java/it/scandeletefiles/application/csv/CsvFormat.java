package it.scandeletefiles.application.csv;

import it.scandeletefiles.application.OperationStatus;

/** Utilità condivise per la scrittura CSV: escapatura campi e etichette di stato in italiano. */
public final class CsvFormat {

    private CsvFormat() {
    }

    /**
     * Escapa un valore CSV secondo RFC 4180 semplificata: racchiude tra virgolette quando
     * contiene separatore, virgolette, a capo o ritorno a carro; raddoppia le virgolette interne.
     */
    public static String escape(String value, char separator) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(separator) >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    public static String statoItaliano(OperationStatus status) {
        return switch (status) {
            case COMPLETED -> "COMPLETATA";
            case CANCELLED -> "ANNULLATA";
            case FAILED -> "FALLITA";
        };
    }
}
