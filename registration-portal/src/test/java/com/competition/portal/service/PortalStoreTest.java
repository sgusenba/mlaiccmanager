package com.competition.portal.service;

import com.competition.portal.model.Organiser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PortalStoreTest {
    @TempDir
    Path dataDir;

    @Test
    void idCountersOnlyGoUpAndSurviveARestart() throws Exception {
        PortalStore store = new PortalStore(dataDir);
        assertEquals(1, store.nextId("meet"));
        assertEquals(2, store.nextId("meet"));
        assertEquals(1, store.nextId("organiser"), "each kind counts on its own");
        assertEquals(3, new PortalStore(dataDir).nextId("meet"));
    }

    @Test
    void organisersAreStored() throws Exception {
        PortalStore store = new PortalStore(dataDir);
        store.update(data -> {
            Organiser o = new Organiser();
            o.id = Ids.take(data.nextIds, "organiser");
            o.username = "stefan";
            data.organisers.add(o);
            return null;
        });
        assertEquals("stefan", new PortalStore(dataDir).get().organisers.get(0).username);
    }
}
