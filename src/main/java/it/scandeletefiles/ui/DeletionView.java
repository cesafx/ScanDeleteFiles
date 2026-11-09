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
import it.scandeletefiles.navigation.AppScreen;
import java.io.File;
import java.nio.file.Path;
import java.util.Objects;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
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

    private TextField manifest;
    private Button chooseManifest;
    private RadioButton trashMode;
    private RadioButton permanentMode;
    private CheckBox confirmation;
    private Button action;
    private Button cancel;
    private Label status;
    private CancellableState currentOperation;

    public DeletionView(DeletionApplicationService deletionService) {
        this.deletionService = Objects.requireNonNull(deletionService, "deletionService");
    }

    @Override
    public Parent createView() {
        VBox content = screenContent(AppScreen.DELETE);

        manifest = new TextField();
        manifest.setPromptText("Manifest CSV revisionato");
        chooseManifest = new Button("Scegli manifest");
        chooseManifest.setOnAction(event -> chooseManifest());
        HBox manifestRow = fieldRow(manifest, chooseManifest);

        ToggleGroup mode = new ToggleGroup();
        trashMode = new RadioButton("Sposta nel cestino (predefinito)");
        trashMode.setToggleGroup(mode);
        trashMode.setSelected(true);
        permanentMode = new RadioButton("Cancella definitivamente");
        permanentMode.setToggleGroup(mode);
        confirmation = new CheckBox("Confermo la cancellazione definitiva");
        confirmation.setDisable(true);
        permanentMode.selectedProperty().addListener((observable, oldValue, selected) -> {
            confirmation.setDisable(!selected);
            if (!selected) {
                confirmation.setSelected(false);
            }
            validate();
        });
        confirmation.selectedProperty().addListener((observable, oldValue, selected) -> validate());

        VBox modes = new VBox(10, trashMode, permanentMode, confirmation);
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
        boolean ready = !manifest.getText().isBlank()
                && !(permanentMode.isSelected() && !confirmation.isSelected());
        action.setDisable(!ready);
    }

    private void startDeletion() {
        DeletionMode mode = trashMode.isSelected() ? DeletionMode.TRASH : DeletionMode.PERMANENT;
        boolean confirmed = permanentMode.isSelected() && confirmation.isSelected();
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

    private record CancellableState(it.scandeletefiles.application.CancellableOperation operation) {
    }
}
