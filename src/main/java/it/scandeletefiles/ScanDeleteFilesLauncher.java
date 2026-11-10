package it.scandeletefiles;

/**
 * Entry point per il launcher nativo jpackage.
 *
 * <p>La classe non estende JavaFX {@code Application}: in questo modo il launcher
 * Java standard usa il classpath dell'app-image prima di delegare all'applicazione
 * JavaFX.</p>
 */
public final class ScanDeleteFilesLauncher {

    private ScanDeleteFilesLauncher() {
    }

    public static void main(String[] args) {
        ScanDeleteFilesApplication.main(args);
    }
}
