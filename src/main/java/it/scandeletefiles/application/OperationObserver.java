package it.scandeletefiles.application;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Receives operation callbacks on the thread chosen by the service implementation.
 * Consumers with thread affinity must wrap the observer with {@link #dispatching}.
 */
public interface OperationObserver {
    void onProgress(OperationProgress progress);

    void onFinished(OperationResult result);

    static OperationObserver dispatching(Executor executor, OperationObserver delegate) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(delegate, "delegate");
        return new OperationObserver() {
            @Override
            public void onProgress(OperationProgress progress) {
                executor.execute(() -> delegate.onProgress(progress));
            }

            @Override
            public void onFinished(OperationResult result) {
                executor.execute(() -> delegate.onFinished(result));
            }
        };
    }
}
