package com.competition.service;

import com.competition.model.Discipline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Event names with the MLAIC number and a type suffix ("1_Miquelet_O") still match their event. */
class EventNameMatchingTest {

    @TempDir
    Path tempDir;

    @Test
    void matchKeyDropsNumberAndTypeSuffix() {
        assertEquals("Miquelet", DisciplineService.matchKey("1_Miquelet_O"));
        assertEquals("Miquelet", DisciplineService.matchKey("1_Miquelet_R"));
        assertEquals("Vetterli", DisciplineService.matchKey("15_Vetterli O"));
        assertEquals("Donald Malson", DisciplineService.matchKey("23_Donald Malson R"));
        assertEquals("Walkyrie", DisciplineService.matchKey("8_Walkyrie_O/R"));
        assertEquals("Königgrätz", DisciplineService.matchKey("XX_Königgrätz"));
        assertEquals("Tanegashima", DisciplineService.matchKey("No 14 Tanegashima"));
        assertEquals("Miquelet", DisciplineService.matchKey("No. 1 Miquelet"));
        assertEquals("Colt", DisciplineService.matchKey("Colt"));
        assertEquals("1_Miquelet", DisciplineService.baseEvent("1_Miquelet_O"));
        assertTrue(DisciplineService.sameEvent("7_Colt", "Colt"));
        assertFalse(DisciplineService.sameEvent("Gustav Adolph + Pauly (aggregate)", "9_Gustav Adolph"));
    }

    @Test
    void pairsAggregatesAndTeamsWorkWithNumberedNames() throws Exception {
        Files.writeString(tempDir.resolve("disciplines.json"), "["
            + "{\"id\":1,\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"1_Miquelet_O\"},"
            + "{\"id\":11,\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"reproduction\",\"event\":\"1_Miquelet_R\"},"
            + "{\"id\":33,\"category\":\"rifle\",\"level\":\"team\",\"type\":\"original\",\"event\":\"9_Gustav Adolph\",\"based_on\":\"1_Miquelet_O\"},"
            + "{\"id\":52,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"7_Colt\"},"
            + "{\"id\":53,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"23_Donald Malson O\"},"
            + "{\"id\":58,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"reproduction\",\"event\":\"23_Donald Malson R\"},"
            + "{\"id\":74,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"38_Remington_O\","
            + "\"aggregate_of\":[\"Colt\",\"Donald Malson\"]}]");
        Files.writeString(tempDir.resolve("data.json"), "{\"competitors\":[],\"results\":[]}");
        DataService dataService = new DataService(tempDir.resolve("data.json").toString(),
            tempDir.resolve("disciplines.json").toString(), tempDir.resolve("competition.json").toString());
        DisciplineService disciplineService = new DisciplineService(dataService);

        List<Map<String, Object>> events = disciplineService.getCombinableEvents();
        assertEquals(List.of("rifle|Miquelet", "pistol|Donald Malson"), events.stream().map(e -> e.get("key")).toList());
        assertEquals("1_Miquelet", events.get(0).get("event"));

        Discipline remington = disciplineService.getAvailableDisciplineById(74);
        assertEquals(List.of(52, 53), disciplineService.getComponents(remington).stream().map(Discipline::getId).toList());

        List<Discipline> catalog = dataService.loadDisciplines();
        assertEquals(List.of(1), TeamService.teamOfFromBasedOn(disciplineService.getAvailableDisciplineById(33), catalog));
    }
}
