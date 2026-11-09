package it.scandeletefiles;

import it.scandeletefiles.bootstrap.ApplicationCompositionRoot;
import java.util.Locale;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class ScanDeleteFilesApplication extends Application {

    @Override
    public void init() {
        Locale.setDefault(Locale.ITALIAN);
        Locale.setDefault(Locale.Category.DISPLAY, Locale.ITALIAN);
        Locale.setDefault(Locale.Category.FORMAT, Locale.ITALIAN);
    }

    @Override
    public void start(Stage stage) {
        ApplicationCompositionRoot root = ApplicationCompositionRoot.create();
        Scene scene = new Scene(root.createView(), 1040, 680);
        scene.getStylesheets().add(
                ScanDeleteFilesApplication.class.getResource("/it/scandeletefiles/application.css").toExternalForm());

        stage.setTitle("ScanDeleteFiles");
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
