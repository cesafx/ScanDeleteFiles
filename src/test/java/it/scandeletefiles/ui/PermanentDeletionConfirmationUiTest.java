package it.scandeletefiles.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.scandeletefiles.application.CancellableOperation;
import it.scandeletefiles.application.DeletionApplicationService;
import it.scandeletefiles.application.DeletionCommand;
import it.scandeletefiles.application.DeletionMode;
import it.scandeletefiles.application.OperationObserver;
import it.scandeletefiles.application.PermanentDeletionConfirmation;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class PermanentDeletionConfirmationUiTest {

    private static final CancellableOperation NO_OP = new CancellableOperation() {
        @Override
        public void requestCancellation() {
        }

        @Override
        public boolean isCancellationRequested() {
            return false;
        }
    };

    @BeforeAll
    static void startJavaFx() {
        try {
            Platform.startup(() -> {
            });
        } catch (IllegalStateException alreadyStarted) {
            // Un'altra classe di test ha già avviato il toolkit JavaFX.
        }
    }

    @Test
    void cestinoEPredefinitoENonRichiedeConferma(@TempDir Path root) throws Exception {
        ControlledDeletionService service = new ControlledDeletionService();
        AtomicInteger confirmations = new AtomicInteger();

        runOnFxThread(() -> {
            Scene scene = scene(new DeletionView(service, warning -> {
                confirmations.incrementAndGet();
                return PermanentDeletionConfirmation.Result.CONFIRMED;
            }));
            start(scene, root.resolve("manifest.csv"));
            return null;
        });

        assertEquals(0, confirmations.get());
        assertEquals(1, service.starts.get());
        assertEquals(DeletionMode.TRASH, service.command.get().mode());
        assertFalse(service.command.get().permanentDeletionConfirmed());
    }

    @Test
    void permanenteAccettataAvviaUnaVoltaConConfermaIrreversibile(@TempDir Path root) throws Exception {
        ControlledDeletionService service = new ControlledDeletionService();
        AtomicReference<String> shownWarning = new AtomicReference<>();

        runOnFxThread(() -> {
            Scene scene = scene(new DeletionView(service, warning -> {
                shownWarning.set(warning);
                return PermanentDeletionConfirmation.Result.CONFIRMED;
            }));
            selectPermanent(scene);
            start(scene, root.resolve("manifest.csv"));
            return null;
        });

        assertEquals(1, service.starts.get());
        assertEquals(DeletionMode.PERMANENT, service.command.get().mode());
        assertTrue(service.command.get().permanentDeletionConfirmed());
        assertNotNull(shownWarning.get());
        assertTrue(shownWarning.get().toLowerCase().contains("irreversibile"));
    }

    @Test
    void rifiutoNonAvviaIlServizio(@TempDir Path root) throws Exception {
        ControlledDeletionService service = new ControlledDeletionService();

        runOnFxThread(() -> {
            Scene scene = scene(new DeletionView(
                    service, warning -> PermanentDeletionConfirmation.Result.REJECTED));
            selectPermanent(scene);
            start(scene, root.resolve("manifest.csv"));
            return null;
        });

        assertEquals(0, service.starts.get());
        assertNull(service.command.get());
    }

    @Test
    void chiusuraNonAvviaIlServizio(@TempDir Path root) throws Exception {
        ControlledDeletionService service = new ControlledDeletionService();

        runOnFxThread(() -> {
            Scene scene = scene(new DeletionView(
                    service, warning -> PermanentDeletionConfirmation.Result.CLOSED));
            selectPermanent(scene);
            start(scene, root.resolve("manifest.csv"));
            return null;
        });

        assertEquals(0, service.starts.get());
        assertNull(service.command.get());
    }

    private static Scene scene(DeletionView deletionView) {
        Parent view = deletionView.createView();
        Scene scene = new Scene(view);
        view.applyCss();
        view.layout();
        return scene;
    }

    private static void selectPermanent(Scene scene) {
        RadioButton permanent = (RadioButton) scene.lookup("#permanent-deletion-mode");
        assertNotNull(permanent);
        permanent.setSelected(true);
    }

    private static void start(Scene scene, Path manifest) {
        TextField manifestField = (TextField) scene.lookup("#deletion-manifest");
        assertNotNull(manifestField);
        manifestField.setText(manifest.toString());
        Button action = (Button) scene.lookup("#avvia-cancellazione");
        assertNotNull(action);
        assertFalse(action.isDisabled());
        action.fire();
    }

    private static <T> T runOnFxThread(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        Platform.runLater(task);
        return task.get();
    }

    private static final class ControlledDeletionService implements DeletionApplicationService {
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicReference<DeletionCommand> command = new AtomicReference<>();

        @Override
        public CancellableOperation start(DeletionCommand deletionCommand, OperationObserver observer) {
            starts.incrementAndGet();
            command.set(deletionCommand);
            return NO_OP;
        }
    }
}
