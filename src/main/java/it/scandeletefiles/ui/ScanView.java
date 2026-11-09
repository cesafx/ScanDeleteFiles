package it.scandeletefiles.ui;

import static it.scandeletefiles.ui.ViewSupport.fieldRow;
import static it.scandeletefiles.ui.ViewSupport.pendingAction;
import static it.scandeletefiles.ui.ViewSupport.pendingChooser;
import static it.scandeletefiles.ui.ViewSupport.screenContent;
import static it.scandeletefiles.ui.ViewSupport.scrollable;
import static it.scandeletefiles.ui.ViewSupport.section;

import it.scandeletefiles.navigation.AppScreen;
import java.util.List;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public final class ScanView implements ScreenView {
    private static final List<String> DEFAULT_EXTENSIONS = List.of(
            "TXT", "LOG", "PDF", "JPG", "JPEG", "PNG", "GIF", "BMP", "TIF", "TIFF",
            "WEBP", "HEIC", "DOC", "DOCX", "DOCM", "DOT", "DOTX", "DOTM", "RTF", "XLS",
            "XLSX", "XLSM", "XLSB", "XLT", "XLTX", "XLTM", "CSV", "PPT", "PPTX", "PPTM",
            "POT", "POTX", "POTM", "PPS", "PPSX", "PPSM", "ODT", "ODS", "ODP", "ZIP",
            "RAR", "7Z");

    @Override
    public Parent createView() {
        VBox content = screenContent(AppScreen.SCAN);

        TextField root = new TextField();
        root.setPromptText("Cartella locale, montata o percorso UNC");
        Button chooseRoot = pendingChooser("Scegli cartella", "choose-scan-root");
        HBox rootRow = fieldRow(root, chooseRoot);

        DatePicker threshold = new DatePicker();
        threshold.setMaxWidth(Double.MAX_VALUE);

        FlowPane extensions = new FlowPane(8, 8);
        DEFAULT_EXTENSIONS.stream()
                .map(CheckBox::new)
                .forEach(checkBox -> {
                    checkBox.setSelected(true);
                    extensions.getChildren().add(checkBox);
                });
        extensions.getStyleClass().add("extension-list");

        CheckBox includeHidden = new CheckBox("Includi file nascosti");
        TextField destination = new TextField();
        destination.setPromptText("Posizione e nome del manifest CSV");
        Button chooseDestination = pendingChooser("Scegli destinazione", "choose-scan-destination");

        content.getChildren().addAll(
                section("Cartella da analizzare", rootRow),
                section("Data limite esclusiva", threshold),
                section("Formati da includere", extensions),
                includeHidden,
                section("Manifest CSV", fieldRow(destination, chooseDestination)),
                pendingAction(AppScreen.SCAN));
        return scrollable(content);
    }
}
