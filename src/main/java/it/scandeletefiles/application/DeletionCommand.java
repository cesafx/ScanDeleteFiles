package it.scandeletefiles.application;

import java.nio.file.Path;
import java.util.Objects;

public record DeletionCommand(Path manifest, DeletionMode mode, boolean permanentDeletionConfirmed,
                              Path destination) {
    public DeletionCommand {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(mode, "mode");
        if (mode == DeletionMode.MOVE_TO_FOLDER) {
            Objects.requireNonNull(destination, "destination");
        }
    }

    public DeletionCommand(Path manifest, DeletionMode mode, boolean permanentDeletionConfirmed) {
        this(manifest, mode, permanentDeletionConfirmed, null);
    }
}
