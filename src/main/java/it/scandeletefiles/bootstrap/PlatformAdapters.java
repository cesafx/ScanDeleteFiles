package it.scandeletefiles.bootstrap;

import it.scandeletefiles.platform.FileSettingsAdapter;
import it.scandeletefiles.platform.SettingsAdapter;
import it.scandeletefiles.platform.TrashAdapter;
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
        TrashAdapter trash = file -> {
            throw new UnsupportedOperationException("L'adattatore del cestino non è ancora implementato");
        };
        Path configPath = Path.of(System.getProperty("user.home"), ".scandeletefiles", "selected-extensions.txt");
        SettingsAdapter settings = new FileSettingsAdapter(configPath);
        return new PlatformAdapters(FileSystems.getDefault(), trash, settings, Clock.systemDefaultZone());
    }
}
