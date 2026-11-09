package it.scandeletefiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class ScanDeleteFilesApplicationTest {

    @Test
    void configuresItalianLocaleForDisplayAndDateFormatting() throws Exception {
        Locale previousDefault = Locale.getDefault();
        Locale previousDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        Locale previousFormat = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.US);
            new ScanDeleteFilesApplication().init();

            assertEquals(Locale.ITALIAN, Locale.getDefault());
            assertEquals(Locale.ITALIAN, Locale.getDefault(Locale.Category.DISPLAY));
            assertEquals(Locale.ITALIAN, Locale.getDefault(Locale.Category.FORMAT));
        } finally {
            Locale.setDefault(previousDefault);
            Locale.setDefault(Locale.Category.DISPLAY, previousDisplay);
            Locale.setDefault(Locale.Category.FORMAT, previousFormat);
        }
    }
}
