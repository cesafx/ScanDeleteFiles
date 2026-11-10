package it.scandeletefiles.application;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

public record ScanCommand(
        Path root,
        LocalDate thresholdDate,
        Set<String> extensions,
        boolean includeHidden,
        boolean scanAllFiles,
        Path manifestDestination) {

    public ScanCommand {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(thresholdDate, "thresholdDate");
        extensions = Set.copyOf(Objects.requireNonNull(extensions, "extensions"));
        Objects.requireNonNull(manifestDestination, "manifestDestination");
    }
}
