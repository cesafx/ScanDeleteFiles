package it.scandeletefiles.application.move;

import it.scandeletefiles.application.CancellableOperation;
import it.scandeletefiles.application.OperationObserver;

@FunctionalInterface
public interface MoveUndoApplicationService {
    CancellableOperation start(MoveUndoCommand command, OperationObserver observer);
}
