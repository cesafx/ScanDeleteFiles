package it.scandeletefiles.ui;

import static it.scandeletefiles.ui.ViewSupport.fieldRow;
import static it.scandeletefiles.ui.ViewSupport.screenContent;
import static it.scandeletefiles.ui.ViewSupport.scrollable;
import static it.scandeletefiles.ui.ViewSupport.section;

import it.scandeletefiles.application.CancellableOperation;
import it.scandeletefiles.application.ExtensionPreferences;
import it.scandeletefiles.application.OperationObserver;
import it.scandeletefiles.application.OperationProgress;
import it.scandeletefiles.application.OperationResult;
import it.scandeletefiles.application.OperationStatus;
import it.scandeletefiles.application.ScanApplicationService;
import it.scandeletefiles.application.ScanCommand;
import it.scandeletefiles.navigation.AppScreen;
import it.scandeletefiles.platform.PathChooserAdapter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

public final class ScanView implements ScreenView {
    private static final List<String> DEFAULT_EXTENSIONS = List.of(
            "TXT", "LOG", "PDF", "JPG", "JPEG", "PNG", "GIF", "BMP", "TIF", "TIFF",
            "WEBP", "HEIC", "DOC", "DOCX", "DOCM", "DOT", "DOTX", "DOTM", "RTF", "XLS",
            "XLSX", "XLSM", "XLSB", "XLT", "XLTX", "XLTM", "CSV", "PPT", "PPTX", "PPTM",
            "POT", "POTX", "POTM", "PPS", "PPSX", "PPSM", "ODT", "ODS", "ODP", "ZIP",
            "RAR", "7Z");
    private static final DateTimeFormatter PROPOSED_NAME_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final List<DateTimeFormatter> ACCEPTED_DATES = List.of(
            DISPLAY_DATE,
            DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu/MM/dd").withResolverStyle(ResolverStyle.STRICT));

    private final ScanApplicationService scanService;
    private final ExtensionPreferences extensionsPrefs;
    private final PathChooserAdapter pathChooser;
    private TextField root;
    private DatePicker threshold;
    private FlowPane extensions;
    private CheckBox includeHidden;
    private CheckBox scanAllFiles;
    private TextField destination;
    private Button action;
    private Button cancel;
    private CancellableOperation currentOperation;
    private Label status;

    public ScanView(ScanApplicationService scanService, ExtensionPreferences extensionsPrefs,
                    PathChooserAdapter pathChooser) {
        this.scanService = Objects.requireNonNull(scanService, "scanService");
        this.extensionsPrefs = Objects.requireNonNull(extensionsPrefs, "extensionsPrefs");
        this.pathChooser = Objects.requireNonNull(pathChooser, "pathChooser");
    }

    @Override
    public Parent createView() {
        VBox content = screenContent(AppScreen.SCAN);

        root = new TextField();
        root.setId("scan-root");
        root.setPromptText("Cartella locale, montata o percorso UNC");
        Button chooseRoot = new Button("Scegli cartella");
        chooseRoot.setId("choose-scan-root");
        chooseRoot.setOnAction(event -> chooseRootFolder());
        HBox rootRow = fieldRow(root, chooseRoot);

        threshold = new DatePicker();
        threshold.setId("scan-threshold");
        threshold.setConverter(new ThresholdDateConverter());
        threshold.setOnShowing(event -> commitThresholdEditor());
        threshold.setMaxWidth(Double.MAX_VALUE);

        extensions = new FlowPane(8, 8);
        Set<String> persisted = extensionsPrefs.selectedExtensions();
        DEFAULT_EXTENSIONS.stream()
                .map(extension -> {
                    CheckBox checkBox = new CheckBox(extension);
                    checkBox.setSelected(persisted.contains(extension));
                    checkBox.setOnAction(event -> persistSelection());
                    return checkBox;
                })
                .forEach(checkBox -> extensions.getChildren().add(checkBox));
        extensions.getStyleClass().add("extension-list");

        includeHidden = new CheckBox("Includi file nascosti");
        scanAllFiles = new CheckBox("Scansiona tutti i file (ignora filtri formati)");
        scanAllFiles.setSelected(true);
        extensions.setVisible(false);
        extensions.setManaged(false);
        destination = new TextField();
        destination.setId("scan-destination");
        destination.setPromptText("Posizione e nome del file CSV");
        Button chooseDestination = new Button("Scegli destinazione");
        chooseDestination.setId("choose-scan-destination");
        chooseDestination.setOnAction(event -> chooseCsvDestination());

        action = new Button(AppScreen.SCAN.actionLabel());
        action.setDisable(true);
        action.setId("avvia-scansione");
        action.setOnAction(event -> startScan());

        cancel = new Button("Annulla");
        cancel.setDisable(true);
        cancel.setId("annulla-scansione");
        cancel.setOnAction(event -> {
            if (currentOperation != null) {
                currentOperation.requestCancellation();
            }
        });

        status = new Label();
        status.setId("scan-status");

        root.textProperty().addListener((observable, previousValue, newValue) -> {
            if (destination.getText().isEmpty() && !newValue.isBlank()) {
                destination.setText(proposedDestination(newValue));
            }
            validate();
        });
        threshold.valueProperty().addListener((observable, previousValue, newValue) -> validate());
        destination.textProperty().addListener((observable, previousValue, newValue) -> validate());
        scanAllFiles.selectedProperty().addListener((observable, previousValue, newValue) -> {
            boolean showExtensions = !newValue;
            extensions.setVisible(showExtensions);
            extensions.setManaged(showExtensions);
        });

        content.getChildren().addAll(
                section("Cartella da analizzare", rootRow),
                section("Data limite esclusiva", threshold),
                section("Formati da includere", extensions),
                includeHidden,
                scanAllFiles,
                section("File CSV di output", fieldRow(destination, chooseDestination)),
                new VBox(10, action, status));
        return scrollable(content);
    }

    private void chooseRootFolder() {
        pathChooser.chooseDirectory(ViewSupport.pathOrNull(root.getText()))
                .ifPresent(selected -> root.setText(selected.toString()));
    }

    private void chooseCsvDestination() {
        pathChooser.chooseCsvDestination(ViewSupport.pathOrNull(destination.getText()))
                .ifPresent(selected -> destination.setText(selected.toString()));
    }

    private void commitThresholdEditor() {
        String text = threshold.getEditor().getText();
        try {
            LocalDate parsed = threshold.getConverter().fromString(text);
            if (parsed != null) {
                threshold.setValue(parsed);
            }
        } catch (RuntimeException invalidDate) {
            // Una data non valida resta nel campo per consentire all'utente di correggerla.
        }
    }

    private static Path pathOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Path.of(value.trim());
        } catch (RuntimeException invalidPath) {
            return null;
        }
    }


    private void validate() {
        commitThresholdEditor();
        boolean ready = !root.getText().isBlank()
                && threshold.getValue() != null
                && !destination.getText().isBlank();
        action.setDisable(!ready);
    }

    private String proposedDestination(String rootText) {
        String stamp = LocalDateTime.now(Clock.systemDefaultZone()).format(PROPOSED_NAME_TIMESTAMP);
        return Path.of(rootText, "scansione_" + stamp + ".csv").toString();
    }

    private void startScan() {
        Set<String> selectedExtensions = extensions.getChildren().stream()
                .map(CheckBox.class::cast)
                .filter(CheckBox::isSelected)
                .map(checkBox -> checkBox.getText().toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());

        ScanCommand command = new ScanCommand(
                Path.of(root.getText()),
                threshold.getValue(),
                selectedExtensions,
                includeHidden.isSelected(),
                scanAllFiles.isSelected(),
                Path.of(destination.getText()));

        currentOperation = null;
        action.setDisable(true);
        cancel.setDisable(false);
        status.setText("Scansione in corso...");

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
                String suffix = result.status() == OperationStatus.COMPLETED ? " — file CSV scritto" : "";
                status.setText(result.message() + suffix);
            }
        });

        currentOperation = scanService.start(command, ui);
    }

    private void persistSelection() {
        Set<String> selected = extensions.getChildren().stream()
                .map(CheckBox.class::cast)
                .filter(CheckBox::isSelected)
                .map(checkBox -> checkBox.getText().toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        extensionsPrefs.replaceSelection(selected);
    }

    private static final class ThresholdDateConverter extends StringConverter<LocalDate> {
        @Override
        public String toString(LocalDate date) {
            return date == null ? "" : DISPLAY_DATE.format(date);
        }

        @Override
        public LocalDate fromString(String text) {
            if (text == null || text.isBlank()) {
                return null;
            }
            String value = text.trim();
            for (DateTimeFormatter formatter : ACCEPTED_DATES) {
                try {
                    return LocalDate.parse(value, formatter);
                } catch (DateTimeParseException ignored) {
                    // Prova il formato successivo.
                }
            }
            throw new IllegalArgumentException("Data non valida: " + text);
        }
    }
}
