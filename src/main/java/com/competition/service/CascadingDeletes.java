package com.competition.service;

import java.util.Set;

/**
 * Deletes a start or a competitor together with what names their starts: the
 * lanes (relays.json) and team memberships (teams.json). Otherwise a lane or
 * team would keep pointing at a start that is gone.
 *
 * <p>Holds the teams.json and relays.json locks around the data.json change,
 * in the order BackupService gives (teams, relays, data.json), so no lane or
 * team can pick up the start while it is being deleted.
 */
public class CascadingDeletes {
    private final StartService startService;
    private final CompetitorService competitorService;
    private final TeamService teamService;
    private final RelayService relayService;

    public CascadingDeletes(StartService startService, CompetitorService competitorService,
                            TeamService teamService, RelayService relayService) {
        this.startService = startService;
        this.competitorService = competitorService;
        this.teamService = teamService;
        this.relayService = relayService;
    }

    /** Deletes the start with its result, lanes and team memberships. */
    public void deleteStart(int competitorId, String startId) throws Exception {
        locked(() -> {
            startService.deleteStart(competitorId, startId);
            return Set.of(startId);
        });
    }

    /** Deletes the competitor with their starts, results, lanes and team memberships. */
    public void deleteCompetitor(int id, Integer expectedVersion) throws Exception {
        locked(() -> competitorService.deleteCompetitor(id, expectedVersion));
    }

    @FunctionalInterface
    private interface Delete {
        Set<String> run() throws Exception;
    }

    private void locked(Delete delete) throws Exception {
        teamService.exclusive(() -> relayService.exclusive(() -> {
            Set<String> startIds = delete.run();
            // Locked days included: a lane must not keep a start that no longer exists
            relayService.clearAssignments(startIds::contains);
            teamService.removeMembers(startIds::contains);
            return null;
        }));
    }
}
