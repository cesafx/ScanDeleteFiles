package it.scandeletefiles.ui;

import static it.scandeletefiles.ui.ViewSupport.fieldRow;
import static it.scandeletefiles.ui.ViewSupport.pendingChooser;
import static it.scandeletefiles.ui.ViewSupport.screenContent;
import static it.scandeletefiles.ui.ViewSupport.scrollable;
import static it.scandeletefiles.ui.ViewSupport.section;

import it.scandeletefiles.application.CancellableOperation;
import it.scandeletefiles.application.OperationObserver;
import it.scandeletefiles.application.OperationProgress;
import it.scandeletefiles.application.OperationResult;
import it.scandeletefiles.application.OperationStatus;
import it.scandeletefiles.application.ScanApplicationService;
import it.scandeletefiles.application.ScanCommand;
import it.scandeletefiles.navigation.AppScreen;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

public final class ScanView implements ScreenView {
    private static final List<String> DEFAULT_EXTENSIONS = List.of(
            "TXT", "LOG", "PDF", "JPG", "JPEG", "PNG", "GIF", "BMP", "TIF", "TIFF",
            "WEBP", "HEIC", "DOC", "DOCX", "DOCM", "DOT", "DOTX", "DOTM", "RTF", "XLS",
            "XLSX", "XLSM", "XLSB", "XLT", "XLTX", "XLTM", "CSV", "PPT", "PPTX", "PPTM",
            "POT", "POTX", "POTM", "PPS", "PPSX", "PPSM", "ODT", "ODS", "ODP", "ZIP",
            "RAR", "7Z");
    private static final DateTimeFormatter PROPOSED_NAME_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final ScanApplicationService scanService;
    private TextField root;
    private DatePicker threshold;
    private FlowPane extensions;
    private CheckBox includeHidden;
    private TextField destination;
    private Button action;
    private Button cancel;
    private CancellableOperation currentOperation;
    private Label status;

    public ScanView(ScanApplicationService scanService) {
        this.scanService = Objects.requireNonNull(scanService, "scanService");
    }

    @Override
    public Parent createView() {
        VBox content = screenContent(AppScreen.SCAN);

        root = new TextField();
        root.setPromptText("Cartella locale, montata o percorso UNC");
        Button chooseRoot = pendingChooser("Scegli cartella", "choose-scan-root");
        HBox rootRow = fieldRow(root, chooseRoot);

        threshold = new DatePicker();
        threshold.setMaxWidth(Double.MAX_VALUE);

        extensions = new FlowPane(8, 8);
        DEFAULT_EXTENSIONS.stream()
                .map(CheckBox::new)
                .forEach(checkBox -> {
                    checkBox.setSelected(true);
                    extensions.getChildren().add(checkBox);
                });
        extensions.getStyleClass().add("extension-list");

        includeHidden = new CheckBox("Includi file nascosti");
        destination = new TextField();
        destination.setPromptText("Posizione e nome del manifest CSV");
        Button chooseDestination = pendingChooser("Scegli destinazione", "choose-scan-destination");

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

        content.getChildren().addAll(
                section("Cartella da analizzare", rootRow),
                section("Data limite esclusiva", threshold),
                section("Formati da includere", extensions),
                includeHidden,
                section("Manifest CSV", fieldRow(destination, chooseDestination)),
                new VBox(10, action, status));
        return scrollable(content);
    }

    private void validate() {
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
                String suffix = result.status() == OperationStatus.COMPLETED ? " — manifest scritto" : "";
                status.setText(result.message() + suffix);
            }
        });

        currentOperation = scanService.start(command, ui);
    }
}
