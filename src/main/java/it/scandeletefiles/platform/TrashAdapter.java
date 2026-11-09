package it.scandeletefiles.platform;

import java.nio.file.Path;

@FunctionalInterface
public interface TrashAdapter {
    TrashResult moveToTrash(Path file);
}
