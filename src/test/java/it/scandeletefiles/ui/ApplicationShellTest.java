package it.scandeletefiles.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.scandeletefiles.application.OperationObserver;
import it.scandeletefiles.application.OperationProgress;
import it.scandeletefiles.application.OperationResult;
import it.scandeletefiles.bootstrap.ApplicationCompositionRoot;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyStarted) {
            // Un'altra classe di test ha già avviato il toolkit JavaFX.
        }
    }

    @Test
    void rendersBothScreensThroughTheNavigationControls() throws Exception {
        runOnFxThread(() -> {
            ApplicationCompositionRoot root = ApplicationCompositionRoot.create();
            Parent view = root.createView();
            Scene scene = new Scene(view);
            view.applyCss();
            view.layout();

            assertEquals("Scansione", title(scene).getText());
            assertEnabled(scene, "#choose-scan-root");
            assertEnabled(scene, "#choose-scan-destination");

            Button deleteNavigation = (Button) scene.lookup("#delete-navigation");
            assertNotNull(deleteNavigation);
            deleteNavigation.fire();
            assertEquals("Cancellazione", title(scene).getText());
            assertEnabled(scene, "#choose-delete-manifest");

            Button scanNavigation = (Button) scene.lookup("#scan-navigation");
            assertNotNull(scanNavigation);
            scanNavigation.fire();
            assertEquals("Scansione", title(scene).getText());
            return null;
        });
    }

    @Test
    void dispatchesUiObserversOnTheJavaFxApplicationThread() throws Exception {
        CountDownLatch callbackReceived = new CountDownLatch(1);
        AtomicBoolean callbackOnJavaFxThread = new AtomicBoolean();
        OperationObserver delegate = new OperationObserver() {
            @Override
            public void onProgress(OperationProgress progress) {
                callbackOnJavaFxThread.set(Platform.isFxApplicationThread());
                callbackReceived.countDown();
            }

            @Override
            public void onFinished(OperationResult result) {}
        };

        JavaFxOperationObservers.onApplicationThread(delegate)
                .onProgress(OperationProgress.indeterminate(1, "In corso"));

        assertTrue(callbackReceived.await(2, TimeUnit.SECONDS));
        assertTrue(callbackOnJavaFxThread.get());
    }

    private static Label title(Scene scene) {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        Label title = (Label) scene.lookup("#screen-title");
        assertNotNull(title);
        return title;
    }

    private static void assertDisabled(Scene scene, String selector) {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        Button button = (Button) scene.lookup(selector);
        assertNotNull(button);
        assertTrue(button.isDisabled());
    }

    private static void assertEnabled(Scene scene, String selector) {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        Button button = (Button) scene.lookup(selector);
        assertNotNull(button);
        assertFalse(button.isDisabled());
    }

    private static <T> T runOnFxThread(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        Platform.runLater(task);
        return task.get();
    }
}
