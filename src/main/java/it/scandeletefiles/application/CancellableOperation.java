package it.scandeletefiles.application;

public interface CancellableOperation {
    void requestCancellation();

    boolean isCancellationRequested();
}
