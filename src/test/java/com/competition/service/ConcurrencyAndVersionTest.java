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
    void setUp() {
        dataService = new DataService(tempDir.resolve("data.json").toString(), tempDir.resolve("disciplines.json").toString());
        competitorService = new CompetitorService(dataService);
        startService = new StartService(dataService);
        disciplineService = new DisciplineService(dataService);
        resultService = new ResultService(dataService, disciplineService);
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
        result.setStartId("x");
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
    void activeDisciplineChangesDoNotOverwriteEachOther() throws Exception {
        Files.writeString(tempDir.resolve("disciplines.json"),
            "[{\"id\":1,\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"A\"},"
                + "{\"id\":2,\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"B\"},"
                + "{\"id\":3,\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"C\"},"
                + "{\"id\":4,\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"D\"}]");

        disciplineService.setActiveDisciplines(List.of(1, 2, 3), null);

        // Two users deactivate different disciplines from the same starting point
        disciplineService.deactivateDiscipline(1);
        disciplineService.deactivateDiscipline(2);
        assertEquals(List.of(3), disciplineService.getActiveDisciplines());

        // A bulk save based on an outdated list is rejected
        assertThrows(ConflictException.class,
            () -> disciplineService.setActiveDisciplines(List.of(1, 2, 3, 4), List.of(1, 2, 3)));
        disciplineService.setActiveDisciplines(List.of(3, 4), List.of(3));
        assertEquals(List.of(3, 4), disciplineService.getActiveDisciplines());
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
