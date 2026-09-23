package com.competition.service;

import com.competition.model.Ranking;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RankingServiceTest {

    @TempDir
    Path tempDir;

    private static String competitor(int id, String name) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"starts\":{\"1\":[{\"generated_id\":\"" + id
            + "-1-1\",\"start_number\":1,\"status\":\"registered\"}]}}";
    }

    @Test
    @SuppressWarnings("unchecked")
    void tieBreakLowerValueWinsAndTotalsComeFromTheShots() throws Exception {
        Files.writeString(tempDir.resolve("disciplines.json"),
            "[{\"id\":1,\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"No 1 Miquelet\"}]");
        // Identical shots; Anna's result is stored the old way, with the override as value
        Files.writeString(tempDir.resolve("data.json"), "{\"competitors\":["
            + competitor(1, "Anna") + "," + competitor(2, "Ben") + "," + competitor(3, "Cara")
            + "],\"results\":["
            + "{\"id\":1,\"start_id\":\"1-1-1\",\"value\":12.5,\"entries\":[10.0,9.0],\"override_value\":12.5},"
            + "{\"id\":2,\"start_id\":\"2-1-1\",\"value\":19,\"entries\":[10,9],\"override_value\":3.0},"
            + "{\"id\":3,\"start_id\":\"3-1-1\",\"value\":19,\"entries\":[10,9],\"override_value\":null}"
            + "]}");
        DataService dataService = new DataService(tempDir.resolve("data.json").toString(),
            tempDir.resolve("disciplines.json").toString(), tempDir.resolve("competition.json").toString());
        RankingService rankingService = new RankingService(dataService, new DisciplineService(dataService),
            new TeamService(tempDir.resolve("teams.json").toString(), dataService));

        List<Ranking> rankings = (List<Ranking>) rankingService.getRanking(1).get("rankings");
        assertEquals(List.of("Ben", "Anna", "Cara"), rankings.stream().map(r -> r.getCompetitor().getName()).toList());
        assertEquals(List.of(1, 2, 3), rankings.stream().map(Ranking::getRank).toList());
        Ranking anna = rankings.get(1);
        assertEquals(19.0, anna.getTotalSum());
        assertEquals(1, anna.getFreqCounts().get("10"), "entries saved as 10.0 count as tens");
    }
}
