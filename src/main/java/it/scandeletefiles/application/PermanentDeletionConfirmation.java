package it.scandeletefiles.application;

@FunctionalInterface
public interface PermanentDeletionConfirmation {

    enum Result {
        CONFIRMED,
        REJECTED,
        CLOSED
    }

    String WARNING = "La cancellazione permanente è irreversibile. I file non potranno essere recuperati dal cestino.";

    Result confirm(String warning);
}
