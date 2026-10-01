package com.competition.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The receiver's folder of backup zips, named like the app names them:
 * mlaiccmanager-backup-yyyyMMdd-HHmmss.zip.
 *
 * <p>Retention keeps every backup of the last 24 hours, the newest of each day
 * for the 30 days before that, and the newest of each month beyond that. Ages
 * come from the time in the file name.
 */
public class BackupStore {
    private static final Logger logger = LoggerFactory.getLogger(BackupStore.class);

    private static final Pattern NAME = Pattern.compile("mlaiccmanager-backup-(\\d{8}-\\d{6})\\.zip");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path dir;

    public BackupStore(Path dir) throws IOException {
        this.dir = Files.createDirectories(dir);
    }

    public record Entry(String name, long size, LocalDateTime time) {}

    public static boolean isValidName(String name) {
        return time(name).isPresent();
    }

    private static Optional<LocalDateTime> time(String name) {
        Matcher m = NAME.matcher(name == null ? "" : name);
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.parse(m.group(1), FILE_TIME));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** Stores a backup under name, replacing a file of that name; the name must be valid. */
    public synchronized void store(String name, byte[] content) throws IOException {
        if (!isValidName(name)) {
            throw new IllegalArgumentException("Invalid backup name");
        }
        Path temp = dir.resolve(name + ".tmp");
        Files.write(temp, content);
        Files.move(temp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The stored backups, newest first. */
    public synchronized List<Entry> list() throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                Optional<LocalDateTime> time = time(name);
                if (time.isPresent() && Files.isRegularFile(file)) {
                    entries.add(new Entry(name, Files.size(file), time.get()));
                }
            }
        }
        entries.sort(Comparator.comparing(Entry::time).reversed().thenComparing(Entry::name));
        return entries;
    }

    /** The bytes of a stored backup, or empty if there is none of that name. */
    public synchronized Optional<byte[]> read(String name) throws IOException {
        if (!isValidName(name) || !Files.isRegularFile(dir.resolve(name))) {
            return Optional.empty();
        }
        return Optional.of(Files.readAllBytes(dir.resolve(name)));
    }

    /** Deletes the backups the retention rule does not keep. */
    public synchronized void prune(LocalDateTime now) throws IOException {
        LocalDateTime recent = now.minusHours(24);
        LocalDateTime daily = now.minusDays(30);
        Set<Object> seen = new HashSet<>();
        // Newest first, so the first backup seen of a day or month is the one to keep
        for (Entry entry : list()) {
            boolean keep;
            if (!entry.time().isBefore(recent)) {
                keep = true;
            } else if (!entry.time().isBefore(daily)) {
                keep = seen.add(LocalDate.from(entry.time()));
            } else {
                keep = seen.add(YearMonth.from(entry.time()));
            }
            if (!keep) {
                Files.deleteIfExists(dir.resolve(entry.name()));
                logger.info("Pruned old backup {}", entry.name());
            }
        }
    }
}
