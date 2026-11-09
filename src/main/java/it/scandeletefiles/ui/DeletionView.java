package it.scandeletefiles.ui;

import static it.scandeletefiles.ui.ViewSupport.fieldRow;
import static it.scandeletefiles.ui.ViewSupport.screenContent;
import static it.scandeletefiles.ui.ViewSupport.scrollable;
import static it.scandeletefiles.ui.ViewSupport.section;

import it.scandeletefiles.application.DeletionApplicationService;
import it.scandeletefiles.application.DeletionCommand;
import it.scandeletefiles.application.DeletionMode;
import it.scandeletefiles.application.OperationObserver;
import it.scandeletefiles.application.OperationProgress;
import it.scandeletefiles.application.OperationResult;
import it.scandeletefiles.application.OperationStatus;
import it.scandeletefiles.application.PermanentDeletionConfirmation;
import it.scandeletefiles.navigation.AppScreen;
import java.io.File;
import java.nio.file.Path;
import java.util.Objects;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

public final class DeletionView implements ScreenView {

    private final DeletionApplicationService deletionService;
    private final PermanentDeletionConfirmation permanentDeletionConfirmation;

    private TextField manifest;
    private Button chooseManifest;
    private RadioButton trashMode;
    private RadioButton permanentMode;
    private Button action;
    private Button cancel;
    private Label status;
    private CancellableState currentOperation;

    public DeletionView(DeletionApplicationService deletionService) {
        this(deletionService, DeletionView::showPermanentDeletionConfirmation);
    }

    DeletionView(DeletionApplicationService deletionService,
                 PermanentDeletionConfirmation permanentDeletionConfirmation) {
        this.deletionService = Objects.requireNonNull(deletionService, "deletionService");
        this.permanentDeletionConfirmation = Objects.requireNonNull(
                permanentDeletionConfirmation, "permanentDeletionConfirmation");
    }

    @Override
    public Parent createView() {
        VBox content = screenContent(AppScreen.DELETE);

        manifest = new TextField();
        manifest.setId("deletion-manifest");
        manifest.setPromptText("Manifest CSV revisionato");
        chooseManifest = new Button("Scegli manifest");
        chooseManifest.setId("choose-delete-manifest");
        chooseManifest.setOnAction(event -> chooseManifest());
        HBox manifestRow = fieldRow(manifest, chooseManifest);

        ToggleGroup mode = new ToggleGroup();
        trashMode = new RadioButton("Sposta nel cestino (predefinito)");
        trashMode.setId("trash-deletion-mode");
        trashMode.setToggleGroup(mode);
        trashMode.setSelected(true);
        permanentMode = new RadioButton("Cancella definitivamente");
        permanentMode.setId("permanent-deletion-mode");
        permanentMode.setToggleGroup(mode);

        VBox modes = new VBox(10, trashMode, permanentMode);
        modes.getStyleClass().add("choice-panel");

        action = new Button(AppScreen.DELETE.actionLabel());
        action.setId("avvia-cancellazione");
        action.setDisable(true);
        action.setOnAction(event -> startDeletion());

        cancel = new Button("Annulla");
        cancel.setId("annulla-cancellazione");
        cancel.setDisable(true);
        cancel.setOnAction(event -> {
            if (currentOperation != null) {
                currentOperation.operation().requestCancellation();
            }
        });

        status = new Label();
        status.setId("deletion-status");

        manifest.textProperty().addListener((observable, oldValue, newValue) -> validate());

        content.getChildren().addAll(
                section("Manifest da elaborare", manifestRow),
                section("Modalità", modes),
                new VBox(10, action, cancel, status));
        return scrollable(content);
    }

    private void chooseManifest() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Scegli il manifest CSV da elaborare");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Manifest CSV (*.csv)", "*.csv"));
        Window owner = chooseManifest.getScene() == null ? null : chooseManifest.getScene().getWindow();
        File selected = chooser.showOpenDialog(owner);
        if (selected != null) {
            manifest.setText(selected.toPath().toString());
        }
    }

    private void validate() {
        boolean ready = !manifest.getText().isBlank();
        action.setDisable(!ready);
    }

    private void startDeletion() {
        DeletionMode mode = trashMode.isSelected() ? DeletionMode.TRASH : DeletionMode.PERMANENT;
        boolean confirmed = mode == DeletionMode.PERMANENT
                && permanentDeletionConfirmation.confirm(PermanentDeletionConfirmation.WARNING)
                == PermanentDeletionConfirmation.Result.CONFIRMED;
        if (mode == DeletionMode.PERMANENT && !confirmed) {
            status.setText("Cancellazione permanente non confermata: nessun file eliminato");
            return;
        }
        DeletionCommand command = new DeletionCommand(Path.of(manifest.getText().trim()), mode, confirmed);

        currentOperation = null;
        action.setDisable(true);
        cancel.setDisable(false);
        status.setText("Cancellazione in corso...");

        OperationObserver ui = OperationObserver.dispatching(Platform::runLater, new OperationObserver() {
            @Override
            public void onProgress(OperationProgress progress) {
                status.setText(progress.message());
            }

            @Override
            public void onFinished(OperationResult result) {
                currentOperation = null;
                action.setDisable(false);
                cancel.setDisable(true);
                status.setText(result.message());
            }
        });

        currentOperation = new CancellableState(deletionService.start(command, ui));
    }

    private static PermanentDeletionConfirmation.Result showPermanentDeletionConfirmation(String warning) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Conferma cancellazione permanente");
        alert.setHeaderText("Confermare la cancellazione definitiva?");
        alert.setContentText(warning);
        var selection = alert.showAndWait();
        if (selection.isEmpty()) {
            return PermanentDeletionConfirmation.Result.CLOSED;
        }
        return ButtonType.OK.equals(selection.get())
                ? PermanentDeletionConfirmation.Result.CONFIRMED
                : PermanentDeletionConfirmation.Result.REJECTED;
    }

    private record CancellableState(it.scandeletefiles.application.CancellableOperation operation) {
    }
}
