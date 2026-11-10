package it.scandeletefiles.platform;

import java.nio.file.Path;
import java.util.Optional;

/** Seleziona percorsi locali tramite il dialogo disponibile sulla piattaforma desktop. */
public interface PathChooserAdapter {
    Optional<Path> chooseDirectory(Path initialDirectory);

    Optional<Path> chooseCsvDestination(Path proposedDestination);
}
