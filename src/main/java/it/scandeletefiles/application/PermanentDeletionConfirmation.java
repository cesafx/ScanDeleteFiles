package it.scandeletefiles.application;

@FunctionalInterface
public interface PermanentDeletionConfirmation {

    String WARNING = "La cancellazione permanente è irreversibile. I file non potranno essere recuperati dal cestino.";

    boolean confirm();
}
