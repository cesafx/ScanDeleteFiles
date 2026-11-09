package it.scandeletefiles.application;

@FunctionalInterface
public interface ScanApplicationService {
    CancellableOperation start(ScanCommand command, OperationObserver observer);
}
