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
import it.scandeletefiles.application.ScanMetadata;
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
    void spostamentoEPredefinitoENonRichiedeConferma(@TempDir Path root) throws Exception {
        ControlledDeletionService service = new ControlledDeletionService();
        AtomicInteger confirmations = new AtomicInteger();

        runOnFxThread(() -> {
            Scene scene = scene(new DeletionView(service, warning -> {
                confirmations.incrementAndGet();
                return PermanentDeletionConfirmation.Result.CONFIRMED;
            }));
            sourceRoot(scene).setText(root.toString());
            destination(scene).setText(root.resolve("destinazione").toString());
            start(scene, root.resolve("manifest.csv"));
            return null;
        });

        assertEquals(0, confirmations.get());
        assertEquals(1, service.starts.get());
        assertEquals(DeletionMode.MOVE_TO_FOLDER, service.command.get().mode());
        assertEquals(root.resolve("destinazione"), service.command.get().destination());
        assertEquals(root, service.command.get().sourceRoot());
        assertFalse(service.command.get().permanentDeletionConfirmed());
    }

    @Test
    void destinazioneEAttivaSoloPerSpostaInCartella() throws Exception {
        runOnFxThread(() -> {
            Scene scene = scene(new DeletionView(new ControlledDeletionService(),
                    warning -> PermanentDeletionConfirmation.Result.REJECTED));
            RadioButton move = (RadioButton) scene.lookup("#move-deletion-mode");
            RadioButton trash = (RadioButton) scene.lookup("#trash-deletion-mode");
            RadioButton permanent = (RadioButton) scene.lookup("#permanent-deletion-mode");
            TextField destination = destination(scene);
            Button chooser = (Button) scene.lookup("#choose-move-destination");
            TextField sourceRoot = sourceRoot(scene);
            Button rootChooser = (Button) scene.lookup("#choose-move-source-root");

            assertTrue(move.isSelected());
            assertFalse(destination.isDisabled());
            assertFalse(chooser.isDisabled());
            assertFalse(sourceRoot.isDisabled());
            assertFalse(rootChooser.isDisabled());

            trash.setSelected(true);
            assertTrue(destination.isDisabled());
            assertTrue(chooser.isDisabled());
            assertTrue(sourceRoot.isDisabled());
            assertTrue(rootChooser.isDisabled());

            permanent.setSelected(true);
            assertTrue(destination.isDisabled());
            assertTrue(chooser.isDisabled());
            assertTrue(sourceRoot.isDisabled());
            assertTrue(rootChooser.isDisabled());

            move.setSelected(true);
            assertFalse(destination.isDisabled());
            assertFalse(chooser.isDisabled());
            assertFalse(sourceRoot.isDisabled());
            assertFalse(rootChooser.isDisabled());
            return null;
        });
    }

    @Test
    void operazioneInCorsoMantieneDisabilitatiAvvioEControlli(@TempDir Path root) throws Exception {
        ControlledDeletionService service = new ControlledDeletionService();

        runOnFxThread(() -> {
            Scene scene = scene(new DeletionView(service,
                    warning -> PermanentDeletionConfirmation.Result.REJECTED));
            sourceRoot(scene).setText(root.toString());
            destination(scene).setText(root.resolve("destinazione").toString());
            start(scene, root.resolve("elenco.csv"));

            Button action = (Button) scene.lookup("#avvia-cancellazione");
            RadioButton trash = (RadioButton) scene.lookup("#trash-deletion-mode");
            TextField csv = (TextField) scene.lookup("#deletion-manifest");
            assertTrue(action.isDisabled());
            assertTrue(trash.isDisabled());
            assertTrue(csv.isDisabled());

            trash.setSelected(true);
            csv.setText(root.resolve("altro.csv").toString());
            assertTrue(action.isDisabled());
            action.fire();
            return null;
        });

        assertEquals(1, service.starts.get());
    }

    @Test
    void rootVienePropostaDaiMetadatiERestaModificabile(@TempDir Path root) throws Exception {
        ControlledDeletionService service = new ControlledDeletionService();
        Path csv = root.resolve("scansione.csv");
        Path rootRegistrata = root.resolve("archivio-originale");
        Path rootCorretta = root.resolve("archivio-corretto");
        ScanMetadata.write(csv, rootRegistrata);

        runOnFxThread(() -> {
            Scene scene = scene(new DeletionView(service,
                    warning -> PermanentDeletionConfirmation.Result.REJECTED));
            TextField csvField = (TextField) scene.lookup("#deletion-manifest");
            csvField.setText(csv.toString());
            assertEquals(rootRegistrata.toAbsolutePath().normalize().toString(),
                    sourceRoot(scene).getText());

            Path csvSenzaMetadati = root.resolve("vecchio.csv");
            csvField.setText(csvSenzaMetadati.toString());
            assertTrue(sourceRoot(scene).getText().isBlank(),
                    "cambiando CSV non deve restare la root del file precedente");
            sourceRoot(scene).setText(rootCorretta.toString());
            destination(scene).setText(root.resolve("destinazione").toString());
            Button action = (Button) scene.lookup("#avvia-cancellazione");
            assertFalse(action.isDisabled());
            action.fire();
            return null;
        });

        assertEquals(rootCorretta, service.command.get().sourceRoot());
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
        RadioButton move = (RadioButton) scene.lookup("#move-deletion-mode");
        if (move != null && move.isSelected() && sourceRoot(scene).getText().isBlank()) {
            sourceRoot(scene).setText(manifest.getParent().toString());
        }
        Button action = (Button) scene.lookup("#avvia-cancellazione");
        assertNotNull(action);
        assertFalse(action.isDisabled());
        action.fire();
    }

    private static TextField sourceRoot(Scene scene) {
        TextField sourceRoot = (TextField) scene.lookup("#move-source-root");
        assertNotNull(sourceRoot);
        return sourceRoot;
    }

    private static TextField destination(Scene scene) {
        TextField destination = (TextField) scene.lookup("#move-destination");
        assertNotNull(destination);
        return destination;
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
