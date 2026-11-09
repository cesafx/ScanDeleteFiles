package it.scandeletefiles.ui;

import it.scandeletefiles.navigation.AppScreen;
import it.scandeletefiles.navigation.NavigationModel;
import java.util.Objects;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public final class ApplicationShell {
    private final NavigationModel navigation;
    private final ScreenView scanView;
    private final ScreenView deletionView;
    private final BorderPane shell = new BorderPane();
    private final Button scanNavigation = navigationButton("Scansione", "scan-navigation");
    private final Button deleteNavigation = navigationButton("Cancellazione", "delete-navigation");

    public ApplicationShell(NavigationModel navigation, ScreenView scanView, ScreenView deletionView) {
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        this.scanView = Objects.requireNonNull(scanView, "scanView");
        this.deletionView = Objects.requireNonNull(deletionView, "deletionView");
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
        shell.setCenter(screen == AppScreen.SCAN ? scanView.createView() : deletionView.createView());
    }

    private static Button navigationButton(String text, String id) {
        Button button = new Button(text);
        button.setId(id);
        button.setMaxWidth(Double.MAX_VALUE);
        button.getStyleClass().add("navigation-button");
        return button;
    }
}
