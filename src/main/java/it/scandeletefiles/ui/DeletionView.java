package it.scandeletefiles.ui;

import static it.scandeletefiles.ui.ViewSupport.fieldRow;
import static it.scandeletefiles.ui.ViewSupport.pendingAction;
import static it.scandeletefiles.ui.ViewSupport.pendingChooser;
import static it.scandeletefiles.ui.ViewSupport.screenContent;
import static it.scandeletefiles.ui.ViewSupport.scrollable;
import static it.scandeletefiles.ui.ViewSupport.section;

import it.scandeletefiles.navigation.AppScreen;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;

public final class DeletionView implements ScreenView {

    @Override
    public Parent createView() {
        VBox content = screenContent(AppScreen.DELETE);

        TextField manifest = new TextField();
        manifest.setPromptText("Manifest CSV revisionato");
        Button chooseManifest = pendingChooser("Scegli manifest", "choose-delete-manifest");

        ToggleGroup mode = new ToggleGroup();
        RadioButton trash = new RadioButton("Sposta nel cestino (predefinito)");
        trash.setToggleGroup(mode);
        trash.setSelected(true);
        RadioButton permanent = new RadioButton("Cancella definitivamente");
        permanent.setToggleGroup(mode);

        CheckBox confirmation = new CheckBox("Confermo la cancellazione definitiva");
        confirmation.disableProperty().bind(permanent.selectedProperty().not());
        permanent.selectedProperty().addListener((observable, oldValue, selected) -> {
            if (!selected) {
                confirmation.setSelected(false);
            }
        });

        VBox modes = new VBox(10, trash, permanent, confirmation);
        modes.getStyleClass().add("choice-panel");

        content.getChildren().addAll(
                section("Manifest da elaborare", fieldRow(manifest, chooseManifest)),
                section("Modalità", modes),
                pendingAction(AppScreen.DELETE));
        return scrollable(content);
    }
}
