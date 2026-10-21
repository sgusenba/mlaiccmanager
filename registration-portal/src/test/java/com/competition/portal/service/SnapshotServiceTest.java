package com.competition.portal.service;

import com.competition.portal.model.Choice;
import com.competition.portal.model.Offer;
import com.competition.portal.model.Snapshot;
import com.competition.portal.model.Starter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotServiceTest {

    private static Offer.Ranking ranking(List<Offer.Ranking> offer, String name) {
        return offer.stream().filter(r -> r.name.equals(name)).findFirst().orElseThrow();
    }

    @Test
    void readsTheManagersShippedDefaults() {
        // Contract: if the Manager's export format changes, this fails
        Snapshot snapshot = SnapshotService.parse(Fixtures.repoFile("default-rankings.json"),
            Fixtures.repoFile("default-disciplines.json"));
        List<Offer.Ranking> offer = SnapshotService.offer(snapshot);
        assertFalse(offer.isEmpty());
        assertTrue(offer.stream().allMatch(r -> !r.entries.isEmpty()));
        assertTrue(offer.stream().flatMap(r -> r.entries.stream()).flatMap(e -> e.disciplines.stream())
            .allMatch(d -> d.name != null && d.category != null));
    }

    @Test
    void offersTheIndividualRankingsOfARealMeet() {
        Snapshot snapshot = SnapshotService.parse(Fixtures.ooeRankings(), Fixtures.mlaicCatalog());
        List<Offer.Ranking> offer = SnapshotService.offer(snapshot);

        assertEquals(21, offer.size(), "the team rankings are left out");
        assertEquals(33, snapshot.rankings.size(), "but kept in the snapshot (21 individual, 12 team)");

        Offer.Ranking whitworth = ranking(offer, "Whitworth O/R");
        assertEquals("WHIT O/R", whitworth.shortName);
        assertEquals("rifle", whitworth.category);
        assertEquals(List.of("WHITO", "WHITR"), whitworth.entries.stream().map(e -> e.label).collect(Collectors.toList()));

        Offer.Ranking remington = ranking(offer, "Remington O/R");
        assertEquals(List.of("REMO", "REMR"), remington.entries.stream().map(e -> e.label).collect(Collectors.toList()));
        assertEquals(List.of("7_Colt", "23_Donald Malson O"),
            remington.entries.get(0).disciplines.stream().map(d -> d.name).collect(Collectors.toList()));
        assertEquals("pistol", remington.category);

        assertTrue(snapshot.disciplines.stream().noneMatch(d -> d.id == 31), "only disciplines the rankings use are kept");
        assertEquals(64, snapshot.rankingsSha256.length());
    }

    @Test
    void startsAreTheDisciplinesOfAllChoicesEachOnce() {
        Snapshot snapshot = SnapshotService.parse(Fixtures.ooeRankings(), Fixtures.mlaicCatalog());
        Starter starter = new Starter();
        starter.choices = List.of(new Choice(69, 0), new Choice(88, 0), new Choice(84, 0), new Choice(84, 1));
        // Colt alone and in Remington O count once; Whitworth O and R both
        assertEquals(List.of(52, 53, 4, 14), List.copyOf(SnapshotService.startsOf(snapshot, starter)));
        assertNull(SnapshotService.disciplinesOf(snapshot, new Choice(84, 2)), "no such entry");
        assertNull(SnapshotService.disciplinesOf(snapshot, new Choice(90, 0)), "team rankings are not offered");
    }

    @Test
    void refusesFilesThatAreNotTheManagersOrDoNotFit() {
        ObjectNode rankings = Fixtures.ooeRankings();
        ObjectNode catalog = Fixtures.mlaicCatalog();
        assertThrows(IllegalArgumentException.class, () -> SnapshotService.parse(catalog, catalog), "catalog as rankings");
        assertThrows(IllegalArgumentException.class, () -> SnapshotService.parse(rankings, rankings), "rankings as catalog");
        assertThrows(IllegalArgumentException.class, () -> SnapshotService.parse(null, catalog));

        ObjectNode newer = rankings.deepCopy().put("version", 2);
        assertThrows(IllegalArgumentException.class, () -> SnapshotService.parse(newer, catalog));

        ObjectNode unknownId = rankings.deepCopy();
        ((ArrayNode) unknownId.get("rankings").get(0).get("entries").get(0).get("discipline_ids")).add(999);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SnapshotService.parse(unknownId, catalog));
        assertTrue(e.getMessage().contains("999"));

        ObjectNode teamInIndividual = rankings.deepCopy();
        ((ArrayNode) teamInIndividual.get("rankings").get(0).get("entries").get(0).get("discipline_ids")).add(35);
        assertThrows(IllegalArgumentException.class, () -> SnapshotService.parse(teamInIndividual, catalog));

        ObjectNode onlyTeams = rankings.deepCopy();
        ArrayNode list = (ArrayNode) onlyTeams.get("rankings");
        for (int i = list.size() - 1; i >= 0; i--) {
            if (!"team".equals(list.get(i).path("level").asText())) {
                list.remove(i);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> SnapshotService.parse(onlyTeams, catalog));

        ObjectNode duplicate = rankings.deepCopy();
        ((ArrayNode) duplicate.get("rankings")).add(duplicate.get("rankings").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> SnapshotService.parse(duplicate, catalog));
    }

    @Test
    void warnsAboutInactiveDisciplines() {
        ObjectNode catalog = Fixtures.mlaicCatalog();
        for (var d : catalog.get("disciplines")) {
            if (d.get("id").asInt() == 52) {
                ((ObjectNode) d).put("active", false);
            }
        }
        Snapshot snapshot = SnapshotService.parse(Fixtures.ooeRankings(), catalog);
        assertTrue(SnapshotService.warnings(snapshot).stream().anyMatch(w -> w.contains("7_Colt")));
        assertTrue(SnapshotService.warnings(SnapshotService.parse(Fixtures.ooeRankings(), Fixtures.mlaicCatalog())).isEmpty());
    }

    @Test
    void findsStartersANewSnapshotWouldChange() {
        Snapshot before = SnapshotService.parse(Fixtures.ooeRankings(), Fixtures.mlaicCatalog());
        Starter whitworthR = new Starter();
        whitworthR.choices = List.of(new Choice(84, 1));
        Starter colt = new Starter();
        colt.choices = List.of(new Choice(69, 0));

        assertTrue(SnapshotService.brokenBy(before, before, List.of(whitworthR, colt)).isEmpty(), "same rankings");

        ObjectNode changed = Fixtures.ooeRankings();
        for (var r : changed.get("rankings")) {
            if (r.get("id").asInt() == 84) {
                ((ArrayNode) r.get("entries")).remove(1); // Whitworth R dropped
            }
        }
        Snapshot after = SnapshotService.parse(changed, Fixtures.mlaicCatalog());
        assertEquals(List.of(whitworthR), SnapshotService.brokenBy(before, after, List.of(whitworthR, colt)));
    }
}
