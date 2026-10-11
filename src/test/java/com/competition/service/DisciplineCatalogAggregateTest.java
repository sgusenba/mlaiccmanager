package com.competition.service;

import com.competition.model.Discipline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Creating and editing aggregate disciplines (like Remington) in the discipline manager. */
class DisciplineCatalogAggregateTest {

    private static final String CATALOG =
        "[{\"id\":52,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"38_Colt_O\"},"
            + "{\"id\":53,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"39_Donald Malson_O\"},"
            + "{\"id\":57,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"reproduction\",\"event\":\"Mariette\"},"
            + "{\"id\":58,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"reproduction\",\"event\":\"39_Donald Malson_R\"}]";

    @TempDir
    Path tempDir;

    private DisciplineService disciplineService;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(tempDir.resolve("data.json"), "{\"competitors\":[],\"results\":[]}");
        Files.writeString(tempDir.resolve("disciplines.json"), CATALOG);
        DataService dataService = new DataService(tempDir.resolve("data.json").toString(),
            tempDir.resolve("disciplines.json").toString(), tempDir.resolve("competition.json").toString());
        disciplineService = new DisciplineService(dataService);
    }

    private static Map<String, Object> remington(String type, String... events) {
        Map<String, Object> data = new HashMap<>();
        data.put("event", "Remington");
        data.put("category", "pistol");
        data.put("level", "individual");
        data.put("type", type);
        data.put("aggregate_of", Arrays.asList(events));
        data.put("shooting_distance", "m25");
        return data;
    }

    @Test
    void createsAnAggregateThatAddsUpTheNamedEvents() throws Exception {
        Discipline created = disciplineService.createCatalogDiscipline(remington("original", "Colt", " Donald Malson ", "Colt"));

        Discipline stored = disciplineService.getAvailableDisciplineById(created.getId());
        assertEquals(List.of("Colt", "Donald Malson"), stored.getAggregateOf());
        assertTrue(DisciplineService.isAggregate(stored));
        assertEquals(List.of(52, 53), disciplineService.getComponents(stored).stream().map(Discipline::getId).toList());
        assertNull(stored.getShootingDistance(), "an aggregate takes no lane");
    }

    @Test
    void refusesEventsWithoutADisciplineOfItsType() throws Exception {
        assertThrows(IllegalArgumentException.class,
            () -> disciplineService.createCatalogDiscipline(remington("original", "Colt", "Nonexistent")));
        // Mariette exists only as a reproduction
        assertThrows(IllegalArgumentException.class,
            () -> disciplineService.createCatalogDiscipline(remington("original", "Mariette")));
        assertEquals(4, disciplineService.getAvailableDisciplines().size(), "nothing was added");
    }

    @Test
    void aTeamAddsUpNoEvents() throws Exception {
        Map<String, Object> data = remington("original", "Colt");
        data.put("level", "team");
        data.put("team_of", List.of(52));
        data.put("team_size", 3);

        assertNull(disciplineService.createCatalogDiscipline(data).getAggregateOf());
    }

    @Test
    void anEmptyListMakesItAPlainDisciplineAgain() throws Exception {
        Discipline created = disciplineService.createCatalogDiscipline(remington("reproduction", "Mariette", "Donald Malson"));
        assertEquals(List.of(57, 58), disciplineService.getComponents(created).stream().map(Discipline::getId).toList());

        Map<String, Object> update = new HashMap<>();
        update.put("aggregate_of", List.of());
        disciplineService.updateCatalogDiscipline(created.getId(), update);
        assertFalse(DisciplineService.isAggregate(disciplineService.getAvailableDisciplineById(created.getId())));

        update.put("aggregate_of", null);
        update.put("shooting_distance", "m25");
        disciplineService.updateCatalogDiscipline(created.getId(), update);
        assertEquals("m25", disciplineService.getAvailableDisciplineById(created.getId()).getShootingDistance());
    }

    @Test
    void changingTheTypeIsCheckedAgainstTheEvents() throws Exception {
        Discipline created = disciplineService.createCatalogDiscipline(remington("original", "Colt"));

        assertThrows(IllegalArgumentException.class,
            () -> disciplineService.updateCatalogDiscipline(created.getId(), Map.of("type", "reproduction")));
    }
}
