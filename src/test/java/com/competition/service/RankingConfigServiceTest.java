package com.competition.service;

import com.competition.model.RankingConfig;
import com.competition.model.RankingDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A meet's rankings: what may be ranked, the program they make, and their export and import. */
class RankingConfigServiceTest {

    private static final String CATALOG = "["
        + "{\"id\":4,\"category\":\"rifle\",\"level\":\"individual\",\"name\":\"Whitworth_O\",\"short_name\":\"WHITO\"},"
        + "{\"id\":14,\"category\":\"rifle\",\"level\":\"individual\",\"name\":\"Whitworth_R\",\"short_name\":\"WHITR\"},"
        + "{\"id\":1,\"category\":\"rifle\",\"level\":\"individual\",\"name\":\"Miquelet_O\"},"
        + "{\"id\":11,\"category\":\"rifle\",\"level\":\"individual\",\"name\":\"Miquelet_R\"},"
        + "{\"id\":3,\"category\":\"rifle\",\"level\":\"individual\",\"name\":\"Minie_O\"},"
        + "{\"id\":35,\"category\":\"rifle\",\"level\":\"team\",\"name\":\"Rigby\",\"team_of\":[4,14],\"team_size\":3},"
        + "{\"id\":46,\"category\":\"rifle\",\"level\":\"team\",\"name\":\"Versailles\",\"team_of\":[1,11,3],\"team_size\":6}"
        + "]";

    @TempDir
    Path tempDir;

    private DataService dataService;
    private RankingConfigService config;
    private StartService startService;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(tempDir.resolve("catalog.json"), TestFiles.catalog(CATALOG));
        Files.writeString(tempDir.resolve("data.json"), "{\"competitors\":["
            + "{\"id\":1,\"name\":\"Anna\",\"starts\":{}},{\"id\":2,\"name\":\"Ben\",\"starts\":{}}],\"results\":[]}");
        dataService = TestFiles.dataService(tempDir);
        TeamService teamService = new TeamService(tempDir.resolve("teams.json").toString(), dataService);
        config = new RankingConfigService(dataService, teamService, tempDir.resolve("default-rankings.json").toString());
        startService = new StartService(dataService, new DisciplineService(dataService, teamService));
    }

    private static Map<String, Object> ranking(String name, String level, List<?> entries) {
        Map<String, Object> request = new HashMap<>();
        request.put("name", name);
        request.put("level", level);
        request.put("entries", entries);
        return request;
    }

    private static Map<String, Object> single(int id) {
        return Map.of("discipline_ids", List.of(id));
    }

    private static Map<String, Object> sum(String label, Integer... ids) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("label", label);
        entry.put("discipline_ids", List.of(ids));
        return entry;
    }

    @Test
    void aNewMeetHasNoRankingsAndTheCatalogsLimit() throws Exception {
        RankingConfig empty = config.getConfig();
        assertTrue(empty.getRankings().isEmpty());
        assertEquals(4, empty.getMaxDisciplinesPerRanking());
        assertTrue(dataService.loadProgramIds().isEmpty());
    }

    @Test
    void rankingsAreCheckedAgainstTheCatalog() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> config.createRanking(ranking("", "individual", List.of(single(4)))),
            "a name is needed");
        assertThrows(IllegalArgumentException.class, () -> config.createRanking(ranking("X", "individual", List.of())),
            "at least one entry");
        assertThrows(IllegalArgumentException.class, () -> config.createRanking(ranking("X", "individual", List.of(single(99)))),
            "unknown discipline");
        assertThrows(IllegalArgumentException.class, () -> config.createRanking(ranking("X", "individual", List.of(single(35)))),
            "a team discipline in an individual ranking");
        assertThrows(IllegalArgumentException.class, () -> config.createRanking(ranking("X", "team", List.of(single(4)))),
            "an individual discipline in a team ranking");
        assertThrows(IllegalArgumentException.class,
            () -> config.createRanking(ranking("X", "individual", List.of(single(4), sum("Twice", 4, 14)))),
            "a discipline twice");
        assertThrows(IllegalArgumentException.class,
            () -> config.createRanking(ranking("X", "individual", List.of(sum(null, 4, 14)))), "a sum needs a name");
        assertThrows(IllegalArgumentException.class,
            () -> config.createRanking(ranking("X", "individual",
                List.of(single(4), single(14), single(1), single(11), single(3)))), "five disciplines, the limit is 4");
        assertTrue(config.getConfig().getRankings().isEmpty(), "nothing was saved");

        RankingDefinition whitworth = config.createRanking(ranking("Whitworth O/R", "individual", List.of(single(4), single(14))));
        assertEquals(1, whitworth.getId());
        // A discipline may be in several rankings
        config.createRanking(ranking("Whitworth R", "individual", List.of(single(14))));
        assertEquals(List.of(4, 14), List.copyOf(dataService.loadProgramIds()));
    }

    @Test
    void theMeetsLimitAlsoBoundsTheTeamDisciplinesItRanks() throws Exception {
        config.setMaxDisciplines(2);
        // Versailles counts three individual disciplines
        assertThrows(IllegalArgumentException.class, () -> config.createRanking(ranking("Versailles", "team", List.of(single(46)))));
        config.createRanking(ranking("Rigby", "team", List.of(single(35))));
        config.createRanking(ranking("Whitworth O/R", "individual", List.of(single(4), single(14))));
        // Not below what a ranking covers already
        assertThrows(IllegalArgumentException.class, () -> config.setMaxDisciplines(1));
        assertEquals(2, config.getConfig().getMaxDisciplinesPerRanking());
    }

    @Test
    void competitorsStartOnlyInTheProgramsIndividualDisciplines() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> startService.createStart(1, 4), "no ranking covers it yet");
        config.createRanking(ranking("Whitworth O/R", "individual", List.of(single(4), single(14))));
        config.createRanking(ranking("Rigby", "team", List.of(single(35))));
        assertNotNull(startService.createStart(1, 4));
        assertNotNull(startService.createStart(1, 14), "several disciplines of one ranking");
        assertNotNull(startService.createStart(1, 4), "another start in the same discipline");
        assertThrows(IllegalArgumentException.class, () -> startService.createStart(1, 35), "a team discipline");
    }

    @Test
    void aDisciplineWithStartsStaysInTheProgram() throws Exception {
        RankingDefinition whitworth = config.createRanking(ranking("Whitworth O/R", "individual", List.of(single(4), single(14))));
        startService.createStart(2, 14);

        ConflictException conflict = assertThrows(ConflictException.class, () -> config.deleteRanking(whitworth.getId(), null));
        assertTrue(conflict.getMessage().contains("Whitworth_R"));
        // Fine while another ranking still covers it
        config.createRanking(ranking("Whitworth R", "individual", List.of(single(14))));
        config.deleteRanking(whitworth.getId(), null);
        assertEquals(List.of(14), List.copyOf(dataService.loadProgramIds()));
    }

    @Test
    void staleChangesAreRejected() throws Exception {
        RankingDefinition r = config.createRanking(ranking("A", "individual", List.of(single(4))));
        Map<String, Object> edit = ranking("B", "individual", List.of(single(4)));
        edit.put("version", r.getVersion());
        config.updateRanking(r.getId(), edit);
        assertThrows(ConflictException.class, () -> config.updateRanking(r.getId(), edit));
        assertThrows(ConflictException.class, () -> config.deleteRanking(r.getId(), 0));
        assertThrows(RecordNotFoundException.class, () -> config.updateRanking(99, edit));
    }

    @Test
    void rankingsCanBeReordered() throws Exception {
        int a = config.createRanking(ranking("A", "individual", List.of(single(4)))).getId();
        int b = config.createRanking(ranking("B", "individual", List.of(single(14)))).getId();
        int c = config.createRanking(ranking("C", "individual", List.of(single(1)))).getId();
        config.reorder(List.of(c, a));
        assertEquals(List.of("C", "A", "B"), config.getConfig().getRankings().stream().map(RankingDefinition::getName).toList());
    }

    @Test
    void exportAndImportRoundTrip() throws Exception {
        config.createRanking(ranking("Whitworth O/R", "individual", List.of(single(4), single(14))));
        config.createRanking(ranking("Miquelet", "individual", List.of(sum("Miquelet O+R", 1, 11))));
        config.createRanking(ranking("Rigby", "team", List.of(single(35))));
        ObjectNode exported = config.exportRankings();
        assertEquals("mlaic-rankings", exported.path("format").asText());
        assertEquals("[\"WHITO\"]", exported.path("rankings").get(0).path("entries").get(0)
            .path("discipline_short_names").toString(), "named for the reader");

        // Into another meet
        Files.delete(tempDir.resolve("rankings.json"));
        RankingConfig imported = config.importRankings(mapper.readTree(exported.toString()));
        assertEquals(List.of("Whitworth O/R", "Miquelet", "Rigby"),
            imported.getRankings().stream().map(RankingDefinition::getName).toList());
        assertEquals(List.of(4, 14, 1, 11, 35), List.copyOf(dataService.loadProgramIds()));
        assertEquals("Miquelet O+R", imported.getRankings().get(1).getEntries().get(0).getLabel());
    }

    @Test
    void anImportWithAnUnknownDisciplineChangesNothing() throws Exception {
        config.createRanking(ranking("Whitworth O/R", "individual", List.of(single(4), single(14))));
        JsonNode file = mapper.readTree("{\"format\":\"mlaic-rankings\",\"version\":1,\"max_disciplines_per_ranking\":4,"
            + "\"rankings\":[{\"name\":\"Good\",\"entries\":[{\"discipline_ids\":[1]}]},"
            + "{\"name\":\"Bad\",\"entries\":[{\"discipline_ids\":[999]}]}]}");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> config.importRankings(file));
        assertTrue(error.getMessage().contains("999"));
        assertEquals(List.of("Whitworth O/R"), config.getConfig().getRankings().stream().map(RankingDefinition::getName).toList());

        assertThrows(IllegalArgumentException.class, () -> config.importRankings(mapper.readTree("{\"rankings\":[]}")),
            "not a rankings file");
    }

    @Test
    void defaultsLeaveOutWhatTheCatalogLacks() throws Exception {
        Files.writeString(tempDir.resolve("default-rankings.json"), "{\"format\":\"mlaic-rankings\",\"version\":1,"
            + "\"max_disciplines_per_ranking\":4,\"rankings\":["
            + "{\"id\":1,\"name\":\"Whitworth_O\",\"entries\":[{\"discipline_ids\":[4]}]},"
            + "{\"id\":2,\"name\":\"Colt\",\"entries\":[{\"discipline_ids\":[52]}]},"
            + "{\"id\":3,\"name\":\"Miquelet\",\"entries\":[{\"discipline_ids\":[1]},{\"discipline_ids\":[52]}]}]}");
        RankingConfig defaults = config.loadDefaults();
        assertEquals(List.of("Whitworth_O", "Miquelet"), defaults.getRankings().stream().map(RankingDefinition::getName).toList());
        assertEquals(1, defaults.getRankings().get(1).getEntries().size());
    }

    @Test
    void theShippedDefaultRankingsFitTheShippedCatalog() throws Exception {
        Path project = Path.of(System.getProperty("user.dir"));
        Files.copy(project.resolve("default-disciplines.json"), tempDir.resolve("catalog.json"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        RankingConfigService shipped = new RankingConfigService(dataService, null,
            project.resolve("default-rankings.json").toString());
        RankingConfig defaults = shipped.loadDefaults();
        assertEquals(mapper.readTree(project.resolve("default-rankings.json").toFile()).path("rankings").size(),
            defaults.getRankings().size(), "nothing left out");
        assertTrue(defaults.getRankings().stream().anyMatch(r -> r.getName().contains("Remington")
            && r.getEntries().get(0).getDisciplineIds().size() == 2));
    }

    @Test
    void rankingsByDisciplineForTheStartCards() throws Exception {
        config.createRanking(ranking("Whitworth O/R", "individual", List.of(single(4), single(14))));
        config.createRanking(ranking("Whitworth R", "individual", List.of(single(14))));
        Map<Integer, List<Map<String, Object>>> byDiscipline = config.getRankingsByDiscipline();
        assertEquals(List.of("Whitworth O/R"), byDiscipline.get(4).stream().map(r -> r.get("name")).toList());
        assertEquals(List.of("Whitworth O/R", "Whitworth R"), byDiscipline.get(14).stream().map(r -> r.get("name")).toList());
    }
}
