package com.competition.portal.service;

import com.competition.portal.model.PortalData;

import java.nio.file.Path;

/**
 * portal.json: organiser accounts, settings and the portal-wide id counters.
 * Written rarely. Lock order: portal.json before a meet file, and its lock is
 * never held while a meet file is locked.
 */
public class PortalStore {
    public static final String FILE = "portal.json";

    private final JsonFile<PortalData> file;

    public PortalStore(Path dataDir) {
        this.file = new JsonFile<>(dataDir.resolve(FILE), PortalData.class, PortalData::new);
    }

    public PortalData get() {
        return file.read();
    }

    public <R> R update(Change<PortalData, R> change) throws Exception {
        return file.update(change);
    }

    /** Takes the next id of the kind and saves the counter at once, so it survives a restart. */
    public int nextId(String kind) throws Exception {
        return file.update(data -> Ids.take(data.nextIds, kind));
    }

    public JsonFile<PortalData> file() {
        return file;
    }
}
