package it.scandeletefiles.application.move;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class MoveJournal {
    public static final int VERSION = 1;

    public enum FileState { PLANNED, MOVED, RESTORED }
    public enum DirectorySide { SOURCE, DESTINATION }

    public record FileRecord(Path originalPath, Path targetPath, String mtime, String targetMtime,
                             long size, String sha256, FileState state) {
        public FileRecord {
            Objects.requireNonNull(originalPath, "originalPath");
            Objects.requireNonNull(targetPath, "targetPath");
            Objects.requireNonNull(mtime, "mtime");
            Objects.requireNonNull(targetMtime, "targetMtime");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(state, "state");
        }

        public FileRecord withState(FileState newState) {
            return new FileRecord(originalPath, targetPath, mtime, targetMtime, size, sha256, newState);
        }

        public FileRecord movedAt(String observedTargetMtime) {
            return new FileRecord(originalPath, targetPath, mtime, observedTargetMtime,
                    size, sha256, FileState.MOVED);
        }
    }

    public record DirectoryRecord(DirectorySide side, Path path, boolean existedBefore,
                                  String mtime) {
        public DirectoryRecord {
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(mtime, "mtime");
        }
    }

    private final Path sourceRoot;
    private final Path destinationRoot;
    private final List<FileRecord> files;
    private final List<DirectoryRecord> directories;

    public MoveJournal(Path sourceRoot, Path destinationRoot) {
        this(sourceRoot, destinationRoot, new ArrayList<>(), new ArrayList<>());
    }

    public MoveJournal(Path sourceRoot, Path destinationRoot, List<FileRecord> files,
                       List<DirectoryRecord> directories) {
        this.sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot").toAbsolutePath().normalize();
        this.destinationRoot = Objects.requireNonNull(destinationRoot, "destinationRoot").toAbsolutePath().normalize();
        this.files = new ArrayList<>(files);
        this.directories = new ArrayList<>(directories);
    }

    public Path sourceRoot() { return sourceRoot; }
    public Path destinationRoot() { return destinationRoot; }
    public List<FileRecord> files() { return files; }
    public List<DirectoryRecord> directories() { return directories; }
}
