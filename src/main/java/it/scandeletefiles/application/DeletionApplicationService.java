package it.scandeletefiles.application;

@FunctionalInterface
public interface DeletionApplicationService {
    CancellableOperation start(DeletionCommand command, OperationObserver observer);
}
