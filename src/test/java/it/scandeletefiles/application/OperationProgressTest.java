package it.scandeletefiles.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OperationProgressTest {

    @Test
    void supportsProgressWhenTheTotalIsNotKnownYet() {
        OperationProgress progress = OperationProgress.indeterminate(25, "Analisi cartelle");

        assertEquals(25, progress.completed());
        assertTrue(progress.total().isEmpty());
        assertEquals("Analisi cartelle", progress.message());
    }

    @Test
    void validatesProgressWhenTheTotalIsKnown() {
        OperationProgress progress = OperationProgress.determinate(3, 10, "File elaborati");

        assertFalse(progress.total().isEmpty());
        assertEquals(10, progress.total().getAsLong());
        assertThrows(
                IllegalArgumentException.class,
                () -> OperationProgress.determinate(11, 10, "Non valido"));
    }
}
