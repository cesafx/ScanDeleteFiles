package it.scandeletefiles;

import it.scandeletefiles.bootstrap.ApplicationCompositionRoot;
import it.scandeletefiles.ui.ApplicationShell;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class ScanDeleteFilesApplication extends Application {

    @Override
    public void start(Stage stage) {
        ApplicationCompositionRoot root = ApplicationCompositionRoot.create();
        Scene scene = new Scene(new ApplicationShell(root.navigation()).createView(), 1040, 680);
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
