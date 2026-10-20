package com.competition.portal.service;

import com.competition.portal.format.CatalogFile;
import com.competition.portal.format.RankingsFile;
import com.competition.portal.i18n.Messages;
import com.competition.portal.model.Choice;
import com.competition.portal.model.Offer;
import com.competition.portal.model.Snapshot;
import com.competition.portal.model.Starter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Reads and checks the rankings and catalog files the organiser exports from
 * the Manager, and derives from them what clubs can register for. The files
 * are the contract between the two programs; no Manager code is shared.
 */
public final class SnapshotService {
    private SnapshotService() {
    }

    /**
     * The snapshot of the two uploaded files. Throws IllegalArgumentException,
     * saying what is wrong, if they are not the Manager's files or do not fit
     * together; nothing is kept then.
     */
    public static Snapshot parse(JsonNode rankingsNode, JsonNode catalogNode) {
        RankingsFile rankings = read(rankingsNode, RankingsFile.class, RankingsFile.FORMAT, "snapshot.notRankings");
        if (rankings.version != null && rankings.version > RankingsFile.VERSION) {
            throw new IllegalArgumentException(Messages.get("snapshot.newerVersion", RankingsFile.FORMAT));
        }
        CatalogFile catalog = read(catalogNode, CatalogFile.class, CatalogFile.FORMAT, "snapshot.notCatalog");
        if (catalog.version != null && catalog.version > CatalogFile.VERSION) {
            throw new IllegalArgumentException(Messages.get("snapshot.newerVersion", CatalogFile.FORMAT));
        }

        Map<Integer, CatalogFile.Discipline> byId = new LinkedHashMap<>();
        catalog.disciplines.forEach(d -> byId.put(d.id, d));
        Set<Integer> rankingIds = new HashSet<>();
        Set<Integer> referenced = new TreeSet<>();
        boolean anyIndividual = false;
        for (RankingsFile.Ranking ranking : rankings.rankings) {
            if (!rankingIds.add(ranking.id)) {
                throw new IllegalArgumentException(Messages.get("snapshot.duplicateRanking", ranking.id));
            }
            for (RankingsFile.Entry entry : ranking.entries) {
                if (entry.disciplineIds.isEmpty()) {
                    throw new IllegalArgumentException(Messages.get("snapshot.emptyEntry", ranking.name));
                }
                for (int id : entry.disciplineIds) {
                    CatalogFile.Discipline discipline = byId.get(id);
                    if (discipline == null) {
                        throw new IllegalArgumentException(Messages.get("snapshot.unknownDiscipline", ranking.name, id));
                    }
                    if (!ranking.isTeam() && discipline.isTeam()) {
                        throw new IllegalArgumentException(Messages.get("snapshot.teamDiscipline", ranking.name, discipline.name));
                    }
                    referenced.add(id);
                }
            }
            anyIndividual |= !ranking.isTeam() && !ranking.entries.isEmpty();
        }
        if (!anyIndividual) {
            throw new IllegalArgumentException(Messages.get("snapshot.noIndividual"));
        }

        Snapshot snapshot = new Snapshot();
        snapshot.uploadedAt = Instant.now().toString();
        snapshot.rankingsSha256 = sha256(rankingsNode);
        snapshot.catalogSha256 = sha256(catalogNode);
        snapshot.rankings = rankings.rankings;
        snapshot.disciplines = referenced.stream().map(byId::get).collect(Collectors.toList());
        return snapshot;
    }

    /** Hints that do not stop an upload, e.g. a ranking using a discipline the catalog marks inactive. */
    public static List<String> warnings(Snapshot snapshot) {
        List<String> warnings = new ArrayList<>();
        Map<Integer, CatalogFile.Discipline> byId = disciplines(snapshot);
        for (Offer.Ranking ranking : offer(snapshot)) {
            for (Offer.Entry entry : ranking.entries) {
                for (Offer.Discipline d : entry.disciplines) {
                    if (!byId.get(d.id).active) {
                        warnings.add(Messages.get("snapshot.inactive", ranking.name, d.name));
                    }
                }
            }
        }
        return warnings.stream().distinct().collect(Collectors.toList());
    }

    /** The individual rankings with entries, in the uploaded order: what clubs can tick. */
    public static List<Offer.Ranking> offer(Snapshot snapshot) {
        List<Offer.Ranking> offer = new ArrayList<>();
        if (snapshot == null) {
            return offer;
        }
        Map<Integer, CatalogFile.Discipline> byId = disciplines(snapshot);
        for (RankingsFile.Ranking ranking : snapshot.rankings) {
            if (ranking.isTeam() || ranking.entries.isEmpty()) {
                continue;
            }
            List<Offer.Entry> entries = new ArrayList<>();
            Set<String> categories = new TreeSet<>();
            for (int i = 0; i < ranking.entries.size(); i++) {
                RankingsFile.Entry entry = ranking.entries.get(i);
                List<Offer.Discipline> discs = entry.disciplineIds.stream().map(byId::get)
                    .map(d -> new Offer.Discipline(d.id, d.name, d.shortName, d.category, d.type, d.shootingDistance))
                    .collect(Collectors.toList());
                discs.forEach(d -> { if (d.category != null) categories.add(d.category); });
                String label = entry.label != null && !entry.label.isBlank() ? entry.label
                    : discs.stream().map(d -> d.shortName != null ? d.shortName : d.name).collect(Collectors.joining("+"));
                entries.add(new Offer.Entry(i, label, discs));
            }
            String shortName = ranking.shortName != null && !ranking.shortName.isBlank() ? ranking.shortName : ranking.name;
            offer.add(new Offer.Ranking(ranking.id, ranking.name, shortName, String.join(" / ", categories), entries));
        }
        return offer;
    }

    /** The disciplines a choice stands for, or null if the snapshot has no such offered ranking entry. */
    public static List<Integer> disciplinesOf(Snapshot snapshot, Choice choice) {
        if (snapshot == null) {
            return null;
        }
        for (RankingsFile.Ranking ranking : snapshot.rankings) {
            if (ranking.id == choice.rankingId && !ranking.isTeam()) {
                return choice.entry >= 0 && choice.entry < ranking.entries.size()
                    ? ranking.entries.get(choice.entry).disciplineIds : null;
            }
        }
        return null;
    }

    /** A starter's starts: the disciplines of all their choices, each once, in the order of the choices. */
    public static Set<Integer> startsOf(Snapshot snapshot, Starter starter) {
        Set<Integer> starts = new LinkedHashSet<>();
        for (Choice choice : starter.choices) {
            List<Integer> ids = disciplinesOf(snapshot, choice);
            if (ids != null) {
                starts.addAll(ids);
            }
        }
        return starts;
    }

    /**
     * The starters a new snapshot would change: one of their choices is gone
     * from it or now stands for other disciplines than in the old one.
     */
    public static List<Starter> brokenBy(Snapshot oldSnapshot, Snapshot newSnapshot, List<Starter> starters) {
        List<Starter> broken = new ArrayList<>();
        for (Starter starter : starters) {
            for (Choice choice : starter.choices) {
                List<Integer> before = disciplinesOf(oldSnapshot, choice);
                List<Integer> after = disciplinesOf(newSnapshot, choice);
                if (after == null || !Objects.equals(before, after)) {
                    broken.add(starter);
                    break;
                }
            }
        }
        return broken;
    }

    private static Map<Integer, CatalogFile.Discipline> disciplines(Snapshot snapshot) {
        Map<Integer, CatalogFile.Discipline> byId = new LinkedHashMap<>();
        snapshot.disciplines.forEach(d -> byId.put(d.id, d));
        return byId;
    }

    private static <T> T read(JsonNode node, Class<T> type, String format, String notAFileKey) {
        if (node == null || !node.isObject() || !format.equals(node.path("format").asText())) {
            throw new IllegalArgumentException(Messages.get(notAFileKey, format));
        }
        try {
            return JsonFile.MAPPER.treeToValue(node, type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalArgumentException(Messages.get(notAFileKey, format));
        }
    }

    private static String sha256(JsonNode node) {
        try {
            byte[] bytes = JsonFile.MAPPER.writeValueAsString(node).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
