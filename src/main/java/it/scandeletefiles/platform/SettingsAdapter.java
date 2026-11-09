package it.scandeletefiles.platform;

import java.util.Set;

public interface SettingsAdapter {
    Set<String> loadSelectedExtensions();

    void saveSelectedExtensions(Set<String> extensions);
}
