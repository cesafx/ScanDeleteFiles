package it.scandeletefiles.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class OperationObserverTest {

    @Test
    void dispatchesCallbacksThroughTheConfiguredExecutor() {
        Queue<Runnable> dispatched = new ArrayDeque<>();
        List<String> received = new ArrayList<>();
        OperationObserver delegate = new OperationObserver() {
            @Override
            public void onProgress(OperationProgress progress) {
                received.add("progress:" + progress.completed());
            }

            @Override
            public void onFinished(OperationResult result) {
                received.add("finished:" + result.status());
            }
        };
        OperationObserver observer = OperationObserver.dispatching(dispatched::add, delegate);

        observer.onProgress(OperationProgress.indeterminate(4, "In corso"));
        observer.onFinished(new OperationResult(OperationStatus.COMPLETED, "Completata"));

        assertEquals(List.of(), received);
        dispatched.remove().run();
        dispatched.remove().run();
        assertEquals(List.of("progress:4", "finished:COMPLETED"), received);
    }
}
