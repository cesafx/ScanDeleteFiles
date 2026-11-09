package it.scandeletefiles.application;

import java.nio.file.Path;
import java.util.Objects;

public record DeletionCommand(Path manifest, DeletionMode mode, boolean permanentDeletionConfirmed) {
    public DeletionCommand {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(mode, "mode");
    }
}
