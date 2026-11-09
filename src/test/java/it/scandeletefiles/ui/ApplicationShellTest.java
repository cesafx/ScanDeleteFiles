package it.scandeletefiles.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import it.scandeletefiles.bootstrap.ApplicationCompositionRoot;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class ApplicationShellTest {

    @BeforeAll
    static void startJavaFx() {
        Platform.startup(() -> {});
    }

    @Test
    void rendersBothScreensThroughTheNavigationControls() throws Exception {
        runOnFxThread(() -> {
            ApplicationCompositionRoot root = ApplicationCompositionRoot.create();
            Parent view = new ApplicationShell(root.navigation()).createView();
            Scene scene = new Scene(view);
            view.applyCss();
            view.layout();

            assertEquals("Scansione", title(scene).getText());

            Button deleteNavigation = (Button) scene.lookup("#delete-navigation");
            assertNotNull(deleteNavigation);
            deleteNavigation.fire();
            assertEquals("Cancellazione", title(scene).getText());

            Button scanNavigation = (Button) scene.lookup("#scan-navigation");
            assertNotNull(scanNavigation);
            scanNavigation.fire();
            assertEquals("Scansione", title(scene).getText());
            return null;
        });
    }

    private static Label title(Scene scene) {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        Label title = (Label) scene.lookup("#screen-title");
        assertNotNull(title);
        return title;
    }

    private static <T> T runOnFxThread(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        Platform.runLater(task);
        return task.get();
    }
}
