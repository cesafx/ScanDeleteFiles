package it.scandeletefiles.ui;

import it.scandeletefiles.navigation.AppScreen;
import it.scandeletefiles.navigation.NavigationModel;
import java.util.List;
import java.util.Objects;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public final class ApplicationShell {
    private static final List<String> DEFAULT_EXTENSIONS = List.of(
            "TXT", "LOG", "PDF", "JPG", "JPEG", "PNG", "GIF", "BMP", "TIF", "TIFF",
            "WEBP", "HEIC", "DOC", "DOCX", "DOCM", "DOT", "DOTX", "DOTM", "RTF", "XLS",
            "XLSX", "XLSM", "XLSB", "XLT", "XLTX", "XLTM", "CSV", "PPT", "PPTX", "PPTM",
            "POT", "POTX", "POTM", "PPS", "PPSX", "PPSM", "ODT", "ODS", "ODP", "ZIP",
            "RAR", "7Z");

    private final NavigationModel navigation;
    private final BorderPane shell = new BorderPane();
    private final Button scanNavigation = navigationButton("Scansione", "scan-navigation");
    private final Button deleteNavigation = navigationButton("Cancellazione", "delete-navigation");

    public ApplicationShell(NavigationModel navigation) {
        this.navigation = Objects.requireNonNull(navigation, "navigation");
    }

    public Parent createView() {
        shell.getStyleClass().add("application-shell");
        shell.setLeft(createSidebar());
        navigation.addListener(this::showScreen);
        showScreen(navigation.currentScreen());
        return shell;
    }

    private Parent createSidebar() {
        Label product = new Label("ScanDeleteFiles");
        product.getStyleClass().add("product-name");

        Label subtitle = new Label("Gestione controllata dei file");
        subtitle.getStyleClass().add("product-subtitle");

        scanNavigation.setOnAction(event -> navigation.navigateTo(AppScreen.SCAN));
        deleteNavigation.setOnAction(event -> navigation.navigateTo(AppScreen.DELETE));

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Label safety = new Label("Nessuna operazione sui file è attiva in questa versione.");
        safety.getStyleClass().add("sidebar-note");
        safety.setWrapText(true);

        VBox sidebar = new VBox(8, product, subtitle, new Separator(), scanNavigation, deleteNavigation, spacer, safety);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPadding(new Insets(28));
        return sidebar;
    }

    private void showScreen(AppScreen screen) {
        scanNavigation.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("selected"), screen == AppScreen.SCAN);
        deleteNavigation.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("selected"), screen == AppScreen.DELETE);
        shell.setCenter(screen == AppScreen.SCAN ? createScanScreen() : createDeletionScreen());
    }

    private Parent createScanScreen() {
        VBox content = screenHeader(AppScreen.SCAN);

        TextField root = new TextField();
        root.setPromptText("Cartella locale, montata o percorso UNC");
        Button chooseRoot = new Button("Scegli cartella");
        HBox rootRow = fieldRow(root, chooseRoot);

        DatePicker threshold = new DatePicker();
        threshold.setMaxWidth(Double.MAX_VALUE);

        FlowPane extensions = new FlowPane(8, 8);
        DEFAULT_EXTENSIONS.stream()
                .map(extension -> new CheckBox(extension))
                .forEach(checkBox -> {
                    checkBox.setSelected(true);
                    extensions.getChildren().add(checkBox);
                });
        extensions.getStyleClass().add("extension-list");

        CheckBox includeHidden = new CheckBox("Includi file nascosti");
        TextField destination = new TextField();
        destination.setPromptText("Posizione e nome del manifest CSV");
        Button chooseDestination = new Button("Scegli destinazione");

        content.getChildren().addAll(
                section("Cartella da analizzare", rootRow),
                section("Data limite esclusiva", threshold),
                section("Formati da includere", extensions),
                includeHidden,
                section("Manifest CSV", fieldRow(destination, chooseDestination)),
                pendingAction(AppScreen.SCAN));
        return scrollableContent(content);
    }

    private Parent createDeletionScreen() {
        VBox content = screenHeader(AppScreen.DELETE);

        TextField manifest = new TextField();
        manifest.setPromptText("Manifest CSV revisionato");
        Button chooseManifest = new Button("Scegli manifest");

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
        return scrollableContent(content);
    }

    private VBox screenHeader(AppScreen screen) {
        Label eyebrow = new Label("OPERAZIONE");
        eyebrow.getStyleClass().add("eyebrow");

        Label title = new Label(screen.title());
        title.setId("screen-title");
        title.getStyleClass().add("screen-title");

        Label description = new Label(screen.description());
        description.getStyleClass().add("screen-description");
        description.setWrapText(true);

        VBox content = new VBox(22, eyebrow, title, description);
        content.getStyleClass().add("screen-content");
        content.setPadding(new Insets(42, 52, 52, 52));
        return content;
    }

    private Parent pendingAction(AppScreen screen) {
        Button action = new Button(screen.actionLabel());
        action.getStyleClass().add("primary-action");
        action.setDisable(true);

        Label note = new Label("Il motore operativo sarà collegato nei prossimi passaggi di sviluppo.");
        note.getStyleClass().add("pending-note");
        note.setWrapText(true);

        HBox row = new HBox(14, action, note);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Parent section(String title, Parent content) {
        Label label = new Label(title);
        label.getStyleClass().add("field-label");
        VBox section = new VBox(8, label, content);
        return section;
    }

    private HBox fieldRow(TextField field, Button button) {
        HBox.setHgrow(field, Priority.ALWAYS);
        field.setMaxWidth(Double.MAX_VALUE);
        HBox row = new HBox(10, field, button);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Parent scrollableContent(VBox content) {
        javafx.scene.control.ScrollPane scrollPane = new javafx.scene.control.ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("content-scroll");
        return scrollPane;
    }

    private static Button navigationButton(String text, String id) {
        Button button = new Button(text);
        button.setId(id);
        button.setMaxWidth(Double.MAX_VALUE);
        button.getStyleClass().add("navigation-button");
        return button;
    }
}
