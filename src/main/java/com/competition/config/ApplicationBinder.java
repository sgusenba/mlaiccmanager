package com.competition.config;

import com.competition.model.Discipline;
import com.competition.service.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ApplicationBinder extends AbstractBinder {
    private static final Logger logger = LoggerFactory.getLogger(ApplicationBinder.class);

    @Override
    protected void configure() {
        String basePath = System.getProperty("user.dir");
        DataService dataService = new DataService(basePath + "/data.json", basePath + "/disciplines.json",
                                                  basePath + "/competition.json");

        migrateCompetitionSettings(basePath, dataService);
        migrateDisciplineRanges(basePath, dataService);

        CompetitorService competitorService = new CompetitorService(dataService);
        DisciplineService disciplineService = new DisciplineService(dataService);
        StartService startService = new StartService(dataService);
        ResultService resultService = new ResultService(dataService, disciplineService);
        TeamService teamService = new TeamService(basePath + "/teams.json", dataService);
        RankingService rankingService = new RankingService(dataService, disciplineService, teamService);
        RelayService relayService = new RelayService(basePath + "/relays.json", dataService);
        MeetService meetService = new MeetService(basePath + "/meet.json");
        BackupService backupService = new BackupService(Paths.get(basePath), dataService, teamService, relayService,
                                                        meetService);

        bind(dataService).to(DataService.class);
        bind(competitorService).to(CompetitorService.class);
        bind(disciplineService).to(DisciplineService.class);
        bind(startService).to(StartService.class);
        bind(resultService).to(ResultService.class);
        bind(rankingService).to(RankingService.class);
        bind(relayService).to(RelayService.class);
        bind(teamService).to(TeamService.class);
        bind(meetService).to(MeetService.class);
        bind(backupService).to(BackupService.class);
    }

    /**
     * Creates competition.json on first start, keeping what this installation
     * had changed before the catalog became read-only:
     * <ul>
     *   <li>disciplines.previous.json (the runtime-edited catalog, saved aside by
     *       the deploy script before it installs the new one) supplies edited
     *       fields, added and removed disciplines and, if it has them, the active flags;</li>
     *   <li>otherwise the active list comes from the old data.json "active_disciplines" key;</li>
     *   <li>otherwise the catalog is taken as shipped.</li>
     * </ul>
     */
    public static void migrateCompetitionSettings(String basePath, DataService dataService) {
        if (dataService.hasCompetitionSettings()) return;
        ObjectMapper mapper = new ObjectMapper();
        File previousFile = new File(basePath + "/disciplines.previous.json");
        try {
            List<Discipline> disciplines = dataService.loadDisciplines();
            boolean activeKnown = false;
            if (previousFile.exists()) {
                JsonNode previous = mapper.readTree(previousFile);
                if (previous.isObject() && previous.has("disciplines")) {
                    previous = previous.get("disciplines");
                }
                disciplines = mapper.convertValue(previous, new TypeReference<List<Discipline>>() {});
                for (JsonNode entry : previous) {
                    activeKnown |= entry.has("active");
                }
            }

            File dataFile = new File(basePath + "/data.json");
            if (!activeKnown && dataFile.exists()) {
                Object raw = mapper.readValue(dataFile, new TypeReference<LinkedHashMap<String, Object>>() {})
                    .get("active_disciplines");
                if (raw instanceof List<?> ids) {
                    Set<Integer> active = new HashSet<>();
                    for (Object id : ids) {
                        if (id instanceof Number n) active.add(n.intValue());
                    }
                    for (Discipline d : disciplines) {
                        d.setActive(active.contains(d.getId()));
                    }
                }
            }

            dataService.saveDisciplines(disciplines);
            if (previousFile.exists()) {
                Files.move(previousFile.toPath(), Paths.get(basePath + "/disciplines.previous.json.migrated"),
                           StandardCopyOption.REPLACE_EXISTING);
            }
            logger.info("Created competition.json with {} active disciplines",
                        disciplines.stream().filter(Discipline::isActive).count());
        } catch (IOException e) {
            logger.warn("Could not create competition.json: {}", e.getMessage());
        }
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