package it.scandeletefiles.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * Adattatore del cestino conforme a freedesktop.org: sposta il file in
 * {@code $XDG_TRASH_HOME o ~/.local/share/Trash/files} e scrive il file
 * {@code .trashinfo} corrispondente in {@code .../Trash/info} con percorso originale
 * e data di eliminazione. I nomi in collisione vengono distinti con un suffisso.
 * Se il cestino della piattaforma non è creabile, l'operazione fallisce senza
 * effettuare alcuna cancellazione definitiva: il chiamante decide come trattare
 * l'esito (nessun fallback automatico verso la cancellazione permanente).
 */
public final class FreedesktopTrashAdapter implements TrashAdapter {

    private static final DateTimeFormatter TRASHINFO_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ITALIAN);

    private final Path trashHome;
    private final Clock clock;

    public FreedesktopTrashAdapter() {
        this(Path.of(System.getProperty("user.home"), ".local", "share", "Trash"), Clock.systemDefaultZone());
    }

    FreedesktopTrashAdapter(Path trashHome, Clock clock) {
        this.trashHome = Objects.requireNonNull(trashHome, "trashHome");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public TrashResult moveToTrash(Path file) {
        Objects.requireNonNull(file, "file");
        try {
            Path filesDir = trashHome.resolve("files");
            Path infoDir = trashHome.resolve("info");
            Files.createDirectories(filesDir);
            Files.createDirectories(infoDir);
            Path target = uniqueTarget(filesDir, file.getFileName().toString());
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            Path trashinfo = infoDir.resolve(target.getFileName().toString() + ".trashinfo");
            String content = "[Trash Info]\n"
                    + "Path=" + originalPath(file) + "\n"
                    + "DeletionDate=" + deletionDate() + "\n";
            Files.write(trashinfo, content.getBytes(StandardCharsets.UTF_8));
            return new TrashResult(TrashResult.Status.MOVED, "Spostato nel cestino");
        } catch (IOException exc) {
            return new TrashResult(TrashResult.Status.FAILED,
                    "Impossibile spostare nel cestino: " + exc.getMessage());
        }
    }

    private Path uniqueTarget(Path filesDir, String fileName) throws IOException {
        Path target = filesDir.resolve(fileName);
        if (!Files.exists(target)) {
            return target;
        }
        String base = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0 && dot < fileName.length() - 1) {
            base = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }
        String stamp = deletionDate().substring(0, 10).replace('-', '.');
        for (int i = 0; i < 1000; i++) {
            Path attempt = filesDir.resolve(base + " (" + stamp + ")" + extension);
            if (!Files.exists(attempt)) {
                return attempt;
            }
        }
        return filesDir.resolve(base + "_" + System.nanoTime() + extension);
    }

    private String originalPath(Path file) {
        return file.toAbsolutePath().normalize().toString();
    }

    private String deletionDate() {
        Instant instant = clock.instant();
        return instant.atZone(ZoneId.systemDefault()).format(TRASHINFO_DATE);
    }
}
