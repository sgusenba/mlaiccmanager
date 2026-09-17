package com.competition.config;

import com.competition.model.Discipline;
import com.competition.service.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ApplicationBinder extends AbstractBinder {
    private static final Logger logger = LoggerFactory.getLogger(ApplicationBinder.class);

    @Override
    protected void configure() {
        String basePath = System.getProperty("user.dir");
        DataService dataService = new DataService(basePath + "/data.json", basePath + "/disciplines.json");

        migrateDisciplineRanges(basePath, dataService);

        CompetitorService competitorService = new CompetitorService(dataService);
        DisciplineService disciplineService = new DisciplineService(dataService);
        StartService startService = new StartService(dataService);
        ResultService resultService = new ResultService(dataService, disciplineService);
        RankingService rankingService = new RankingService(dataService, disciplineService);
        RelayService relayService = new RelayService(basePath + "/relays.json", dataService);

        bind(dataService).to(DataService.class);
        bind(competitorService).to(CompetitorService.class);
        bind(disciplineService).to(DisciplineService.class);
        bind(startService).to(StartService.class);
        bind(resultService).to(ResultService.class);
        bind(rankingService).to(RankingService.class);
        bind(relayService).to(RelayService.class);
    }

    @SuppressWarnings("unchecked")
    private static void migrateDisciplineRanges(String basePath, DataService dataService) {
        try {
            File relaysFile = new File(basePath + "/relays.json");
            if (!relaysFile.exists()) return;

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> relays = mapper.readValue(relaysFile, new TypeReference<LinkedHashMap<String, Object>>() {});
            Object raw = relays.get("discipline_ranges");
            if (!(raw instanceof Map)) return;
            Map<String, Object> ranges = (Map<String, Object>) raw;
            if (ranges.isEmpty()) return;

            List<Discipline> disciplines = dataService.loadDisciplines();
            boolean alreadyMigrated = disciplines.stream().anyMatch(d -> d.getShootingDistance() != null);
            if (alreadyMigrated) return;

            for (Discipline d : disciplines) {
                Object rangeId = ranges.get(String.valueOf(d.getId()));
                if (rangeId instanceof String s && !s.isBlank()) {
                    d.setShootingDistance(s);
                }
            }
            dataService.saveDisciplines(disciplines);

            relays.put("discipline_ranges", new LinkedHashMap<>());
            File tempFile = new File(basePath + "/relays.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tempFile, relays);
            Files.move(tempFile.toPath(), Paths.get(basePath + "/relays.json"),
                       StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            logger.info("Migrated {} discipline ranges from relays.json to disciplines.json", ranges.size());
        } catch (IOException e) {
            logger.warn("Could not migrate discipline ranges: {}", e.getMessage());
        }
    }
}