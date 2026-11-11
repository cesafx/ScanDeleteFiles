package it.scandeletefiles.application.move;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

final class MovePathSafety {
    private MovePathSafety() { }

    static void requireContained(Path root, Path path, boolean pathMayNotExist) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (normalizedPath.equals(normalizedRoot) || !normalizedPath.startsWith(normalizedRoot)) {
            throw new IOException("Percorso esterno alla root del journal: " + normalizedPath);
        }
        Path current = normalizedRoot;
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Root del journal assente: " + current);
        }
        Path rootReal = current.toRealPath();
        rejectLinkOrReparsePoint(current);
        for (Path part : normalizedRoot.relativize(normalizedPath)) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                rejectLinkOrReparsePoint(current);
                if (!current.toRealPath().startsWith(rootReal)) {
                    throw new IOException("Il percorso esce dalla root reale del journal: " + current);
                }
            } else if (!pathMayNotExist) {
                break;
            }
        }
    }

    private static void rejectLinkOrReparsePoint(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (Files.isSymbolicLink(path) || attributes.isOther()) {
            throw new IOException("Link o reparse point non ammesso nel percorso: " + path);
        }
    }
}
