package it.scandeletefiles.ui;

import it.scandeletefiles.navigation.AppScreen;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import java.nio.file.Path;

final class ViewSupport {
    private ViewSupport() {}

    static VBox screenContent(AppScreen screen) {
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

    static Parent pendingAction(AppScreen screen) {
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

    static Parent section(String title, Parent content) {
        Label label = new Label(title);
        label.getStyleClass().add("field-label");
        return new VBox(8, label, content);
    }

    static HBox fieldRow(TextField field, Button button) {
        HBox.setHgrow(field, Priority.ALWAYS);
        field.setMaxWidth(Double.MAX_VALUE);
        HBox row = new HBox(10, field, button);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    static Parent scrollable(VBox content) {
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("content-scroll");
        return scrollPane;
    }

    static Button pendingChooser(String label, String id) {
        Button button = new Button(label);
        button.setId(id);
        button.setDisable(true);
        return button;
    }

    static Path pathOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Path.of(value.trim()).normalize();
        } catch (RuntimeException invalidPath) {
            return null;
        }
    }
}
