package com.competition.portal.model;

import java.util.List;

/**
 * What clubs can register for at a meet: its individual rankings with their
 * entries and the disciplines behind them. Derived from the snapshot each
 * time, never stored.
 */
public final class Offer {
    private Offer() {
    }

    public static class Ranking {
        public final int rankingId;
        public final String name;
        public final String shortName;
        /** "rifle", "pistol", or both as "pistol / rifle" */
        public final String category;
        public final List<Entry> entries;

        public Ranking(int rankingId, String name, String shortName, String category, List<Entry> entries) {
            this.rankingId = rankingId;
            this.name = name;
            this.shortName = shortName;
            this.category = category;
            this.entries = entries;
        }
    }

    /** One entry clubs can tick; a ranking with O and R has two. */
    public static class Entry {
        public final int index;
        /** The entry's own label (a sum, e.g. "REMO") or its disciplines' short names joined with "+". */
        public final String label;
        public final List<Discipline> disciplines;

        public Entry(int index, String label, List<Discipline> disciplines) {
            this.index = index;
            this.label = label;
            this.disciplines = disciplines;
        }
    }

    public static class Discipline {
        public final int id;
        public final String name;
        public final String shortName;
        public final String category;
        public final String type;
        public final String shootingDistance;

        public Discipline(int id, String name, String shortName, String category, String type, String shootingDistance) {
            this.id = id;
            this.name = name;
            this.shortName = shortName;
            this.category = category;
            this.type = type;
            this.shootingDistance = shootingDistance;
        }
    }
}
