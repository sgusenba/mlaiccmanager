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

    private static Map<String, Object> remington(String type, Integer... ids) {
        Map<String, Object> data = new HashMap<>();
        data.put("event", "Remington");
        data.put("category", "pistol");
        data.put("level", "individual");
        data.put("type", type);
        data.put("aggregate_of", Arrays.asList(ids));
        data.put("shooting_distance", "m25");
        return data;
    }

    @Test
    void createsAnAggregateThatAddsUpTheTickedDisciplines() throws Exception {
        Discipline created = disciplineService.createCatalogDiscipline(remington("original", 52, 53, 52));

        Discipline stored = disciplineService.getAvailableDisciplineById(created.getId());
        assertEquals(List.of(52, 53), stored.getAggregateOf());
        assertTrue(DisciplineService.isAggregate(stored));
        assertEquals(List.of(52, 53), disciplineService.getComponents(stored).stream().map(Discipline::getId).toList());
        assertNull(stored.getShootingDistance(), "an aggregate takes no lane");
    }

    @Test
    void refusesDisciplinesThatDoNotExistOrAreOfAnotherType() throws Exception {
        assertThrows(IllegalArgumentException.class,
            () -> disciplineService.createCatalogDiscipline(remington("original", 52, 999)));
        // Mariette is a reproduction
        assertThrows(IllegalArgumentException.class,
            () -> disciplineService.createCatalogDiscipline(remington("original", 57)));
        assertEquals(4, disciplineService.getAvailableDisciplines().size(), "nothing was added");
    }

    @Test
    void aTeamAddsUpNoEvents() throws Exception {
        Map<String, Object> data = remington("original", 52);
        data.put("level", "team");
        data.put("team_of", List.of(52));
        data.put("team_size", 3);

        assertNull(disciplineService.createCatalogDiscipline(data).getAggregateOf());
    }

    @Test
    void anEmptyListMakesItAPlainDisciplineAgain() throws Exception {
        Discipline created = disciplineService.createCatalogDiscipline(remington("reproduction", 57, 58));
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
    void changingTheTypeIsCheckedAgainstTheDisciplines() throws Exception {
        Discipline created = disciplineService.createCatalogDiscipline(remington("original", 52));

        assertThrows(IllegalArgumentException.class,
            () -> disciplineService.updateCatalogDiscipline(created.getId(), Map.of("type", "reproduction")));
    }

    @Test
    void changingOrDeletingADisciplineThatIsAddedUpIsChecked() throws Exception {
        Discipline created = disciplineService.createCatalogDiscipline(remington("original", 52, 53));

        assertThrows(IllegalArgumentException.class,
            () -> disciplineService.updateCatalogDiscipline(53, Map.of("type", "reproduction")));
        assertThrows(ConflictException.class, () -> disciplineService.deleteCatalogDiscipline(52));
        assertEquals(List.of(52, 53), disciplineService.getAvailableDisciplineById(created.getId()).getAggregateOf());
    }

    @Test
    void eventNamesOfOlderDataBecomeDisciplineIds() throws Exception {
        Files.writeString(tempDir.resolve("disciplines.json"), CATALOG.replace("]", ","
            + "{\"id\":74,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"original\","
            + "\"event\":\"38_Remington_O\",\"aggregate_of\":[\"Colt\",\"Donald Malson\"]}]"));
        // This competition changed the reproduction Remington, still by event name
        Files.writeString(tempDir.resolve("competition.json"), "{\"active_disciplines\":[52,53,57,58,74,1000],"
            + "\"discipline_overrides\":{},\"removed_disciplines\":[],\"combined_events\":[],"
            + "\"custom_disciplines\":[{\"id\":1000,\"category\":\"pistol\",\"level\":\"individual\","
            + "\"type\":\"reproduction\",\"event\":\"38_Remington_R\",\"aggregate_of\":[\"Mariette\",\"Donald Malson\",\"Nonexistent\"]}]}");

        assertEquals(List.of(52, 53), disciplineService.getAvailableDisciplineById(74).getAggregateOf());
        assertEquals(List.of(57, 58), disciplineService.getAvailableDisciplineById(1000).getAggregateOf());

        // The next save stores ids, and the shipped aggregate is not stored as changed
        disciplineService.updateCatalogDiscipline(1000, Map.of("short_name", "REMR"));
        String saved = Files.readString(tempDir.resolve("competition.json"));
        assertTrue(saved.replaceAll("\\s", "").contains("\"aggregate_of\":[57,58]"), saved);
        assertFalse(saved.contains("\"74\""), saved);
    }
}
