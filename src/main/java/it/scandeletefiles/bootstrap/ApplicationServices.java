package it.scandeletefiles.bootstrap;

import it.scandeletefiles.application.DeletionApplicationService;
import it.scandeletefiles.application.DeletionEngine;
import it.scandeletefiles.application.ScanApplicationService;
import it.scandeletefiles.application.scan.ScanEngine;
import it.scandeletefiles.application.move.MoveUndoApplicationService;
import it.scandeletefiles.application.move.MoveUndoEngine;
import java.util.Objects;

public record ApplicationServices(ScanApplicationService scan, DeletionApplicationService deletion,
                                  MoveUndoApplicationService moveUndo) {
    public ApplicationServices {
        Objects.requireNonNull(scan, "scan");
        Objects.requireNonNull(deletion, "deletion");
        Objects.requireNonNull(moveUndo, "moveUndo");
    }

    static ApplicationServices pendingImplementation() {
        ScanApplicationService scan = (command, observer) -> {
            throw new UnsupportedOperationException("La scansione non è ancora implementata");
        };
        DeletionApplicationService deletion = (command, observer) -> {
            throw new UnsupportedOperationException("La cancellazione non è ancora implementata");
        };
        MoveUndoApplicationService moveUndo = (command, observer) -> {
            throw new UnsupportedOperationException("Il ripristino Move non è ancora implementato");
        };
        return new ApplicationServices(scan, deletion, moveUndo);
    }

    static ApplicationServices create(PlatformAdapters platform) {
        Objects.requireNonNull(platform, "platform");
        return new ApplicationServices(
                new ScanEngine(platform),
                new DeletionEngine(platform.trash(), platform.clock()),
                new MoveUndoEngine(platform.clock()));
    }
}
