package com.competition.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Starts of the removed combined disciplines (21-30, 60-65). */
class LegacyCombinedStartsTest {

    private static final String CATALOG = "["
        + "{\"id\":6,\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"14_Tanegashima_O\"},"
        + "{\"id\":16,\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"reproduction\",\"event\":\"14_Tanegashima_R\"},"
        + "{\"id\":57,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"reproduction\",\"event\":\"12_Mariette\"},"
        + "{\"id\":34,\"category\":\"rifle\",\"level\":\"team\",\"type\":\"open\",\"event\":\"19_Nagashino\",\"team_of\":[6,16]},"
        + "{\"id\":38,\"category\":\"rifle\",\"level\":\"team\",\"type\":\"original\",\"event\":\"27_Nobunaga\",\"team_of\":[6]}"
        + "]";

    @TempDir
    Path tempDir;

    private DataService dataService;
    private DisciplineService disciplineService;

    @BeforeEach
    void setUp() throws Exception {
        // Anna and Ben were entered in the old Tanegashima combined (26), Anna twice; Cara in Mariette combined (63)
        Files.writeString(tempDir.resolve("data.json"), "{\"competitors\":["
            + "{\"id\":1,\"name\":\"Anna\",\"starts\":{\"26\":[{\"generated_id\":\"1-26-1\",\"start_number\":1,\"discipline_id\":26},"
            + "{\"generated_id\":\"1-26-2\",\"start_number\":2,\"discipline_id\":26}]}},"
            + "{\"id\":2,\"name\":\"Ben\",\"starts\":{\"26\":[{\"generated_id\":\"2-26-1\",\"start_number\":1,\"discipline_id\":26}]}},"
            + "{\"id\":3,\"name\":\"Cara\",\"starts\":{\"63\":[{\"generated_id\":\"3-63-1\",\"start_number\":1,\"discipline_id\":63}]}}"
            + "],\"results\":[{\"id\":1,\"start_id\":\"2-26-1\",\"discipline_id\":26,\"competitor_id\":2,\"entries\":[10]}]}");
        Files.writeString(tempDir.resolve("disciplines.json"), CATALOG);
        dataService = new DataService(tempDir.resolve("data.json").toString(),
            tempDir.resolve("disciplines.json").toString(), tempDir.resolve("competition.json").toString());
        disciplineService = new DisciplineService(dataService);
        disciplineService.setActiveDisciplines(List.of(34), null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> startsOf(int competitorId) throws Exception {
        return dataService.read(data -> ((List<Map<String, Object>>) data.get("competitors")).stream()
            .filter(c -> ((Number) c.get("id")).intValue() == competitorId).findFirst()
            .map(c -> (Map<String, Object>) c.get("starts")).orElseThrow());
    }

    private int resultDisciplineId() throws Exception {
        Object id = dataService.read(data -> ((Map<?, ?>) ((List<?>) data.get("results")).get(0)).get("discipline_id"));
        return ((Number) id).intValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void startsMoveToTheOriginalKeepingTheirIdsAndTheEventIsRankedCombined() throws Exception {
        assertEquals(Map.of(26, 3, 63, 1), LegacyCombinedStarts.migrate(dataService));

        Map<String, Object> anna = startsOf(1);
        assertEquals(Set.of("6"), anna.keySet());
        List<Map<String, Object>> annaStarts = (List<Map<String, Object>>) anna.get("6");
        assertEquals(List.of("1-26-1", "1-26-2"), annaStarts.stream().map(s -> s.get("generated_id")).toList());
        assertEquals(6, annaStarts.get(0).get("discipline_id"));
        // A one-type event: to its only discipline, nothing to combine
        assertEquals(Set.of("57"), startsOf(3).keySet());
        // The result follows its start
        assertEquals(6, resultDisciplineId());

        assertEquals(Set.of("rifle|Tanegashima"), dataService.loadCombinedEvents());
        assertEquals(Set.of(6, 16, 57, 34), Set.copyOf(disciplineService.getActiveDisciplines()));
        // Once only
        assertEquals(Map.of(), LegacyCombinedStarts.migrate(dataService));
    }

}
