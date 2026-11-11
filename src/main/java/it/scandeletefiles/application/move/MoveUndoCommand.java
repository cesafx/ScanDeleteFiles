package it.scandeletefiles.application.move;

import java.nio.file.Path;
import java.util.Objects;

public record MoveUndoCommand(Path journal) {
    public MoveUndoCommand {
        Objects.requireNonNull(journal, "journal");
        String name = journal.getFileName() == null ? ""
                : journal.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        boolean validName = name.equals("moving.undo.csv")
                || name.startsWith("moving_") && name.endsWith(".undo.csv");
        if (!validName) {
            throw new IllegalArgumentException(
                    "Il registro deve avere nome moving.undo.csv o moving_<suffisso>.undo.csv");
        }
    }
}
