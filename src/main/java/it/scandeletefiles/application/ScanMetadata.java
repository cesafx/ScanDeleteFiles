package it.scandeletefiles.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Metadati tecnici associati al CSV di scansione, senza alterarne il contratto. */
public final class ScanMetadata {
    private static final Pattern ROOT_PROPERTY = Pattern.compile(
            "\\\"scanRoot\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");
    private static final String AUDIT_ROOT_PREFIX = "Cartella analizzata: ";

    private ScanMetadata() {
    }

    public static Path pathFor(Path csv) {
        String name = csv.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return csv.resolveSibling(stem + ".meta.json");
    }

    public static void write(Path csv, Path scanRoot) throws IOException {
        Path metadata = pathFor(csv);
        Path parent = metadata.getParent() == null ? Path.of(".") : metadata.getParent();
        Path temp = parent.resolve(metadata.getFileName() + ".tmp." + System.nanoTime());
        String json = "{\n"
                + "  \"version\": 1,\n"
                + "  \"scanRoot\": \"" + escape(scanRoot.toAbsolutePath().normalize().toString()) + "\"\n"
                + "}\n";
        Files.writeString(temp, json, StandardCharsets.UTF_8);
        try {
            Files.move(temp, metadata, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temp, metadata);
        }
    }

    public static Optional<Path> readRoot(Path csv) {
        Optional<Path> metadataRoot = readMetadataRoot(pathFor(csv));
        return metadataRoot.isPresent() ? metadataRoot : readAuditRoot(csv);
    }

    private static Optional<Path> readMetadataRoot(Path metadata) {
        if (!Files.isRegularFile(metadata)) {
            return Optional.empty();
        }
        try {
            Matcher matcher = ROOT_PROPERTY.matcher(Files.readString(metadata, StandardCharsets.UTF_8));
            if (matcher.find()) {
                return pathOf(unescape(matcher.group(1)));
            }
        } catch (IOException ignored) {
            // Il fallback sul log mantiene utilizzabili i CSV prodotti prima dei metadati.
        }
        return Optional.empty();
    }

    private static Optional<Path> readAuditRoot(Path csv) {
        Path audit = csv.resolveSibling(csv.getFileName().toString() + ".log");
        if (!Files.isRegularFile(audit)) {
            return Optional.empty();
        }
        try {
            for (String line : Files.readAllLines(audit, StandardCharsets.UTF_8)) {
                if (line.startsWith(AUDIT_ROOT_PREFIX)) {
                    return pathOf(line.substring(AUDIT_ROOT_PREFIX.length()));
                }
            }
        } catch (IOException ignored) {
            // La root potrà essere inserita manualmente nella schermata Move/Delete.
        }
        return Optional.empty();
    }

    private static Optional<Path> pathOf(String value) {
        if (value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(value).normalize());
        } catch (RuntimeException invalidPath) {
            return Optional.empty();
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static String unescape(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (!escaped) {
                if (current == '\\') {
                    escaped = true;
                } else {
                    result.append(current);
                }
                continue;
            }
            result.append(switch (current) {
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                default -> current;
            });
            escaped = false;
        }
        if (escaped) {
            result.append('\\');
        }
        return result.toString();
    }
}
