package it.scandeletefiles.bootstrap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

class ApplicationCompositionRootTest {

    @Test
    void createsTheApplicationCompositionWithoutErrors() {
        assertDoesNotThrow(ApplicationCompositionRoot::create);
    }
}
