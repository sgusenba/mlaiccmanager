package com.competition.service;

import com.competition.model.Competitor;
import com.competition.model.Result;
import com.competition.model.Start;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyAndVersionTest {

    @TempDir
    Path tempDir;

    private DataService dataService;
    private CompetitorService competitorService;
    private StartService startService;
    private ResultService resultService;
    private DisciplineService disciplineService;

    @BeforeEach
    void setUp() throws Exception {
        writeCatalog(tempDir);
        dataService = TestFiles.dataService(tempDir);
        competitorService = new CompetitorService(dataService);
        disciplineService = new DisciplineService(dataService);
        startService = new StartService(dataService, disciplineService);
        resultService = new ResultService(dataService, disciplineService);
    }

    /** A catalog of individual disciplines 1-12, each in a ranking, so starts can be created in them. */
    static void writeCatalog(java.nio.file.Path dir) throws java.io.IOException {
        StringBuilder catalog = new StringBuilder("[");
        for (int id = 1; id <= 12; id++) {
            catalog.append(id > 1 ? "," : "").append("{\"id\":").append(id)
                .append(",\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"original\",\"name\":\"Event ")
                .append(id).append("\"}");
        }
        TestFiles.writeCatalog(dir, catalog.append("]").toString());
    }

    @Test
    void concurrentCreatesAreNotLost() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Competitor>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String name = "Competitor " + i;
            futures.add(pool.submit(() -> {
                go.await();
                return competitorService.createCompetitor(new Competitor(name));
            }));
        }
        go.countDown();
        for (Future<Competitor> future : futures) {
            future.get();
        }
        pool.shutdown();

        List<Competitor> all = competitorService.getAllCompetitors();
        assertEquals(threads, all.size());
        Set<Integer> ids = new HashSet<>();
        all.forEach(c -> ids.add(c.getId()));
        assertEquals(threads, ids.size(), "ids must be unique");
    }

    @Test
    void staleCompetitorUpdateIsRejected() throws Exception {
        Competitor created = competitorService.createCompetitor(new Competitor("Anna"));
        assertEquals(1, created.getVersion());

        Competitor first = new Competitor("Anna B.");
        first.setVersion(1);
        Competitor updated = competitorService.updateCompetitor(created.getId(), first);
        assertEquals(2, updated.getVersion());

        Competitor stale = new Competitor("Anna C.");
        stale.setVersion(1);
        ConflictException conflict = assertThrows(ConflictException.class,
            () -> competitorService.updateCompetitor(created.getId(), stale));
        Competitor current = (Competitor) conflict.getCurrent();
        assertEquals("Anna B.", current.getName());
        assertEquals(2, current.getVersion());
        assertEquals("Anna B.", competitorService.getCompetitorById(created.getId()).getName());
    }

    @Test
    void updatingCompetitorKeepsStarts() throws Exception {
        Competitor created = competitorService.createCompetitor(new Competitor("Ben"));
        startService.createStart(created.getId(), 5);

        Competitor edit = new Competitor("Ben Renamed");
        edit.setVersion(created.getVersion());
        edit.setStarts(new java.util.HashMap<>()); // what an old client would send
        competitorService.updateCompetitor(created.getId(), edit);

        Competitor reloaded = competitorService.getCompetitorById(created.getId());
        assertEquals(1, reloaded.getStarts().get("5").size());
    }

    @Test
    void staleDeleteIsRejectedAndMissingRecordIsNotFound() throws Exception {
        Competitor created = competitorService.createCompetitor(new Competitor("Carl"));
        Competitor edit = new Competitor("Carl 2");
        edit.setVersion(1);
        competitorService.updateCompetitor(created.getId(), edit);

        assertThrows(ConflictException.class, () -> competitorService.deleteCompetitor(created.getId(), 1));
        competitorService.deleteCompetitor(created.getId(), 2);
        assertThrows(RecordNotFoundException.class, () -> competitorService.deleteCompetitor(created.getId(), null));
    }

    @Test
    void secondResultForSameStartIsAConflict() throws Exception {
        Competitor competitor = competitorService.createCompetitor(new Competitor("Dora"));
        Start start = startService.createStart(competitor.getId(), 3);

        Result first = new Result(competitor.getId(), 3, 90);
        first.setStartId(start.getGeneratedId());
        Result saved = resultService.createResult(first);
        assertEquals(1, saved.getVersion());

        Result second = new Result(competitor.getId(), 3, 80);
        second.setStartId(start.getGeneratedId());
        ConflictException conflict = assertThrows(ConflictException.class, () -> resultService.createResult(second));
        assertEquals(90.0, ((Result) conflict.getCurrent()).getValue());
    }

    @Test
    void staleResultUpdateIsRejected() throws Exception {
        Competitor competitor = competitorService.createCompetitor(new Competitor("Emil"));
        Result result = new Result(competitor.getId(), 3, 50);
        result.setStartId(startService.createStart(competitor.getId(), 3).getGeneratedId());
        Result saved = resultService.createResult(result);

        Result edit = new Result(competitor.getId(), 3, 60);
        edit.setVersion(1);
        assertEquals(2, resultService.updateResult(saved.getId(), edit).getVersion());

        Result stale = new Result(competitor.getId(), 3, 70);
        stale.setVersion(1);
        assertThrows(ConflictException.class, () -> resultService.updateResult(saved.getId(), stale));
    }

    @Test
    void startNumbersDoNotCollideAfterDelete() throws Exception {
        Competitor competitor = competitorService.createCompetitor(new Competitor("Fritz"));
        Start first = startService.createStart(competitor.getId(), 7);
        Start second = startService.createStart(competitor.getId(), 7);
        startService.deleteStart(competitor.getId(), first.getGeneratedId());

        Start third = startService.createStart(competitor.getId(), 7);
        assertNotEquals(second.getGeneratedId(), third.getGeneratedId());
        assertEquals(3, third.getStartNumber());
    }

    @Test
    void rankingChangesDoNotOverwriteEachOther() throws Exception {
        TestFiles.writeCatalog(tempDir,
            "[{\"id\":1,\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"original\",\"name\":\"A\"},"
                + "{\"id\":2,\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"original\",\"name\":\"B\"}]");
        RankingConfigService config = new RankingConfigService(dataService, null, "none");

        // Rankings added at the same time are all kept
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String name = "Ranking " + i;
            futures.add(pool.submit(() -> {
                go.await();
                return config.createRanking(java.util.Map.of("name", name,
                    "entries", List.of(java.util.Map.of("discipline_ids", List.of(1)),
                        java.util.Map.of("discipline_ids", List.of(2)))));
            }));
        }
        go.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();
        assertEquals(2 + threads, config.getConfig().getRankings().size());

        // An edit based on an outdated version is rejected
        int id = config.getConfig().getRankings().get(0).getId();
        java.util.Map<String, Object> edit = new java.util.HashMap<>(java.util.Map.of("name", "Renamed",
            "entries", List.of(java.util.Map.of("discipline_ids", List.of(1)))));
        edit.put("version", 0);
        assertEquals(1, config.updateRanking(id, edit).getVersion());
        assertThrows(ConflictException.class, () -> config.updateRanking(id, edit));
    }

    @Test
    void oldDataFileWithoutVersionsStillWorks() throws Exception {
        Files.writeString(tempDir.resolve("data.json"),
            "{\"competitors\":[{\"id\":1,\"name\":\"Old\",\"starts\":{}}],\"results\":[],"
                + "\"disciplines\":[],\"teams\":[],\"active_disciplines\":[]}");

        Competitor old = competitorService.getCompetitorById(1);
        assertEquals(0, old.getVersion());

        Competitor edit = new Competitor("Old Renamed");
        edit.setVersion(0);
        assertEquals(1, competitorService.updateCompetitor(1, edit).getVersion());
    }
}
