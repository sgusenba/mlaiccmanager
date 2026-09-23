package com.competition.service;

import com.competition.model.Competitor;
import com.competition.model.Result;
import com.competition.model.Start;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StartIdTest {

    @TempDir
    Path tempDir;

    private CompetitorService competitorService;
    private StartService startService;
    private ResultService resultService;

    @BeforeEach
    void setUp() {
        DataService dataService = new DataService(tempDir.resolve("data.json").toString(), tempDir.resolve("disciplines.json").toString(),
            tempDir.resolve("competition.json").toString());
        competitorService = new CompetitorService(dataService);
        startService = new StartService(dataService);
        resultService = new ResultService(dataService, new DisciplineService(dataService));
    }

    private void writeData(String competitors, String results) throws Exception {
        Files.writeString(tempDir.resolve("data.json"),
            "{\"competitors\":[" + competitors + "],\"results\":[" + results + "],"
                + "\"disciplines\":[],\"teams\":[],\"active_disciplines\":[]}");
    }

    private static String legacyStart(String id, int number, int disciplineId) {
        return "{\"generated_id\":\"" + id + "\",\"start_number\":" + number
            + ",\"discipline_id\":" + disciplineId + ",\"status\":\"registered\"}";
    }

    @Test
    void idsDoNotCollideAcrossCompetitorsAndLegacyIdsKeepWorking() throws Exception {
        // Competitor 1 / discipline 12 / start 3 was stored as "1123" by the old format
        writeData(
            "{\"id\":1,\"name\":\"One\",\"starts\":{\"12\":["
                + legacyStart("1121", 1, 12) + "," + legacyStart("1122", 2, 12) + "," + legacyStart("1123", 3, 12)
                + "]}},"
                + "{\"id\":11,\"name\":\"Eleven\",\"starts\":{}}",
            "{\"id\":1,\"competitor_id\":1,\"discipline_id\":12,\"value\":95,\"start_id\":\"1123\"}");

        // Competitor 11 / discipline 2 / start 3 produced "1123" too before the fix
        startService.createStart(11, 2);
        startService.createStart(11, 2);
        Start third = startService.createStart(11, 2);
        assertEquals("11-2-3", third.getGeneratedId());

        // A result for the new start is not blocked by competitor 1's result on "1123"
        Result result = new Result(11, 2, 80);
        result.setStartId(third.getGeneratedId());
        resultService.createResult(result);

        // Deleting the new start leaves competitor 1's legacy start and result alone
        startService.deleteStart(11, third.getGeneratedId());
        Result duplicate = new Result(1, 12, 50);
        duplicate.setStartId("1123");
        ConflictException conflict = assertThrows(ConflictException.class, () -> resultService.createResult(duplicate));
        Result legacyResult = (Result) conflict.getCurrent();
        assertEquals(1, legacyResult.getCompetitorId());
        assertEquals(95.0, legacyResult.getValue());

        Competitor one = competitorService.getCompetitorById(1);
        assertEquals("1123", one.getStarts().get("12").get(2).getGeneratedId());
        assertEquals("1-12-4", startService.createStart(1, 12).getGeneratedId());
    }

    @Test
    void creatingStartRejectsIdUsedByAnotherCompetitor() throws Exception {
        writeData(
            "{\"id\":1,\"name\":\"One\",\"starts\":{}},"
                + "{\"id\":2,\"name\":\"Two\",\"starts\":{\"5\":[" + legacyStart("1-5-1", 1, 5) + "]}}",
            "");

        assertThrows(ConflictException.class, () -> startService.createStart(1, 5));
        assertTrue(competitorService.getCompetitorById(1).getStarts().isEmpty(), "rejected start must not be saved");
    }

    @Test
    void creatingStartRejectsIdUsedByOrphanedResult() throws Exception {
        writeData(
            "{\"id\":1,\"name\":\"One\",\"starts\":{}}",
            "{\"id\":1,\"competitor_id\":1,\"discipline_id\":5,\"value\":70,\"start_id\":\"1-5-1\"}");

        assertThrows(ConflictException.class, () -> startService.createStart(1, 5));
    }
}
