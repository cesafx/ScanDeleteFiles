package it.scandeletefiles.navigation;

public enum AppScreen {
    SCAN(
            "Scansione",
            "Crea un file CSV con i file che corrispondono ai criteri scelti.",
            "Avvia scansione"),
    DELETE(
            "Move/Delete",
            "Elabora il CSV revisionato e tratta soltanto le righe approvate.",
            "Avvia operazione");

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
