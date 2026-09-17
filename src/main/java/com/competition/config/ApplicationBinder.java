package com.competition.config;

import com.competition.service.*;
import org.glassfish.hk2.utilities.binding.AbstractBinder;

public class ApplicationBinder extends AbstractBinder {
    @Override
    protected void configure() {
        // Create data service instance with absolute paths
        String basePath = System.getProperty("user.dir");
        DataService dataService = new DataService(basePath + "/data.json", basePath + "/disciplines.json");
        
        // Create service instances
        CompetitorService competitorService = new CompetitorService(dataService);
        DisciplineService disciplineService = new DisciplineService(dataService);
        StartService startService = new StartService(dataService);
        ResultService resultService = new ResultService(dataService, disciplineService);
        RankingService rankingService = new RankingService(dataService, disciplineService);
        // Relay management is kept in its own file, separate from data.json
        RelayService relayService = new RelayService(basePath + "/relays.json", dataService);

        // Bind services as singletons
        bind(dataService).to(DataService.class);
        bind(competitorService).to(CompetitorService.class);
        bind(disciplineService).to(DisciplineService.class);
        bind(startService).to(StartService.class);
        bind(resultService).to(ResultService.class);
        bind(rankingService).to(RankingService.class);
        bind(relayService).to(RelayService.class);
    }
}