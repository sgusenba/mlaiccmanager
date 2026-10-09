package com.competition.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The meet's program: which disciplines of each event are shot, which are combined, and which team rankings there are. */
class ProgramServiceTest {

    private static final String CATALOG = "["
        + individual(6, "original", "14_Tanegashima_O") + ","
        + individual(16, "reproduction", "14_Tanegashima_R") + ","
        + individual(7, "original", "15_Vetterli_O") + ","
        + individual(17, "reproduction", "15_Vetterli_R") + ","
        + "{\"id\":52,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"7_Colt\"},"
        + "{\"id\":74,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"38_Remington_O\",\"aggregate_of\":[\"Colt\"]},"
        + "{\"id\":75,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"reproduction\",\"event\":\"38_Remington_R\",\"aggregate_of\":[\"Mariette\"]},"
        + "{\"id\":34,\"category\":\"rifle\",\"level\":\"team\",\"type\":\"open\",\"event\":\"19_Nagashino\",\"team_of\":[6,16]},"
        + "{\"id\":38,\"category\":\"rifle\",\"level\":\"team\",\"type\":\"original\",\"event\":\"27_Nobunaga\",\"team_of\":[6]}"
        + "]";

    private static String individual(int id, String type, String event) {
        return "{\"id\":" + id + ",\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"" + type
            + "\",\"event\":\"" + event + "\"}";
    }

    @TempDir
    Path tempDir;

    private DataService dataService;
    private ProgramService programService;

    @BeforeEach
    void setUp() throws Exception {
        // Anna starts in Tanegashima O and R, Ben in Vetterli O
        Files.writeString(tempDir.resolve("data.json"), "{\"competitors\":["
            + "{\"id\":1,\"name\":\"Anna\",\"starts\":{\"6\":[{\"generated_id\":\"1-6-1\",\"start_number\":1}],"
            + "\"16\":[{\"generated_id\":\"1-16-1\",\"start_number\":1}]}},"
            + "{\"id\":2,\"name\":\"Ben\",\"starts\":{\"7\":[{\"generated_id\":\"2-7-1\",\"start_number\":1}]}}"
            + "],\"results\":[]}");
        Files.writeString(tempDir.resolve("disciplines.json"), CATALOG);
        dataService = new DataService(tempDir.resolve("data.json").toString(),
            tempDir.resolve("disciplines.json").toString(), tempDir.resolve("competition.json").toString());
        DisciplineService disciplineService = new DisciplineService(dataService);
        disciplineService.setActiveDisciplines(List.of(6, 16, 7, 52, 34, 38), null);
        programService = new ProgramService(dataService, disciplineService,
            new TeamService(tempDir.resolve("teams.json").toString(), dataService));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> event(Map<String, Object> program, String key) {
        return ((List<Map<String, Object>>) program.get("events")).stream()
            .filter(e -> key.equals(e.get("key"))).findFirst().orElseThrow();
    }

    /** The ids of the event's disciplines that are shot. */
    @SuppressWarnings("unchecked")
    private static List<Object> shot(Map<String, Object> program, String key) {
        return ((List<Map<String, Object>>) event(program, key).get("disciplines")).stream()
            .filter(d -> Boolean.TRUE.equals(d.get("active"))).map(d -> d.get("id")).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> team(Map<String, Object> program, int id) {
        return ((List<Map<String, Object>>) program.get("teams")).stream()
            .filter(t -> ((Number) t.get("id")).intValue() == id).findFirst().orElseThrow();
    }

    private static Map<String, Object> choice(boolean combined, Integer... active) {
        return Map.of("active", List.of(active), "combined", combined);
    }

    private Map<String, Object> save(Map<String, Object> program, Map<String, Object> events, List<Integer> teams) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("events", events);
        request.put("teams", teams);
        request.put("base", program.get("base"));
        return programService.setProgram(request);
    }

    @Test
    @SuppressWarnings("unchecked")
    void groupsDisciplinesIntoEvents() throws Exception {
        Map<String, Object> program = programService.getProgram();
        List<Map<String, Object>> events = (List<Map<String, Object>>) program.get("events");
        assertEquals(List.of("rifle|Tanegashima", "rifle|Vetterli", "pistol|Colt", "pistol|Remington"),
            events.stream().map(e -> e.get("key")).toList());

        Map<String, Object> tanegashima = event(program, "rifle|Tanegashima");
        assertEquals("14_Tanegashima", tanegashima.get("name"));
        assertEquals(true, tanegashima.get("combinable"));
        assertEquals(false, tanegashima.get("combined"));
        assertEquals(List.of(6, 16), shot(program, "rifle|Tanegashima"));

        assertEquals(List.of(7), shot(program, "rifle|Vetterli"), "only Vetterli O is shot");
        assertEquals(false, event(program, "pistol|Colt").get("combinable"));
        assertEquals(List.of(), shot(program, "pistol|Remington"));
        assertEquals(false, event(program, "pistol|Remington").get("combinable"), "aggregates are not combined");

        assertEquals(List.of("14_Tanegashima_O", "14_Tanegashima_R"), team(program, 34).get("composition"));
        assertEquals(true, team(program, 38).get("active"));
    }

    @Test
    void setsWhichDisciplinesAreShot() throws Exception {
        Map<String, Object> program = save(programService.getProgram(), Map.of(
            "rifle|Vetterli", choice(false, 7, 17),
            "rifle|Tanegashima", choice(false, 16),
            "pistol|Colt", choice(false),
            "pistol|Remington", choice(false, 74)), List.of(34));

        assertEquals(List.of(7, 17), shot(program, "rifle|Vetterli"));
        assertEquals(List.of(16), shot(program, "rifle|Tanegashima"), "reproduction only");
        assertEquals(List.of(), shot(program, "pistol|Colt"));
        assertEquals(List.of(74), shot(program, "pistol|Remington"));
        assertEquals(Set.of(16, 7, 17, 74, 34), Set.copyOf(new DisciplineService(dataService).getActiveDisciplines()));
        assertEquals(false, team(program, 38).get("active"));
    }

    @Test
    void combinesAndSeparatesAgain() throws Exception {
        // Anna's reproduction start is moved away, so Tanegashima can be combined
        Files.writeString(tempDir.resolve("data.json"), Files.readString(tempDir.resolve("data.json"))
            .replace(",\"16\":[{\"generated_id\":\"1-16-1\",\"start_number\":1}]", ""));

        Map<String, Object> program = save(programService.getProgram(),
            Map.of("rifle|Tanegashima", choice(true, 6, 16)), null);
        assertEquals(true, event(program, "rifle|Tanegashima").get("combined"));
        assertEquals(Set.of("rifle|Tanegashima"), dataService.loadCombinedEvents());

        program = save(program, Map.of("rifle|Tanegashima", choice(false, 6)), null);
        assertEquals(false, event(program, "rifle|Tanegashima").get("combined"));
        assertTrue(dataService.loadCombinedEvents().isEmpty());
        assertEquals(true, team(program, 34).get("active"), "teams left out of the request stay");
    }

    @Test
    void combiningNeedsBothOriginalAndReproduction() throws Exception {
        Map<String, Object> program = programService.getProgram();
        assertThrows(IllegalArgumentException.class,
            () -> save(program, Map.of("rifle|Vetterli", choice(true, 7)), null));
        assertTrue(dataService.loadCombinedEvents().isEmpty());
    }

    @Test
    void combiningIsRefusedWhileSomeoneStartsInBoth() throws Exception {
        Map<String, Object> program = programService.getProgram();
        ConflictException e = assertThrows(ConflictException.class,
            () -> save(program, Map.of("rifle|Tanegashima", choice(true, 6, 16)), null));
        assertTrue(e.getMessage().contains("Anna"));
        assertTrue(dataService.loadCombinedEvents().isEmpty());
    }

    @Test
    void rejectsWhatDoesNotFit() throws Exception {
        Map<String, Object> program = programService.getProgram();
        assertThrows(IllegalArgumentException.class, () -> save(program, Map.of("pistol|Colt", choice(true, 52)), null));
        assertThrows(IllegalArgumentException.class, () -> save(program, Map.of("pistol|Remington", choice(true, 74, 75)), null));
        assertThrows(IllegalArgumentException.class, () -> save(program, Map.of("rifle|Nothing", choice(false)), null));
        assertThrows(IllegalArgumentException.class, () -> save(program, Map.of("rifle|Vetterli", choice(false, 52)), null),
            "Colt is not part of Vetterli");
        assertThrows(IllegalArgumentException.class, () -> save(program, Map.of("rifle|Vetterli", "off"), null));
    }

    @Test
    void aStaleBaseIsRefused() throws Exception {
        Map<String, Object> stale = programService.getProgram();
        save(programService.getProgram(), Map.of("pistol|Colt", choice(false)), null);
        ConflictException e = assertThrows(ConflictException.class,
            () -> save(stale, Map.of("rifle|Vetterli", choice(false)), null));
        assertNotNull(e.getCurrent(), "carries the current program");
        assertEquals(List.of(7), shot(programService.getProgram(), "rifle|Vetterli"), "nothing saved");
    }
}
