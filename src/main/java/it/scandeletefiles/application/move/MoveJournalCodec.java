package it.scandeletefiles.application.move;

import it.scandeletefiles.application.csv.CsvFormat;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class MoveJournalCodec {
    private static final char SEP = ';';
    private static final String HEADER = "Version;RecordType;State;SourceRoot;DestinationRoot;OriginalPath;TargetPath;Mtime;TargetMtime;Size;SHA256;DirectorySide;DirectoryPath;ExistedBefore;DirectoryMtime";

    public void write(Path path, MoveJournal journal) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent() == null ? Path.of(".").toAbsolutePath() : absolute.getParent();
        Files.createDirectories(parent);
        Path temp = parent.resolve(absolute.getFileName() + ".tmp." + System.nanoTime());
        try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            writer.write('\uFEFF');
            writer.write(HEADER);
            writer.newLine();
            writeRow(writer, Integer.toString(MoveJournal.VERSION), "META", "", journal.sourceRoot().toString(),
                    journal.destinationRoot().toString(), "", "", "", "", "", "", "", "", "", "");
            for (MoveJournal.FileRecord file : journal.files()) {
                writeRow(writer, Integer.toString(MoveJournal.VERSION), "FILE", file.state().name(), "", "",
                        file.originalPath().toString(), file.targetPath().toString(),
                        file.mtime(), file.targetMtime(), Long.toString(file.size()), file.sha256(),
                        "", "", "", "");
            }
            for (MoveJournal.DirectoryRecord directory : journal.directories()) {
                writeRow(writer, Integer.toString(MoveJournal.VERSION), "DIRECTORY", "", "", "", "", "", "", "", "", "",
                        directory.side().name(), directory.path().toString(),
                        Boolean.toString(directory.existedBefore()), directory.mtime());
            }
        }
        try {
            Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exc) {
            Files.deleteIfExists(temp);
            throw new IOException("Il filesystem non supporta l'aggiornamento atomico del registro Move", exc);
        }
    }

    public MoveJournal read(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.size() < 2 || !lines.get(0).equals("\uFEFF" + HEADER)) {
            throw new IOException("Journal Move non conforme o privo di versione");
        }
        Path sourceRoot = null;
        Path destinationRoot = null;
        List<MoveJournal.FileRecord> files = new ArrayList<>();
        List<MoveJournal.DirectoryRecord> directories = new ArrayList<>();
        for (int lineNumber = 1; lineNumber < lines.size(); lineNumber++) {
            if (lines.get(lineNumber).isBlank()) continue;
            List<String> fields = parse(lines.get(lineNumber));
            if (fields.size() != 15 || !fields.get(0).equals(Integer.toString(MoveJournal.VERSION))) {
                throw new IOException("Versione o riga journal non valida alla riga " + (lineNumber + 1));
            }
            try {
                switch (fields.get(1)) {
                    case "META" -> {
                        sourceRoot = Path.of(fields.get(3)).toAbsolutePath().normalize();
                        destinationRoot = Path.of(fields.get(4)).toAbsolutePath().normalize();
                    }
                    case "FILE" -> files.add(new MoveJournal.FileRecord(
                            Path.of(fields.get(5)).toAbsolutePath().normalize(),
                            Path.of(fields.get(6)).toAbsolutePath().normalize(),
                            fields.get(7), fields.get(8), Long.parseLong(fields.get(9)), fields.get(10),
                            MoveJournal.FileState.valueOf(fields.get(2))));
                    case "DIRECTORY" -> directories.add(new MoveJournal.DirectoryRecord(
                            MoveJournal.DirectorySide.valueOf(fields.get(11)),
                            Path.of(fields.get(12)).toAbsolutePath().normalize(),
                            parseBooleanStrict(fields.get(13)), fields.get(14)));
                    default -> throw new IOException("Tipo record journal non valido: " + fields.get(1));
                }
            } catch (IllegalArgumentException exc) {
                throw new IOException("Valore journal non valido alla riga " + (lineNumber + 1), exc);
            }
        }
        if (sourceRoot == null || destinationRoot == null) {
            throw new IOException("Record META mancante nel journal Move");
        }
        return new MoveJournal(sourceRoot, destinationRoot, files, directories);
    }

    private static boolean parseBooleanStrict(String value) {
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException("Valore booleano non valido: " + value);
    }

    private static void writeRow(BufferedWriter writer, String... fields) throws IOException {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) writer.write(SEP);
            writer.write(CsvFormat.escape(fields[i], SEP));
        }
        writer.newLine();
    }

    private static List<String> parse(String line) throws IOException {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"'); i++;
                    } else quoted = false;
                } else current.append(c);
            } else if (c == SEP) {
                fields.add(current.toString()); current.setLength(0);
            } else if (c == '"') quoted = true;
            else current.append(c);
        }
        if (quoted) throw new IOException("Virgolette non chiuse nel journal");
        fields.add(current.toString());
        return fields;
    }
}
