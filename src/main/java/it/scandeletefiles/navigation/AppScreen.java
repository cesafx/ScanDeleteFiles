package it.scandeletefiles.navigation;

public enum AppScreen {
    SCAN(
            "Scansione",
            "Crea un manifest CSV dei file che corrispondono ai criteri scelti.",
            "Avvia scansione"),
    DELETE(
            "Cancellazione",
            "Controlla un manifest revisionato e tratta soltanto le righe approvate.",
            "Avvia cancellazione");

    private final String title;
    private final String description;
    private final String actionLabel;

    AppScreen(String title, String description, String actionLabel) {
        this.title = title;
        this.description = description;
        this.actionLabel = actionLabel;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public String actionLabel() {
        return actionLabel;
    }
}
