package it.scandeletefiles.bootstrap;

import it.scandeletefiles.platform.SettingsAdapter;
import it.scandeletefiles.platform.TrashAdapter;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;

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
        SettingsAdapter settings = new SettingsAdapter() {
            @Override
            public Set<String> loadSelectedExtensions() {
                throw new UnsupportedOperationException("La persistenza delle preferenze non è ancora implementata");
            }

            @Override
            public void saveSelectedExtensions(Set<String> extensions) {
                throw new UnsupportedOperationException("La persistenza delle preferenze non è ancora implementata");
            }
        };
        return new PlatformAdapters(FileSystems.getDefault(), trash, settings, Clock.systemDefaultZone());
    }
}
