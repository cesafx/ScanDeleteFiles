package it.scandeletefiles.bootstrap;

import it.scandeletefiles.application.DeletionApplicationService;
import it.scandeletefiles.application.ScanApplicationService;
import java.util.Objects;

public record ApplicationServices(ScanApplicationService scan, DeletionApplicationService deletion) {
    public ApplicationServices {
        Objects.requireNonNull(scan, "scan");
        Objects.requireNonNull(deletion, "deletion");
    }

    static ApplicationServices pendingImplementation() {
        ScanApplicationService scan = (command, observer) -> {
            throw new UnsupportedOperationException("La scansione non è ancora implementata");
        };
        DeletionApplicationService deletion = (command, observer) -> {
            throw new UnsupportedOperationException("La cancellazione non è ancora implementata");
        };
        return new ApplicationServices(scan, deletion);
    }
}
