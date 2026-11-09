package it.scandeletefiles.application;

public interface OperationObserver {
    void onProgress(OperationProgress progress);

    void onFinished(OperationResult result);
}
