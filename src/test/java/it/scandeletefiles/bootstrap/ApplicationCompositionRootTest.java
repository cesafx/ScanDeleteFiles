package it.scandeletefiles.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.FileSystems;
import org.junit.jupiter.api.Test;

class ApplicationCompositionRootTest {

    @Test
    void composesNavigationApplicationServicesAndPlatformAdapters() {
        ApplicationCompositionRoot root = ApplicationCompositionRoot.create();

        assertNotNull(root.navigation());
        assertNotNull(root.services().scan());
        assertNotNull(root.services().deletion());
        assertSame(FileSystems.getDefault(), root.platform().fileSystem());
        assertNotNull(root.platform().trash());
        assertNotNull(root.platform().settings());
        assertNotNull(root.platform().clock());
        assertEquals("Scansione", root.navigation().currentScreen().title());
    }
}
