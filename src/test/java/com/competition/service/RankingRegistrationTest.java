package com.competition.service;

import com.competition.model.Result;
import com.competition.model.Start;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Competitors are registered for each ranking on its own, a sum like
 * Remington_O (Colt + Donald Malson O) included: shooting Colt and Malson O
 * does not put a starter into Remington_O, and one Colt start counts in every
 * ranking it is registered for.
 */
class RankingRegistrationTest {

    private static final int COLT = 1;
    private static final int MALSON = 2;
    private static final int REMINGTON = 3;

    private static final String CATALOG = "["
        + "{\"id\":52,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"original\",\"name\":\"7_Colt\",\"short_name\":\"COLT\"},"
        + "{\"id\":53,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"original\",\"name\":\"23_Donald Malson O\",\"short_name\":\"MALO\"}"
        + "]";

    private static final String RANKINGS = "{\"max_disciplines_per_ranking\":4,\"rankings\":["
        + "{\"id\":1,\"name\":\"7_Colt\",\"entries\":[{\"discipline_ids\":[52]}]},"
        + "{\"id\":2,\"name\":\"23_Donald Malson O\",\"entries\":[{\"discipline_ids\":[53]}]},"
        + "{\"id\":3,\"name\":\"38_Remington_O\",\"entries\":[{\"label\":\"REMO\",\"discipline_ids\":[52,53]}]}"
        + "],\"next_id\":4}";

    @TempDir
    Path tempDir;

    private StartService startService;
    private ResultService resultService;
    private RankingService rankingService;
    private CascadingDeletes cascadingDeletes;
    private CompetitorService competitorService;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(tempDir.resolve("catalog.json"), TestFiles.catalog(CATALOG));
        Files.writeString(tempDir.resolve("rankings.json"), RANKINGS);
        Files.writeString(tempDir.resolve("data.json"), "{\"competitors\":["
            + "{\"id\":1,\"name\":\"Anna\",\"starts\":{}},"
            + "{\"id\":2,\"name\":\"Ben\",\"starts\":{}}"
            + "],\"results\":[]}");
        DataService dataService = TestFiles.dataService(tempDir);
        DisciplineService disciplineService = new DisciplineService(dataService);
        TeamService teamService = new TeamService(tempDir.resolve("teams.json").toString(), dataService);
        competitorService = new CompetitorService(dataService);
        startService = new StartService(dataService, disciplineService);
        resultService = new ResultService(dataService, disciplineService);
        rankingService = new RankingService(dataService, teamService);
        RelayService relayService = new RelayService(tempDir.resolve("relays.json").toString(), dataService);
        cascadingDeletes = new CascadingDeletes(startService, competitorService, teamService, relayService);
    }

    private void result(String startId, double value) throws Exception {
        Result result = new Result(0, 0, value);
        result.setStartId(startId);
        resultService.createResult(result);
    }

    @SuppressWarnings("unchecked")
    private List<Object> names(int rankingId) throws Exception {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) rankingService.getRankingResult(rankingId).get("rankings");
        return rows.stream().map(r -> (Object) ((Map<?, ?>) r.get("competitor")).get("name")).toList();
    }

    @Test
    void shootingBothDisciplinesOfASumDoesNotRegisterForTheSum() throws Exception {
        String colt = startService.registerForRanking(1, COLT, 0, null).get(0).getGeneratedId();
        String malson = startService.registerForRanking(1, MALSON, 0, null).get(0).getGeneratedId();
        result(colt, 90);
        result(malson, 80);

        assertEquals(List.of("Anna"), names(COLT));
        assertEquals(List.of("Anna"), names(MALSON));
        assertEquals(List.of(), names(REMINGTON), "Anna is not registered for Remington_O");
    }

    @Test
    void registeringForTheSumOnlyCountsThereAndAddsAStartPerDiscipline() throws Exception {
        List<Start> starts = startService.registerForRanking(2, REMINGTON, 0, ".44");
        assertEquals(List.of(52, 53), starts.stream().map(Start::getDisciplineId).toList());
        assertEquals(List.of(".44", ".44"), starts.stream().map(Start::getCaliber).toList());
        assertTrue(starts.stream().allMatch(s -> s.getRankingIds().equals(List.of(REMINGTON))));
        result(starts.get(0).getGeneratedId(), 90);
        result(starts.get(1).getGeneratedId(), 80);

        assertEquals(List.of("Ben"), names(REMINGTON));
        assertEquals(List.of(), names(COLT), "Ben is registered for Remington_O only");
    }

    @Test
    void oneColtStartCountsInEveryRankingItIsRegisteredFor() throws Exception {
        Start colt = startService.registerForRanking(1, COLT, 0, ".44").get(0);
        List<Start> remington = startService.registerForRanking(1, REMINGTON, 0, null);

        assertEquals(colt.getGeneratedId(), remington.get(0).getGeneratedId(), "the Colt start is shared");
        assertEquals(List.of(COLT, REMINGTON), remington.get(0).getRankingIds());
        assertEquals(".44", remington.get(0).getCaliber());
        assertEquals(List.of(REMINGTON), remington.get(1).getRankingIds(), "Malson O is a new start");
        assertEquals(2, competitorService.getCompetitorById(1).getStarts().values().stream().mapToInt(List::size).sum());

        result(colt.getGeneratedId(), 90);
        assertEquals(List.of("Anna"), names(COLT));
        assertEquals(List.of("Anna"), names(REMINGTON));

        assertThrows(IllegalArgumentException.class, () -> startService.registerForRanking(1, REMINGTON, 0, null),
            "already registered");
    }

    @Test
    void withdrawingKeepsSharedStartsAndDeletesTheRest() throws Exception {
        Start colt = startService.registerForRanking(1, COLT, 0, null).get(0);
        String malson = startService.registerForRanking(1, REMINGTON, 0, null).get(1).getGeneratedId();
        result(colt.getGeneratedId(), 90);
        result(malson, 80);

        cascadingDeletes.withdrawFromRanking(1, REMINGTON);

        Map<String, List<Start>> starts = competitorService.getCompetitorById(1).getStarts();
        assertEquals(Set.of("52"), starts.keySet(), "the Malson O start was only for Remington_O");
        assertEquals(List.of(COLT), starts.get("52").get(0).getRankingIds());
        assertEquals(List.of("Anna"), names(COLT), "the Colt result stays");
        assertEquals(List.of(), names(REMINGTON));
        assertThrows(RecordNotFoundException.class, () -> cascadingDeletes.withdrawFromRanking(1, REMINGTON));
    }

    @Test
    void startsAddedByDisciplineAloneCountEverywhere() throws Exception {
        Start colt = startService.createStart(1, 52);
        assertNull(colt.getRankingIds());
        result(colt.getGeneratedId(), 90);
        assertEquals(List.of("Anna"), names(COLT));
        assertEquals(List.of("Anna"), names(REMINGTON));
    }
}
