package com.competition.config;

import com.competition.service.*;
import org.glassfish.hk2.utilities.binding.AbstractBinder;

import java.nio.file.Paths;

public class ApplicationBinder extends AbstractBinder {
    @Override
    protected void configure() {
        String basePath = System.getProperty("user.dir");
        DataService dataService = new DataService(basePath + "/data.json", basePath + "/disciplines.json",
                                                  basePath + "/competition.json");

        CompetitorService competitorService = new CompetitorService(dataService);
        DisciplineService disciplineService = new DisciplineService(dataService);
        StartService startService = new StartService(dataService, disciplineService);
        ResultService resultService = new ResultService(dataService, disciplineService);
        TeamService teamService = new TeamService(basePath + "/teams.json", dataService);
        RankingService rankingService = new RankingService(dataService, disciplineService, teamService);
        RelayService relayService = new RelayService(basePath + "/relays.json", dataService);
        CascadingDeletes cascadingDeletes = new CascadingDeletes(startService, competitorService, teamService,
                                                                 relayService);
        MeetService meetService = new MeetService(basePath + "/meet.json");
        ProgramService programService = new ProgramService(dataService, disciplineService, teamService);
        BackupService backupService = new BackupService(Paths.get(basePath), dataService, teamService, relayService,
                                                        meetService);
        SettingsService settingsService = new SettingsService(Paths.get(basePath, "appsettings.json"));
        ExternalBackupService externalBackupService = new ExternalBackupService(backupService, settingsService,
                                                                                Paths.get(basePath));
        externalBackupService.start();
        DangerZoneService dangerZoneService = new DangerZoneService(dataService, teamService, relayService,
                                                                    meetService, backupService);

        bind(dataService).to(DataService.class);
        bind(competitorService).to(CompetitorService.class);
        bind(disciplineService).to(DisciplineService.class);
        bind(startService).to(StartService.class);
        bind(resultService).to(ResultService.class);
        bind(rankingService).to(RankingService.class);
        bind(relayService).to(RelayService.class);
        bind(cascadingDeletes).to(CascadingDeletes.class);
        bind(teamService).to(TeamService.class);
        bind(meetService).to(MeetService.class);
        bind(programService).to(ProgramService.class);
        bind(backupService).to(BackupService.class);
        bind(dangerZoneService).to(DangerZoneService.class);
        bind(settingsService).to(SettingsService.class);
        bind(externalBackupService).to(ExternalBackupService.class);
    }
}
