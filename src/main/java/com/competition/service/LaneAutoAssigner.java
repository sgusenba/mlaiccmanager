package com.competition.service;

import com.competition.i18n.Messages;
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
 * than it has lanes; a start only goes in a relay whose day takes its discipline.
 * Relays the caller passes in are the only ones used (locked days are left out
 * by the caller).
 *
 * <p>Soft goals: the starts of one discipline are placed one after the other,
 * so a discipline occupies a contiguous run of relays, and the disciplines
 * ranked together follow each other. Both come from filling every relay from
 * a queue sorted by the meet's ranking order: a start
 * whose competitor already shoots in the relay is skipped and moves to the next
 * relay, where it comes first again. Afterwards a competitor's back-to-back
 * relays on one day are removed where a start can move or swap without breaking
 * a hard rule, so this goal never costs a start its lane.
 */
public final class LaneAutoAssigner {
    private LaneAutoAssigner() {
    }

    /** A discipline's place in the queue: the meet's ranking order, so disciplines ranked together follow each other. */
    public record DisciplineOrder(int disciplineId, int rank) {
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
     * @param allowed    relay id -> the disciplines its day takes
     * @param nextRelay  relay id -> the relay after it on the same day; none for a day's last relay
     */
    public static Plan plan(List<String> relayIds, List<Range> ranges,
                            Map<String, Map<String, Set<Integer>>> occupied,
                            Map<String, Set<Integer>> shooters,
                            List<Pending> pending, Map<Integer, DisciplineOrder> order,
                            Map<String, Set<Integer>> allowed, Map<String, String> nextRelay) {
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
                    ? Messages.get("auto.rangeMissing", start.rangeId()) : Messages.get("auto.noRange")));
            }
        }

        Comparator<Pending> queueOrder = Comparator
            .comparingInt((Pending p) -> rankOf(order, p.disciplineId()).rank())
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
                    if (!allowed.get(relayId).contains(start.disciplineId())) {
                        i++; // shot on another day
                        continue;
                    }
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

        new BackToBackRepair(relayIds, ranges, pending, allowed, nextRelay, inRelay, taken, placements).run();

        for (Range range : ranges) {
            for (Pending start : queues.get(range.id())) {
                List<String> open = relayIds.stream()
                    .filter(relayId -> allowed.get(relayId).contains(start.disciplineId())).toList();
                if (open.isEmpty()) {
                    unplaced.add(new Unplaced(start.startId(), Messages.get("auto.noDay")));
                    continue;
                }
                boolean full = open.stream().allMatch(
                    relayId -> taken.get(relayId).get(range.id()).size() >= range.laneCount());
                unplaced.add(new Unplaced(start.startId(), full
                    ? Messages.get("auto.noFreeLane", range.name())
                    : Messages.get("auto.competitorBusy", range.name())));
            }
        }
        return new Plan(placements, unplaced);
    }

    /**
     * Moves planned starts out of relays right next to another of the
     * competitor's relays. A start stays on its range and only goes to a relay
     * that takes its discipline and where its competitor does not shoot yet, into
     * a free lane or by swapping with another planned start; lanes that were
     * already assigned stay. Every step lowers the back-to-back count, so this
     * ends. Of the possible steps it takes the one that spreads the disciplines
     * over the fewest extra relays, then the shortest move, which keeps
     * disciplines together as far as possible.
     */
    private static final class BackToBackRepair {
        /** Moves the start at index to relayId: into a free lane, or swapping with the start at swapWith. */
        private record Step(int index, int swapWith, String relayId, int spread, int distance) {
        }

        private final List<String> relayIds;
        private final Map<String, Integer> positions = new HashMap<>();
        private final Map<String, Integer> laneCounts = new HashMap<>();
        private final Map<String, Pending> starts = new HashMap<>();
        private final Map<Integer, TreeMap<Integer, Integer>> relaysOfDiscipline = new HashMap<>(); // position -> starts
        private final Map<String, Set<Integer>> allowed;
        private final Map<String, String> nextRelay;
        private final Map<String, Set<Integer>> inRelay;
        private final Map<String, Map<String, Set<Integer>>> taken;
        private final List<Placement> placements;

        BackToBackRepair(List<String> relayIds, List<Range> ranges, List<Pending> pending,
                         Map<String, Set<Integer>> allowed, Map<String, String> nextRelay,
                         Map<String, Set<Integer>> inRelay, Map<String, Map<String, Set<Integer>>> taken,
                         List<Placement> placements) {
            this.relayIds = relayIds;
            this.allowed = allowed;
            this.nextRelay = nextRelay;
            this.inRelay = inRelay;
            this.taken = taken;
            this.placements = placements;
            for (int i = 0; i < relayIds.size(); i++) {
                positions.put(relayIds.get(i), i);
            }
            ranges.forEach(r -> laneCounts.put(r.id(), r.laneCount()));
            pending.forEach(p -> starts.put(p.startId(), p));
            for (Placement placement : placements) {
                relaysOfDiscipline.computeIfAbsent(startAt(placement).disciplineId(), k -> new TreeMap<>())
                    .merge(positions.get(placement.relayId()), 1, Integer::sum);
            }
        }

        void run() {
            Step step = bestStep();
            while (step != null) {
                apply(step);
                step = bestStep();
            }
        }

        private Pending startAt(Placement placement) {
            return starts.get(placement.startId());
        }

        private Step bestStep() {
            Step best = null;
            for (int i = 0; i < placements.size(); i++) {
                if (backToBackIn(placements.get(i))) {
                    for (Step step : stepsFor(i)) {
                        if (best == null || step.spread() < best.spread()
                            || (step.spread() == best.spread() && step.distance() < best.distance())) {
                            best = step;
                        }
                    }
                }
            }
            return best;
        }

        private boolean backToBackIn(Placement placement) {
            int competitor = startAt(placement).competitorId();
            String next = nextRelay.get(placement.relayId());
            if (next != null && inRelay.get(next).contains(competitor)) {
                return true;
            }
            return relayIds.stream().anyMatch(relayId -> placement.relayId().equals(nextRelay.get(relayId))
                && inRelay.get(relayId).contains(competitor));
        }

        private List<Step> stepsFor(int index) {
            Placement placement = placements.get(index);
            Pending start = startAt(placement);
            List<Step> steps = new ArrayList<>();
            for (String relayId : relayIds) {
                if (!relayId.equals(placement.relayId()) && fits(start, relayId)
                    && taken.get(relayId).get(placement.rangeId()).size() < laneCounts.get(placement.rangeId())) {
                    addIfBetter(steps, index, -1, relayId);
                }
            }
            for (int j = 0; j < placements.size(); j++) {
                Placement other = placements.get(j);
                if (j != index && !other.relayId().equals(placement.relayId()) && other.rangeId().equals(placement.rangeId())
                    && fits(start, other.relayId()) && fits(startAt(other), placement.relayId())) {
                    addIfBetter(steps, index, j, other.relayId());
                }
            }
            return steps;
        }

        private boolean fits(Pending start, String relayId) {
            return allowed.get(relayId).contains(start.disciplineId()) && !inRelay.get(relayId).contains(start.competitorId());
        }

        /** Tries the step on the working state, keeps it as a candidate if it lowers the back-to-back count, and undoes it. */
        private void addIfBetter(List<Step> steps, int index, int swapWith, String relayId) {
            Placement placement = placements.get(index);
            Pending start = startAt(placement);
            Pending other = swapWith >= 0 ? startAt(placements.get(swapWith)) : null;
            int before = backToBackCount(start.competitorId()) + (other != null ? backToBackCount(other.competitorId()) : 0);
            int spreadBefore = span(start.disciplineId()) + (other != null ? span(other.disciplineId()) : 0);

            shift(start, placement.relayId(), relayId);
            if (other != null) {
                shift(other, relayId, placement.relayId());
            }
            int after = backToBackCount(start.competitorId()) + (other != null ? backToBackCount(other.competitorId()) : 0);
            int spreadAfter = span(start.disciplineId()) + (other != null ? span(other.disciplineId()) : 0);
            if (other != null) {
                shift(other, placement.relayId(), relayId);
            }
            shift(start, relayId, placement.relayId());

            if (after < before) {
                steps.add(new Step(index, swapWith, relayId, spreadAfter - spreadBefore,
                    Math.abs(positions.get(relayId) - positions.get(placement.relayId()))));
            }
        }

        private void apply(Step step) {
            Placement placement = placements.get(step.index());
            shift(startAt(placement), placement.relayId(), step.relayId());
            if (step.swapWith() >= 0) {
                Placement other = placements.get(step.swapWith());
                shift(startAt(other), other.relayId(), placement.relayId());
                placements.set(step.index(), new Placement(other.relayId(), other.rangeId(), other.laneNo(), placement.startId()));
                placements.set(step.swapWith(), new Placement(placement.relayId(), placement.rangeId(), placement.laneNo(), other.startId()));
                return;
            }
            Set<Integer> lanes = taken.get(step.relayId()).get(placement.rangeId());
            int lane = 1;
            while (lanes.contains(lane)) {
                lane++;
            }
            lanes.add(lane);
            taken.get(placement.relayId()).get(placement.rangeId()).remove(placement.laneNo());
            placements.set(step.index(), new Placement(step.relayId(), placement.rangeId(), lane, placement.startId()));
        }

        private int backToBackCount(int competitor) {
            int count = 0;
            for (String relayId : relayIds) {
                String next = nextRelay.get(relayId);
                if (next != null && inRelay.get(relayId).contains(competitor) && inRelay.get(next).contains(competitor)) {
                    count++;
                }
            }
            return count;
        }

        /** How many relays lie between the discipline's first and last one. */
        private int span(int disciplineId) {
            TreeMap<Integer, Integer> relays = relaysOfDiscipline.get(disciplineId);
            return relays.lastKey() - relays.firstKey();
        }

        private void shift(Pending start, String from, String to) {
            inRelay.get(from).remove(start.competitorId());
            inRelay.get(to).add(start.competitorId());
            TreeMap<Integer, Integer> relays = relaysOfDiscipline.get(start.disciplineId());
            relays.merge(positions.get(from), -1, (count, change) -> count + change == 0 ? null : count + change);
            relays.merge(positions.get(to), 1, Integer::sum);
        }
    }

    private static DisciplineOrder rankOf(Map<Integer, DisciplineOrder> order, int disciplineId) {
        DisciplineOrder rank = order.get(disciplineId);
        return rank != null ? rank : new DisciplineOrder(disciplineId, Integer.MAX_VALUE);
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
