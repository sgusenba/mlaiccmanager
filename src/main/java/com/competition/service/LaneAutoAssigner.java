package com.competition.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pure planner of the automatic lane assignment. It only decides where starts
 * go; RelayService validates nothing again but stores the result.
 *
 * <p>Hard rules, never broken: a start takes one lane only; a competitor has at
 * most one lane per relay (across all ranges); a start only goes on the range
 * its discipline is set to fire on; a range never holds more starts per relay
 * than it has lanes. Relays the caller passes in are the only ones used (locked
 * days are left out by the caller).
 *
 * <p>Soft goals: the starts of one discipline are placed one after the other,
 * so a discipline occupies a contiguous run of relays, and the disciplines of
 * one event (original, reproduction, combined) follow each other. Both come
 * from filling every relay from a queue sorted by event and type: a start
 * whose competitor already shoots in the relay is skipped and moves to the next
 * relay, where it comes first again.
 */
public final class LaneAutoAssigner {
    private LaneAutoAssigner() {
    }

    /** A discipline's place in the queue: events keep catalog order, types go original, reproduction, combined. */
    public record DisciplineOrder(int disciplineId, String familyKey, int familyRank, int typeRank) {
        static int typeRank(String type) {
            if (type == null) return 3;
            return switch (type) {
                case "original" -> 0;
                case "reproduction" -> 1;
                case "combined" -> 2;
                default -> 3;
            };
        }
    }

    public record Range(String id, String name, int laneCount) {
    }

    /** A start that needs a lane; rangeId is null if its discipline is not set to a range. */
    public record Pending(String startId, int competitorId, int disciplineId, String rangeId) {
    }

    public record Placement(String relayId, String rangeId, int laneNo, String startId) {
    }

    public record Unplaced(String startId, String reason) {
    }

    public record Plan(List<Placement> placements, List<Unplaced> unplaced) {
    }

    /**
     * @param relayIds   relays to fill, in schedule order
     * @param ranges     the ranges in display order
     * @param occupied   relay id -> range id -> lanes already taken (kept as they are)
     * @param shooters   relay id -> competitors that already have a lane in the relay
     * @param pending    starts that still need a lane
     * @param order      discipline id -> its queue position
     */
    public static Plan plan(List<String> relayIds, List<Range> ranges,
                            Map<String, Map<String, Set<Integer>>> occupied,
                            Map<String, Set<Integer>> shooters,
                            List<Pending> pending, Map<Integer, DisciplineOrder> order) {
        List<Unplaced> unplaced = new ArrayList<>();
        Map<String, List<Pending>> queues = new LinkedHashMap<>();
        ranges.forEach(r -> queues.put(r.id(), new ArrayList<>()));
        Map<Integer, String> rangeOfDiscipline = chooseRanges(ranges, pending);
        for (Pending start : pending) {
            String rangeId = rangeOfDiscipline.get(start.disciplineId());
            if (rangeId != null) {
                queues.get(rangeId).add(start);
            } else {
                unplaced.add(new Unplaced(start.startId(), start.rangeId() != null
                    ? "Its range " + start.rangeId() + " does not exist" : "There is no range to fire on"));
            }
        }

        Comparator<Pending> queueOrder = Comparator
            .comparingInt((Pending p) -> rankOf(order, p.disciplineId()).familyRank())
            .thenComparingInt(p -> rankOf(order, p.disciplineId()).typeRank())
            .thenComparingInt(Pending::disciplineId)
            .thenComparingInt(Pending::competitorId)
            .thenComparing(Pending::startId);
        queues.values().forEach(queue -> queue.sort(queueOrder));

        // Working copies: the caller's maps stay untouched
        Map<String, Set<Integer>> inRelay = new HashMap<>();
        for (String relayId : relayIds) {
            inRelay.put(relayId, new HashSet<>(shooters.getOrDefault(relayId, Set.of())));
        }
        Map<String, Map<String, Set<Integer>>> taken = new HashMap<>();
        for (String relayId : relayIds) {
            Map<String, Set<Integer>> byRange = new HashMap<>();
            for (Range range : ranges) {
                byRange.put(range.id(), new HashSet<>(
                    occupied.getOrDefault(relayId, Map.of()).getOrDefault(range.id(), Set.of())));
            }
            taken.put(relayId, byRange);
        }

        List<Placement> placements = new ArrayList<>();
        for (String relayId : relayIds) {
            for (Range range : ranges) {
                List<Pending> queue = queues.get(range.id());
                Set<Integer> lanes = taken.get(relayId).get(range.id());
                Set<Integer> competitors = inRelay.get(relayId);
                int nextLane = 1;
                for (int i = 0; i < queue.size() && lanes.size() < range.laneCount(); ) {
                    Pending start = queue.get(i);
                    if (competitors.contains(start.competitorId())) {
                        i++; // shoots in this relay already: next relay
                        continue;
                    }
                    while (lanes.contains(nextLane)) {
                        nextLane++;
                    }
                    lanes.add(nextLane);
                    competitors.add(start.competitorId());
                    placements.add(new Placement(relayId, range.id(), nextLane, start.startId()));
                    queue.remove(i);
                }
            }
        }

        for (Range range : ranges) {
            for (Pending start : queues.get(range.id())) {
                boolean full = relayIds.stream().allMatch(
                    relayId -> taken.get(relayId).get(range.id()).size() >= range.laneCount());
                unplaced.add(new Unplaced(start.startId(), full
                    ? "No free lane left on " + range.name() + " in the unlocked relays"
                    : "The competitor already has a lane in every relay with a free lane on " + range.name()));
            }
        }
        return new Plan(placements, unplaced);
    }

    private static DisciplineOrder rankOf(Map<Integer, DisciplineOrder> order, int disciplineId) {
        DisciplineOrder rank = order.get(disciplineId);
        return rank != null ? rank : new DisciplineOrder(disciplineId, "", Integer.MAX_VALUE, 3);
    }

    /**
     * A discipline fires on its own range. One without a range goes on the
     * range with the lowest load per lane, as a whole, so it still forms one
     * block. Starts of a range that does not exist get no range.
     */
    private static Map<Integer, String> chooseRanges(List<Range> ranges, List<Pending> pending) {
        Map<String, Range> rangeById = new HashMap<>();
        ranges.forEach(r -> rangeById.put(r.id(), r));

        Map<Integer, String> result = new HashMap<>();
        Map<String, Integer> load = new HashMap<>();
        Map<Integer, Integer> freeDisciplines = new TreeMap<>(); // discipline id -> starts, no range set
        for (Pending start : pending) {
            if (start.rangeId() == null) {
                freeDisciplines.merge(start.disciplineId(), 1, Integer::sum);
            } else if (rangeById.containsKey(start.rangeId())) {
                result.put(start.disciplineId(), start.rangeId());
                load.merge(start.rangeId(), 1, Integer::sum);
            }
        }
        if (!ranges.isEmpty()) {
            freeDisciplines.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> {
                    Range best = ranges.get(0);
                    double bestLoad = Double.MAX_VALUE;
                    for (Range range : ranges) {
                        double perLane = (load.getOrDefault(range.id(), 0) + entry.getValue()) / (double) range.laneCount();
                        if (perLane < bestLoad) {
                            bestLoad = perLane;
                            best = range;
                        }
                    }
                    result.put(entry.getKey(), best.id());
                    load.merge(best.id(), entry.getValue(), Integer::sum);
                });
        }
        return result;
    }
}
