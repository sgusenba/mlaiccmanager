package com.competition.service;

import java.util.List;
import java.util.Map;

/**
 * Shot scoring shared by the individual and the team ranking: a result's score,
 * its ring counts for the countback, and the manual tie-break value, for which
 * the lower value wins (distance of the furthest shot from the centre).
 */
public final class Scoring {
    public static final int MAX_RING = 10;

    private Scoring() {}

    /**
     * The shots' sum when entries were recorded, otherwise the stored value.
     * Older results stored the override instead of the sum as value.
     */
    public static double score(Map<String, Object> result) {
        if (result == null) {
            return 0.0;
        }
        if (result.get("entries") instanceof List<?> entries && !entries.isEmpty()) {
            double sum = 0.0;
            for (Object entry : entries) {
                if (entry instanceof Number n) {
                    sum += n.doubleValue();
                }
            }
            return sum;
        }
        return result.get("value") instanceof Number n ? n.doubleValue() : 0.0;
    }

    /** Adds the result's shots to counts, indexed by ring (1-10); misses and odd values are ignored. */
    public static void addRingCounts(Map<String, Object> result, int[] counts) {
        if (result == null || !(result.get("entries") instanceof List<?> entries)) {
            return;
        }
        for (Object entry : entries) {
            if (entry instanceof Number n) {
                double value = n.doubleValue();
                int ring = (int) value;
                // Entries saved by Java come back as 10.0, those saved by the Python version as 10
                if (ring == value && ring >= 1 && ring <= MAX_RING) {
                    counts[ring]++;
                }
            }
        }
    }

    public static int[] newRingCounts() {
        return new int[MAX_RING + 1];
    }

    /**
     * Sort key value for a tie-break: the lower value wins, a missing one
     * loses against any entered one. Higher key means better, like the other key fields.
     */
    public static double tieBreakKey(Double tieBreak) {
        return tieBreak != null ? -tieBreak : Double.NEGATIVE_INFINITY;
    }

    public static Double doubleOrNull(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }
}
