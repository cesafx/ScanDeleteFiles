package it.scandeletefiles.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import it.scandeletefiles.application.CancellableOperation;
import it.scandeletefiles.application.ExtensionPreferences;
import it.scandeletefiles.platform.PathChooserAdapter;
import it.scandeletefiles.platform.SettingsAdapter;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class ScanPathChooserUiTest {

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
    void iPulsantiScegliCompilanoCartellaEDestinazione(@TempDir Path root) throws Exception {
        Path destination = root.resolve("manifest-personalizzato.csv");
        PathChooserAdapter chooser = new PathChooserAdapter() {
            @Override
            public Optional<Path> chooseDirectory(Path initialDirectory) {
                return Optional.of(root);
            }

            @Override
            public Optional<Path> chooseCsvDestination(Path proposedDestination) {
                return Optional.of(destination);
            }
        };

        runOnFxThread(() -> {
            ScanView view = new ScanView((command, observer) -> NO_OP, preferences(), chooser);
            Parent parent = view.createView();
            Scene scene = new Scene(parent);
            parent.applyCss();
            parent.layout();

            Button chooseRoot = (Button) scene.lookup("#choose-scan-root");
            Button chooseDestination = (Button) scene.lookup("#choose-scan-destination");
            TextField rootField = (TextField) scene.lookup("#scan-root");
            TextField destinationField = (TextField) scene.lookup("#scan-destination");
            assertNotNull(chooseRoot);
            assertNotNull(chooseDestination);
            assertNotNull(rootField);
            assertNotNull(destinationField);

            chooseRoot.fire();
            assertEquals(root.toString(), rootField.getText());
            chooseDestination.fire();
            assertEquals(destination.toString(), destinationField.getText());
            return null;
        });
    }

    private static ExtensionPreferences preferences() {
        return new ExtensionPreferences(new SettingsAdapter() {
            @Override
            public Set<String> loadSelectedExtensions() {
                return Set.of("TXT");
            }

            @Override
            public void saveSelectedExtensions(Set<String> extensions) {
            }
        });
    }

    private static <T> T runOnFxThread(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        Platform.runLater(task);
        return task.get();
    }
}
