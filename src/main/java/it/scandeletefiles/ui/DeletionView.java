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
import it.scandeletefiles.application.ScanMetadata;
import it.scandeletefiles.navigation.AppScreen;
import it.scandeletefiles.platform.PathChooserAdapter;
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
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

public final class DeletionView implements ScreenView {

    private final DeletionApplicationService deletionService;
    private final PermanentDeletionConfirmation permanentDeletionConfirmation;
    private final PathChooserAdapter pathChooser;

    private TextField manifest;
    private Button chooseManifest;
    private RadioButton trashMode;
    private RadioButton permanentMode;
    private RadioButton moveMode;
    private TextField destination;
    private Button chooseDestination;
    private HBox destinationRow;
    private TextField sourceRoot;
    private Button chooseSourceRoot;
    private HBox sourceRootRow;
    private Parent sourceRootSection;
    private Parent destinationSection;
    private Button action;
    private Button cancel;
    private Label status;
    private CancellableState currentOperation;
    private boolean operationRunning;

    public DeletionView(DeletionApplicationService deletionService) {
        this(deletionService, DeletionView::showPermanentDeletionConfirmation, null);
    }

    DeletionView(DeletionApplicationService deletionService,
                 PermanentDeletionConfirmation permanentDeletionConfirmation) {
        this(deletionService, permanentDeletionConfirmation, null);
    }

    public DeletionView(DeletionApplicationService deletionService,
                 PermanentDeletionConfirmation permanentDeletionConfirmation,
                 PathChooserAdapter pathChooser) {
        this.deletionService = Objects.requireNonNull(deletionService, "deletionService");
        this.permanentDeletionConfirmation = permanentDeletionConfirmation != null
                ? permanentDeletionConfirmation : DeletionView::showPermanentDeletionConfirmation;
        this.pathChooser = pathChooser;
    }

    @Override
    public Parent createView() {
        VBox content = screenContent(AppScreen.DELETE);

        manifest = new TextField();
        manifest.setId("deletion-manifest");
        manifest.setPromptText("File CSV revisionato");
        chooseManifest = new Button("Scegli CSV");
        chooseManifest.setId("choose-delete-manifest");
        chooseManifest.setOnAction(event -> chooseManifest());
        HBox manifestRow = fieldRow(manifest, chooseManifest);

        ToggleGroup mode = new ToggleGroup();
        trashMode = new RadioButton("Sposta nel cestino");
        trashMode.setId("trash-deletion-mode");
        trashMode.setToggleGroup(mode);
        permanentMode = new RadioButton("Cancella definitivamente");
        permanentMode.setId("permanent-deletion-mode");
        permanentMode.setToggleGroup(mode);
        moveMode = new RadioButton("Sposta in cartella (predefinito)");
        moveMode.setId("move-deletion-mode");
        moveMode.setToggleGroup(mode);
        moveMode.setSelected(true);

        sourceRoot = new TextField();
        sourceRoot.setId("move-source-root");
        sourceRoot.setPromptText("Root originale della scansione");
        chooseSourceRoot = new Button("Scegli root");
        chooseSourceRoot.setId("choose-move-source-root");
        chooseSourceRoot.setOnAction(event -> chooseDirectory(sourceRoot,
                "Scegli la cartella root originale"));
        sourceRootRow = fieldRow(sourceRoot, chooseSourceRoot);

        destination = new TextField();
        destination.setId("move-destination");
        destination.setPromptText("Cartella di destinazione");
        chooseDestination = new Button("Scegli cartella");
        chooseDestination.setId("choose-move-destination");
        chooseDestination.setOnAction(event -> chooseDirectory(destination,
                "Scegli la cartella di destinazione"));
        destinationRow = fieldRow(destination, chooseDestination);
        sourceRootSection = section("Root originale", sourceRootRow);
        destinationSection = section("Destinazione", destinationRow);

        VBox modes = new VBox(10, moveMode, trashMode, permanentMode,
                sourceRootSection, destinationSection);
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

        manifest.textProperty().addListener((observable, oldValue, newValue) -> {
            loadSourceRoot(newValue);
            validate();
        });
        sourceRoot.textProperty().addListener((observable, oldValue, newValue) -> validate());
        destination.textProperty().addListener((observable, oldValue, newValue) -> validate());
        mode.selectedToggleProperty().addListener((observable, oldValue, newValue) -> {
            updateDestinationState();
            validate();
        });
        updateDestinationState();

        content.getChildren().addAll(
                section("File CSV da elaborare", manifestRow),
                section("Modalità", modes),
                new VBox(10, action, cancel, status));
        return scrollable(content);
    }

    private void chooseManifest() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Scegli il file CSV da elaborare");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("File CSV (*.csv)", "*.csv"));
        Window owner = chooseManifest.getScene() == null ? null : chooseManifest.getScene().getWindow();
        File selected = chooser.showOpenDialog(owner);
        if (selected != null) {
            manifest.setText(selected.toPath().toString());
        }
    }

    private void validate() {
        boolean ready = !manifest.getText().isBlank();
        if (ready && moveMode.isSelected()) {
            ready = !sourceRoot.getText().isBlank() && !destination.getText().isBlank();
        }
        action.setDisable(operationRunning || !ready);
    }

    private void updateDestinationState() {
        boolean enabled = moveMode.isSelected() && !operationRunning;
        destination.setDisable(!enabled);
        chooseDestination.setDisable(!enabled);
        sourceRoot.setDisable(!enabled);
        chooseSourceRoot.setDisable(!enabled);
        sourceRootSection.setVisible(moveMode.isSelected());
        sourceRootSection.setManaged(moveMode.isSelected());
        destinationSection.setVisible(moveMode.isSelected());
        destinationSection.setManaged(moveMode.isSelected());
    }

    private void updateOperationControls() {
        manifest.setDisable(operationRunning);
        chooseManifest.setDisable(operationRunning);
        trashMode.setDisable(operationRunning);
        permanentMode.setDisable(operationRunning);
        moveMode.setDisable(operationRunning);
        sourceRoot.setDisable(operationRunning || !moveMode.isSelected());
        chooseSourceRoot.setDisable(operationRunning || !moveMode.isSelected());
        updateDestinationState();
        validate();
    }

    private void loadSourceRoot(String csvValue) {
        sourceRoot.clear();
        Path csv = ViewSupport.pathOrNull(csvValue);
        if (csv == null) {
            return;
        }
        ScanMetadata.readRoot(csv).ifPresent(root -> sourceRoot.setText(root.toString()));
    }

    private void chooseDirectory(TextField field, String title) {
        String currentValue = field.getText().trim();
        Path proposed = ViewSupport.pathOrNull(currentValue);
        if (pathChooser != null) {
            pathChooser.chooseDirectory(proposed)
                    .ifPresent(selected -> field.setText(selected.toString()));
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(title);
        if (proposed != null && proposed.toFile().isDirectory()) {
            chooser.setInitialDirectory(proposed.toFile());
        }
        Window owner = field.getScene() == null ? null : field.getScene().getWindow();
        File selected = chooser.showDialog(owner);
        if (selected != null) {
            field.setText(selected.toPath().toString());
        }
    }

    private void startDeletion() {
        DeletionMode mode;
        boolean confirmed = false;
        Path destinationPath = null;
        Path sourceRootPath = null;
        if (trashMode.isSelected()) {
            mode = DeletionMode.TRASH;
        } else if (permanentMode.isSelected()) {
            mode = DeletionMode.PERMANENT;
            confirmed = permanentDeletionConfirmation.confirm(PermanentDeletionConfirmation.WARNING)
                    == PermanentDeletionConfirmation.Result.CONFIRMED;
            if (!confirmed) {
                status.setText("Cancellazione permanente non confermata: nessun file eliminato");
                return;
            }
        } else {
            mode = DeletionMode.MOVE_TO_FOLDER;
            sourceRootPath = ViewSupport.pathOrNull(sourceRoot.getText().trim());
            if (sourceRootPath == null) {
                status.setText("Root originale non valida: controlla il percorso digitato");
                return;
            }
            destinationPath = ViewSupport.pathOrNull(destination.getText().trim());
            if (destinationPath == null) {
                status.setText("Cartella di destinazione non valida: controlla il percorso digitato");
                return;
            }
        }
        Path manifestPath = ViewSupport.pathOrNull(manifest.getText().trim());
        if (manifestPath == null) {
            status.setText("File CSV non valido: controlla il percorso digitato");
            return;
        }
        DeletionCommand command = new DeletionCommand(
                manifestPath, mode, confirmed, destinationPath, sourceRootPath);

        currentOperation = null;
        operationRunning = true;
        updateOperationControls();
        cancel.setDisable(false);
        status.setText("Operazione in corso...");

        OperationObserver ui = OperationObserver.dispatching(Platform::runLater, new OperationObserver() {
            @Override
            public void onProgress(OperationProgress progress) {
                status.setText(progress.message());
            }

            @Override
            public void onFinished(OperationResult result) {
                currentOperation = null;
                operationRunning = false;
                updateOperationControls();
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
