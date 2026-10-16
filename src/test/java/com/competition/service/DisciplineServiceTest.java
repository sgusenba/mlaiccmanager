package com.competition.service;

import com.competition.model.Discipline;
import com.competition.model.DisciplineCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The installation's discipline catalog: where it starts from, editing, and its export and import. */
class DisciplineServiceTest {

    private static final String SHIPPED = "["
        + "{\"id\":1,\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"original\",\"name\":\"1_Miquelet_O\",\"short_name\":\"MIQO\"},"
        + "{\"id\":11,\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"reproduction\",\"name\":\"1_Miquelet_R\",\"short_name\":\"MIQR\"},"
        + "{\"id\":6,\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"original\",\"name\":\"14_Tanegashima_O\",\"short_name\":\"TANO\"},"
        + "{\"id\":54,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"original\",\"name\":\"28_Tanzutsu_O\",\"short_name\":\"TANO\"},"
        + "{\"id\":31,\"category\":\"rifle\",\"level\":\"team\",\"type\":\"original\",\"name\":\"9_Gustav Adolph\",\"team_of\":[1],\"team_size\":3}"
        + "]";

    @TempDir
    Path tempDir;

    private DataService dataService;
    private DisciplineService disciplineService;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(tempDir.resolve(DataService.DEFAULT_CATALOG_FILE), TestFiles.catalog(SHIPPED));
        Files.writeString(tempDir.resolve("data.json"), "{\"competitors\":[{\"id\":1,\"name\":\"Anna\",\"starts\":"
            + "{\"6\":[{\"generated_id\":\"1-6-1\",\"start_number\":1}]}}],\"results\":[]}");
        dataService = TestFiles.dataService(tempDir);
        TeamService teamService = new TeamService(tempDir.resolve("teams.json").toString(), dataService);
        disciplineService = new DisciplineService(dataService, teamService);
    }

    private static Map<String, Object> individual(String name, String category) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", name);
        data.put("category", category);
        data.put("level", "individual");
        data.put("type", "original");
        return data;
    }

    @Test
    void theCatalogStartsAsShippedAndIsThenThisInstallationsOwn() throws Exception {
        assertFalse(Files.exists(tempDir.resolve("catalog.json")));
        assertEquals(List.of(1, 11, 6, 54, 31),
            disciplineService.getAvailableDisciplines().stream().map(Discipline::getId).toList());
        assertTrue(Files.exists(tempDir.resolve("catalog.json")), "written on first use");

        // A later shipped catalog does not replace it
        Files.writeString(tempDir.resolve(DataService.DEFAULT_CATALOG_FILE), TestFiles.catalog("[]"));
        assertEquals(5, disciplineService.getAvailableDisciplines().size());
    }

    @Test
    void aCatalogOfTheOldLayoutIsKeptAsideAndReplacedByTheShippedOne() throws Exception {
        Files.writeString(tempDir.resolve("catalog.json"), "[{\"id\":74,\"event\":\"38_Remington_O\",\"aggregate_of\":[\"Colt\"]}]");
        assertEquals(5, disciplineService.getAvailableDisciplines().size());
        assertTrue(Files.exists(tempDir.resolve("catalog.legacy.json")));
    }

    @Test
    void theCatalogOfTheFirstRankingsReleaseIsTakenOver() throws Exception {
        // That release kept the catalog in disciplines.json
        Files.writeString(tempDir.resolve("disciplines.json"), TestFiles.catalog(
            "[{\"id\":7,\"category\":\"rifle\",\"level\":\"individual\",\"name\":\"Edited\"}]"));
        assertEquals(List.of("Edited"), disciplineService.getAvailableDisciplines().stream().map(Discipline::getName).toList());
        assertTrue(Files.exists(tempDir.resolve("catalog.json")));
    }

    @Test
    void anOldLayoutDisciplinesJsonIsIgnoredAndTheJarsDefaultsApply() throws Exception {
        // Deploy scripts from before the rankings release copy the old catalog there; nothing shipped next to the app
        Files.writeString(tempDir.resolve("disciplines.json"), "[{\"id\":74,\"event\":\"38_Remington_O\"}]");
        Files.delete(tempDir.resolve(DataService.DEFAULT_CATALOG_FILE));
        List<Discipline> disciplines = disciplineService.getAvailableDisciplines();
        assertTrue(disciplines.size() > 50, "the default catalog built into the jar");
        assertEquals("TNZO", disciplineService.getAvailableDisciplineById(54).getShortName());
        assertTrue(Files.exists(tempDir.resolve("disciplines.json")), "left alone");
    }

    @Test
    void shortNamesNeedNotBeUnique() throws Exception {
        // Rifle Tanegashima and pistol Tanzutsu are both TANO; their ids tell them apart
        assertEquals("TANO", disciplineService.getAvailableDisciplineById(6).getShortName());
        assertEquals("TANO", disciplineService.getAvailableDisciplineById(54).getShortName());
        Map<String, Object> data = individual("Another", "rifle");
        data.put("short_name", "TANO");
        assertEquals(55, disciplineService.createCatalogDiscipline(data).getId(), "next id after the highest");
    }

    @Test
    void disciplinesAreValidated() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> disciplineService.createCatalogDiscipline(individual("", "rifle")));
        assertThrows(IllegalArgumentException.class, () -> disciplineService.createCatalogDiscipline(individual("X", "cannon")));
        Map<String, Object> team = individual("Team", "rifle");
        team.put("level", "team");
        assertThrows(IllegalArgumentException.class, () -> disciplineService.createCatalogDiscipline(team), "no team_of");
        team.put("team_of", List.of(54));
        assertThrows(IllegalArgumentException.class, () -> disciplineService.createCatalogDiscipline(team), "a pistol discipline");
        team.put("team_of", List.of(1, 11));
        Discipline created = disciplineService.createCatalogDiscipline(team);
        assertEquals(3, created.getTeamSize(), "the default team size");
        assertNull(created.getShootingDistance(), "a team takes no lane");
    }

    @Test
    void aDisciplineInUseIsArchivedNotDeleted() throws Exception {
        // Tanegashima has a start, Miquelet is counted by Gustav Adolph
        assertThrows(ConflictException.class, () -> disciplineService.deleteCatalogDiscipline(6));
        assertThrows(ConflictException.class, () -> disciplineService.deleteCatalogDiscipline(1));
        assertThrows(ConflictException.class,
            () -> disciplineService.updateCatalogDiscipline(6, Map.of("category", "pistol")), "its category is used");
        disciplineService.updateCatalogDiscipline(6, Map.of("active", false));
        assertFalse(disciplineService.getAvailableDisciplineById(6).isActive());

        disciplineService.deleteCatalogDiscipline(54);
        assertNull(disciplineService.getAvailableDisciplineById(54));
    }

    @Test
    void exportAndImportMergeOrReplace() throws Exception {
        DisciplineCatalog exported = disciplineService.exportCatalog();
        JsonNode file = mapper.valueToTree(exported);
        assertEquals("mlaic-disciplines", file.path("format").asText());

        // Into another installation, whose catalog has other disciplines
        Path other = tempDir.resolve("other");
        Files.createDirectories(other);
        Files.writeString(other.resolve("catalog.json"), TestFiles.catalog(
            "[{\"id\":1,\"category\":\"rifle\",\"level\":\"individual\",\"name\":\"Old name\"},"
                + "{\"id\":99,\"category\":\"rifle\",\"level\":\"individual\",\"name\":\"Local\"}]"));
        Files.writeString(other.resolve("data.json"), "{\"competitors\":[],\"results\":[]}");
        DisciplineService target = new DisciplineService(TestFiles.dataService(other));

        Map<String, Object> merged = target.importCatalog(file, "merge");
        assertEquals(List.of(11, 6, 54, 31), merged.get("added"));
        assertEquals(List.of(1), merged.get("updated"));
        assertEquals("1_Miquelet_O", target.getAvailableDisciplineById(1).getName());
        assertNotNull(target.getAvailableDisciplineById(99), "merge keeps what the file lacks");

        Map<String, Object> replaced = target.importCatalog(file, "replace");
        assertEquals(List.of(99), replaced.get("removed"));
        assertEquals(List.of(1, 11, 6, 54, 31), target.getAvailableDisciplines().stream().map(Discipline::getId).toList());
    }

    @Test
    void aReplaceThatWouldRemoveADisciplineInUseChangesNothing() throws Exception {
        JsonNode withoutTanegashima = mapper.readTree(TestFiles.catalog(
            "[{\"id\":1,\"category\":\"rifle\",\"level\":\"individual\",\"name\":\"1_Miquelet_O\"}]"));
        assertThrows(ConflictException.class, () -> disciplineService.importCatalog(withoutTanegashima, "replace"));
        assertEquals(5, disciplineService.getAvailableDisciplines().size());
        assertThrows(IllegalArgumentException.class, () -> disciplineService.importCatalog(mapper.readTree("[]"), "merge"),
            "not a disciplines file");
    }
}
