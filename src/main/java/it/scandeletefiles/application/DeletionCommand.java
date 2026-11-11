package it.scandeletefiles.application;

import java.nio.file.Path;
import java.util.Objects;

public record DeletionCommand(Path manifest, DeletionMode mode, boolean permanentDeletionConfirmed,
                              Path destination, Path sourceRoot) {
    public DeletionCommand {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(mode, "mode");
        if (mode == DeletionMode.MOVE_TO_FOLDER) {
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(sourceRoot, "sourceRoot");
        }
    }

    public DeletionCommand(Path manifest, DeletionMode mode, boolean permanentDeletionConfirmed,
                           Path destination) {
        this(manifest, mode, permanentDeletionConfirmed, destination,
                mode == DeletionMode.MOVE_TO_FOLDER ? defaultSourceRoot(manifest) : null);
    }

    public DeletionCommand(Path manifest, DeletionMode mode, boolean permanentDeletionConfirmed) {
        this(manifest, mode, permanentDeletionConfirmed, null, null);
    }

    private static Path defaultSourceRoot(Path manifest) {
        Path absolute = manifest.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        return parent == null ? absolute.getRoot() : parent;
    }
}
