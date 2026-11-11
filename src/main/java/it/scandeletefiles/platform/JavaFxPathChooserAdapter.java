package it.scandeletefiles.platform;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

/** Implementazione JavaFX dei dialoghi di selezione percorsi. */
public final class JavaFxPathChooserAdapter implements PathChooserAdapter {

    @Override
    public Optional<Path> chooseDirectory(Path initialDirectory) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Scegli cartella");
        if (initialDirectory != null && initialDirectory.toFile().isDirectory()) {
            chooser.setInitialDirectory(initialDirectory.toFile());
        }
        File selected = chooser.showDialog(null);
        return selected == null ? Optional.empty() : Optional.of(selected.toPath());
    }

    @Override
    public Optional<Path> chooseCsvDestination(Path proposedDestination) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Scegli dove salvare il file CSV");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("File CSV (*.csv)", "*.csv"));
        if (proposedDestination != null) {
            Path parent = proposedDestination.getParent();
            if (parent != null && parent.toFile().isDirectory()) {
                chooser.setInitialDirectory(parent.toFile());
            }
            if (proposedDestination.getFileName() != null) {
                chooser.setInitialFileName(proposedDestination.getFileName().toString());
            }
        }
        File selected = chooser.showSaveDialog(null);
        return selected == null ? Optional.empty() : Optional.of(withCsvExtension(selected.toPath()));
    }

    @Override
    public Optional<Path> chooseCsvFile(Path initialPath) {
        return chooseOpenFile("Scegli il file CSV da elaborare", "File CSV (*.csv)", "*.csv", initialPath);
    }

    @Override
    public Optional<Path> chooseMoveUndoJournal(Path initialPath) {
        return chooseOpenFile("Scegli il registro di ripristino Move",
                "Registro Move (*.undo.csv)", "*.undo.csv", initialPath);
    }

    private static Optional<Path> chooseOpenFile(String title, String description, String pattern,
                                                 Path initialPath) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(description, pattern));
        if (initialPath != null) {
            Path parent = Files.isDirectory(initialPath) ? initialPath : initialPath.getParent();
            if (parent != null && parent.toFile().isDirectory()) {
                chooser.setInitialDirectory(parent.toFile());
            }
        }
        File selected = chooser.showOpenDialog(null);
        return selected == null ? Optional.empty() : Optional.of(selected.toPath());
    }

    private static Path withCsvExtension(Path selected) {
        String value = selected.toString();
        return value.toLowerCase(java.util.Locale.ROOT).endsWith(".csv")
                ? selected : Path.of(value + ".csv");
    }
}
