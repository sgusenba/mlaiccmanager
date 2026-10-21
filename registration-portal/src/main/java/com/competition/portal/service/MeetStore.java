package com.competition.portal.service;

import com.competition.portal.i18n.Messages;
import com.competition.portal.model.Meet;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The meets, one file each: meets/&lt;id&gt;.json, plus meets/&lt;id&gt;.audit.jsonl
 * for the meet's audit log. Every meet file has its own lock and no code
 * holds two of them at once. Meet ids come from portal.json's counter, so a
 * deleted meet's id is never handed out again.
 */
public class MeetStore {
    public static final String DIR = "meets";
    private static final Pattern MEET_FILE = Pattern.compile("(\\d+)\\.json");

    private final Path dir;
    private final PortalStore portalStore;
    private final Map<Integer, JsonFile<Meet>> files = new ConcurrentHashMap<>();

    public MeetStore(Path dataDir, PortalStore portalStore) {
        this.dir = dataDir.resolve(DIR);
        this.portalStore = portalStore;
    }

    /** Every meet, by id. */
    public List<Meet> list() throws IOException {
        List<Meet> meets = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return meets;
        }
        List<Integer> ids = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*.json")) {
            for (Path entry : entries) {
                Matcher m = MEET_FILE.matcher(entry.getFileName().toString());
                if (m.matches()) {
                    ids.add(Integer.parseInt(m.group(1)));
                }
            }
        }
        ids.sort(Comparator.naturalOrder());
        for (int id : ids) {
            // Deleted since the directory was listed: leave it out
            Meet meet = file(id).read();
            if (meet.id != 0) {
                meets.add(meet);
            }
        }
        return meets;
    }

    /** The meet; RecordNotFoundException if there is none with this id. */
    public Meet get(int id) {
        Meet meet = file(id).read();
        if (meet.id == 0) {
            throw notFound(id);
        }
        return meet;
    }

    /** Stores a new meet under the next meet id and returns it with id and version 1. */
    public Meet create(Meet meet) throws Exception {
        // Takes and releases portal.json's lock before the meet file is locked
        int id = portalStore.nextId("meet");
        return file(id).replace(stored -> {
            if (stored.id != 0) {
                throw new IllegalStateException("Meet file " + id + " already exists");
            }
            meet.id = id;
            meet.version = 1;
            return meet;
        });
    }

    /** Runs change on the stored meet and saves it; RecordNotFoundException if there is none. */
    public <R> R update(int id, Change<Meet, R> change) throws Exception {
        return update(id, change, null);
    }

    /** Like {@link #update(int, Change)}, then runs afterSave while the meet is still locked (e.g. the audit log). */
    public <R> R update(int id, Change<Meet, R> change, Change<R, Void> afterSave) throws Exception {
        return file(id).update(meet -> {
            if (meet.id == 0) {
                throw notFound(id);
            }
            return change.apply(meet);
        }, afterSave);
    }

    /** Deletes the meet's file and its audit log, i.e. all of its data. */
    public void delete(int id) throws Exception {
        file(id).exclusive(path -> {
            if (!Files.deleteIfExists(path)) {
                throw notFound(id);
            }
            Files.deleteIfExists(auditFile(id));
            return null;
        });
    }

    public Path auditFile(int id) {
        return dir.resolve(id + ".audit.jsonl");
    }

    private JsonFile<Meet> file(int id) {
        // A missing file reads as a meet with id 0, i.e. "no such meet"
        return files.computeIfAbsent(id, key -> new JsonFile<>(dir.resolve(key + ".json"), Meet.class, Meet::new));
    }

    private static RecordNotFoundException notFound(int id) {
        return new RecordNotFoundException(Messages.get("meet.notFound", id));
    }
}
