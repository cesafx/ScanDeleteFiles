package it.scandeletefiles.ui;

import it.scandeletefiles.application.OperationObserver;
import java.util.Objects;
import java.util.concurrent.Executor;
import javafx.application.Platform;

public final class JavaFxOperationObservers {
    private static final Executor JAVAFX_EXECUTOR = command -> {
        if (Platform.isFxApplicationThread()) {
            command.run();
        } else {
            Platform.runLater(command);
        }
    };

    private JavaFxOperationObservers() {}

    /**
     * Returns an observer whose callbacks always run on the JavaFX Application Thread.
     */
    public static OperationObserver onApplicationThread(OperationObserver delegate) {
        return OperationObserver.dispatching(JAVAFX_EXECUTOR, Objects.requireNonNull(delegate, "delegate"));
    }
}
