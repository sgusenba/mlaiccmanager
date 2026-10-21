package com.competition.portal.service;

import com.competition.portal.model.Club;
import com.competition.portal.model.Meet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Deletes registrations that are no longer needed: once a meet's delete_after
 * day has passed (in the meet's time zone), its starters, the clubs' contact
 * persons and its audit log are removed. The meet, its clubs and the uploaded
 * rankings stay. Runs at start and then regularly (PortalServices).
 */
public class RetentionJob {
    private static final Logger logger = LoggerFactory.getLogger(RetentionJob.class);

    private final MeetStore meets;
    private final AuditLog audit;
    private final Clock clock;

    public RetentionJob(MeetStore meets, AuditLog audit, Clock clock) {
        this.meets = meets;
        this.audit = audit;
        this.clock = clock;
    }

    /** Runs once; returns the number of meets whose registrations were deleted. */
    public int run() throws Exception {
        int purged = 0;
        for (Meet listed : meets.list()) {
            if (listed.deleteAfter == null || !due(listed)) {
                continue;
            }
            boolean hasData = !listed.starters.isEmpty() || Files.exists(meets.auditFile(listed.id))
                || listed.clubs.stream().anyMatch(RetentionJob::hasContact);
            if (!hasData) {
                continue;
            }
            int count = meets.update(listed.id, meet -> {
                int n = meet.starters.size();
                StarterService.clearPersonalData(meet);
                return n;
            }, n -> {
                audit.delete(listed.id);
                return null;
            });
            logger.info("Retention: deleted the registrations of meet {} ({} starters), delete_after {}",
                listed.id, count, listed.deleteAfter);
            purged++;
        }
        return purged;
    }

    private boolean due(Meet meet) {
        ZoneId zone = ZoneId.of(meet.timeZone != null ? meet.timeZone : MeetService.DEFAULT_TIME_ZONE);
        return LocalDate.now(clock.withZone(zone)).isAfter(LocalDate.parse(meet.deleteAfter));
    }

    private static boolean hasContact(Club club) {
        return club.contact != null && (club.contact.name != null || club.contact.email != null);
    }
}
