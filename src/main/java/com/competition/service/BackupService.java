package com.competition.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Backup and restore of all runtime data as one zip file: data.json,
 * competition.json, teams.json, relays.json and meet.json. The discipline catalog
 * (disciplines.json) ships with every release and is not part of it.
 *
 * <p>Both directions hold every file's lock, so a backup is a consistent
 * snapshot and a restore replaces all files at once. Lock order: meet.json,
 * teams.json, relays.json, data.json, disciplines, which matches the services'
 * own order (teams/relays before data.json, data.json before disciplines);
 * meet.json is never locked together with another file elsewhere.
 *
 * <p>Before a restore the current files are saved to
 * backups/pre-restore-&lt;time&gt;.zip, so a wrong restore can be undone.
 */
public class BackupService {
    private static final Logger logger = LoggerFactory.getLogger(BackupService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** The runtime data files, in the order they are written to the zip. data.json is required on restore. */
    static final List<String> FILES = List.of(
        "data.json", "competition.json", "teams.json", "relays.json", "meet.json");
    static final String REQUIRED_FILE = "data.json";
    static final String MANIFEST = "backup-info.json";
    static final String SAFETY_COPY_DIR = "backups";
    static final int FORMAT = 1;

    // Far above any real competition; guards against a zip that unpacks to gigabytes
    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path baseDir;
    private final DataService dataService;
    private final TeamService teamService;
    private final RelayService relayService;
    private final MeetService meetService;

    public BackupService(Path baseDir, DataService dataService, TeamService teamService, RelayService relayService,
                         MeetService meetService) {
        this.baseDir = baseDir;
        this.dataService = dataService;
        this.teamService = teamService;
        this.relayService = relayService;
        this.meetService = meetService;
    }

    /** A file name for a backup taken now, e.g. mlaiccmanager-backup-20260924-101500.zip. */
    public static String backupFileName() {
        return "mlaiccmanager-backup-" + LocalDateTime.now().format(FILE_TIME) + ".zip";
    }

    /** The zipped runtime data. The files are read under lock first, so nothing is locked while the zip is sent. */
    public byte[] createBackup() throws Exception {
        Map<String, byte[]> files = locked(this::readFiles);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeZip(out, files);
        logger.info("Created backup with {}", files.keySet());
        return out.toByteArray();
    }

    /**
     * Replaces the runtime data with the backup's. Files missing from the backup
     * are removed, so the result is exactly the state the backup was taken in.
     * Nothing is changed if the zip is not a valid backup.
     *
     * @return the restored files and the name of the safety copy of the previous data
     */
    public Map<String, Object> restore(InputStream zip) throws Exception {
        Map<String, byte[]> files = readBackup(zip);
        return locked(() -> {
            Map<String, byte[]> previous = readFiles();
            Path safetyCopy = null;
            if (!previous.isEmpty()) {
                Path dir = Files.createDirectories(baseDir.resolve(SAFETY_COPY_DIR));
                safetyCopy = dir.resolve("pre-restore-" + LocalDateTime.now().format(FILE_TIME) + ".zip");
                try (OutputStream out = Files.newOutputStream(safetyCopy)) {
                    writeZip(out, previous);
                }
            }

            for (String name : FILES) {
                Path target = baseDir.resolve(name);
                byte[] content = files.get(name);
                if (content == null) {
                    Files.deleteIfExists(target);
                    continue;
                }
                Path temp = baseDir.resolve(name + ".tmp");
                Files.write(temp, content);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            logger.info("Restored backup with {}; previous data saved to {}", files.keySet(), safetyCopy);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("restored_files", new ArrayList<>(files.keySet()));
            result.put("safety_copy", safetyCopy != null ? SAFETY_COPY_DIR + "/" + safetyCopy.getFileName() : null);
            return result;
        });
    }

    private <T> T locked(Callable<T> action) throws Exception {
        return meetService.exclusive(() -> teamService.exclusive(
            () -> relayService.exclusive(() -> dataService.exclusive(action))));
    }

    /** The data files that exist, by name. Caller holds the locks. */
    private Map<String, byte[]> readFiles() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String name : FILES) {
            Path file = baseDir.resolve(name);
            if (Files.exists(file)) {
                files.put(name, Files.readAllBytes(file));
            }
        }
        return files;
    }

    private static void writeZip(OutputStream out, Map<String, byte[]> files) throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", FORMAT);
        manifest.put("created_at", OffsetDateTime.now().toString());
        manifest.put("files", new ArrayList<>(files.keySet()));

        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(MANIFEST));
            zip.write(objectMapper.writeValueAsBytes(manifest));
            zip.closeEntry();
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
    }

    /**
     * The data files of an uploaded backup, each checked to be a JSON object.
     * Entries are matched by file name, so a zip of a folder holding the files
     * works too; anything else in the zip is ignored.
     */
    static Map<String, byte[]> readBackup(InputStream in) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        boolean empty = true;
        try (ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                empty = false;
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().substring(entry.getName().replace('\\', '/').lastIndexOf('/') + 1);
                if (!FILES.contains(name)) {
                    continue;
                }
                if (files.containsKey(name)) {
                    throw new IllegalArgumentException("The backup contains " + name + " more than once");
                }
                byte[] content = readLimited(zip, name);
                JsonNode json;
                try {
                    json = objectMapper.readTree(content);
                } catch (IOException e) {
                    throw new IllegalArgumentException(name + " in the backup is not valid JSON");
                }
                if (json == null || !json.isObject()) {
                    throw new IllegalArgumentException(name + " in the backup is not a JSON object");
                }
                files.put(name, content);
            }
        } catch (ZipException e) {
            empty = true;
        }
        // ZipInputStream reads anything that is not a zip as an empty zip
        if (empty) {
            throw new IllegalArgumentException("The file is not a zip file");
        }
        if (!files.containsKey(REQUIRED_FILE)) {
            throw new IllegalArgumentException("Not a backup: " + REQUIRED_FILE + " is missing");
        }
        return files;
    }

    private static byte[] readLimited(InputStream in, String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        for (int n; (n = in.read(buffer)) > 0; ) {
            total += n;
            if (total > MAX_FILE_BYTES) {
                throw new IllegalArgumentException(name + " in the backup is too large");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
