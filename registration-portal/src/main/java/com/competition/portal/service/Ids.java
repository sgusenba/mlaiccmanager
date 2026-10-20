package com.competition.portal.service;

import java.util.Map;

/** Id counters kept in a document's next_ids: an id is never handed out twice, also after deletions. */
public final class Ids {
    private Ids() {
    }

    /** The next id of the kind (from 1), counting the stored counter up. */
    public static int take(Map<String, Integer> nextIds, String kind) {
        int id = Math.max(1, nextIds.getOrDefault(kind, 1));
        nextIds.put(kind, id + 1);
        return id;
    }
}
