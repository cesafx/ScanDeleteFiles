package it.scandeletefiles.bootstrap;

import it.scandeletefiles.platform.FileSettingsAdapter;
import it.scandeletefiles.platform.FreedesktopTrashAdapter;
import it.scandeletefiles.platform.SettingsAdapter;
import it.scandeletefiles.platform.TrashAdapter;
import it.scandeletefiles.platform.TrashResult;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;

public record PlatformAdapters(
        FileSystem fileSystem,
        TrashAdapter trash,
        SettingsAdapter settings,
        Clock clock) {

    public PlatformAdapters {
        Objects.requireNonNull(fileSystem, "fileSystem");
        Objects.requireNonNull(trash, "trash");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(clock, "clock");
    }

    static PlatformAdapters currentPlatform() {
        TrashAdapter trash;
        String os = System.getProperty("os.name");
        if (os.startsWith("Windows")) {
            // L'integrazione nativa con il cestino di Windows (Shell recycle bin)
            // richiede chiamate piattaforma non disponibili sul JDK: nell'MVP il
            // cestino è trattato come non disponibile e nessuna cancellazione
            // definitiva viene eseguita come fallback implicito.
            trash = file -> new TrashResult(TrashResult.Status.UNAVAILABLE,
                    "Cestino non disponibile su questa piattaforma: integrazione nativa non inclusa nell'MVP");
        } else {
            trash = new FreedesktopTrashAdapter();
        }
        Path configPath = Path.of(System.getProperty("user.home"), ".scandeletefiles", "selected-extensions.txt");
        SettingsAdapter settings = new FileSettingsAdapter(configPath);
        return new PlatformAdapters(FileSystems.getDefault(), trash, settings, Clock.systemDefaultZone());
    }
}
