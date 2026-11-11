package it.scandeletefiles.platform;

import java.nio.file.Path;
import java.util.Optional;

/** Seleziona percorsi locali tramite il dialogo disponibile sulla piattaforma desktop. */
public interface PathChooserAdapter {
    Optional<Path> chooseDirectory(Path initialDirectory);

    Optional<Path> chooseCsvDestination(Path proposedDestination);

    default Optional<Path> chooseCsvFile(Path initialPath) {
        return Optional.empty();
    }

    default Optional<Path> chooseMoveUndoJournal(Path initialPath) {
        return Optional.empty();
    }
}
