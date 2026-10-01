package com.competition.service;

import com.competition.model.AppSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Pushes the backup zip to a backup receiver over HTTP after every change: once
 * the data files have been quiet for the configured time, or at the latest the
 * maximum delay after the first change nobody has backed up yet. Nothing is
 * sent while nothing changed.
 *
 * <p>The data files' size and modification time are polled every
 * {@value #POLL_SECONDS} seconds, so the data services need no hook. The
 * settings are read on every poll, so changes apply at once. A failed push is
 * retried after {@value #RETRY_SECONDS} seconds with the newest data.
 */
public class ExternalBackupService {
    private static final Logger logger = LoggerFactory.getLogger(ExternalBackupService.class);

    static final int POLL_SECONDS = 10;
    static final int RETRY_SECONDS = 60;

    private final BackupService backupService;
    private final SettingsService settingsService;
    private final Path baseDir;
    private final LongSupplier clock;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private ScheduledExecutorService scheduler;

    // All state below is guarded by this
    private String lastSeenSignature;
    private String lastPushedSignature;
    private String lastPushedUrl;
    private long firstUnbackedChange = -1;
    private long lastChange;
    private long retryAfter;
    private Instant lastSuccess;
    private String lastBackupName;
    private long lastBackupSize;
    private String lastError;
    private Instant lastErrorAt;

    public ExternalBackupService(BackupService backupService, SettingsService settingsService, Path baseDir) {
        this(backupService, settingsService, baseDir, System::currentTimeMillis);
    }

    ExternalBackupService(BackupService backupService, SettingsService settingsService, Path baseDir,
                          LongSupplier clock) {
        this.backupService = backupService;
        this.settingsService = settingsService;
        this.baseDir = baseDir;
        this.clock = clock;
    }

    /** Starts polling on a daemon thread, so it never keeps the server from stopping. */
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "external-backup");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::tickSafely, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
    }

    private void tickSafely() {
        try {
            tick();
        } catch (RuntimeException e) {
            logger.error("External backup poll failed", e);
        }
    }

    /** One poll: notices changes and pushes a backup when one is due. */
    synchronized void tick() {
        AppSettings.ExternalBackup settings = settingsService.getExternalBackup();
        if (!settings.isEnabled() || settings.getUrl().isEmpty()) {
            return;
        }
        long now = clock.getAsLong();
        String signature = signature();
        if (signature == null) {
            return; // no data.json yet, nothing to back up
        }

        // A new receiver gets a full backup even if the data did not change
        if (!settings.getUrl().equals(lastPushedUrl)) {
            lastPushedSignature = null;
        }
        if (!signature.equals(lastSeenSignature)) {
            lastSeenSignature = signature;
            lastChange = now;
        }
        if (signature.equals(lastPushedSignature)) {
            firstUnbackedChange = -1;
            return;
        }
        if (firstUnbackedChange < 0) {
            firstUnbackedChange = now;
            if (lastPushedSignature == null) {
                lastChange = now - settings.getDebounceSeconds() * 1000L; // the first push does not wait
            }
        }
        boolean quiet = now - lastChange >= settings.getDebounceSeconds() * 1000L;
        boolean overdue = now - firstUnbackedChange >= settings.getMaxDelaySeconds() * 1000L;
        if ((quiet || overdue) && now >= retryAfter) {
            push(settings, signature, now);
        }
    }

    /** Pushes a backup now, whatever the timing; throws IllegalStateException if it fails or the backup is off. */
    public synchronized Map<String, Object> runNow() {
        AppSettings.ExternalBackup settings = settingsService.getExternalBackup();
        if (!settings.isEnabled() || settings.getUrl().isEmpty()) {
            throw new IllegalStateException("The external backup is not enabled");
        }
        String signature = signature();
        if (signature == null) {
            throw new IllegalStateException("There is no data to back up yet");
        }
        if (!push(settings, signature, clock.getAsLong())) {
            throw new IllegalStateException(lastError);
        }
        return status();
    }

    /** Pushes the backup; true on success. Caller holds the lock. */
    private boolean push(AppSettings.ExternalBackup settings, String signature, long now) {
        try {
            byte[] zip = backupService.createBackup();
            String name = BackupService.backupFileName();
            HttpRequest request = HttpRequest.newBuilder(URI.create(settings.getUrl() + "/api/backups/" + name))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/zip")
                .header("Authorization", "Bearer " + settings.getToken())
                .header("X-Content-SHA256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(zip)))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(zip))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("The receiver answered " + response.statusCode() + ": " + response.body());
            }
            lastPushedSignature = signature;
            lastPushedUrl = settings.getUrl();
            firstUnbackedChange = -1;
            lastSuccess = Instant.ofEpochMilli(now);
            lastBackupName = name;
            lastBackupSize = zip.length;
            lastError = null;
            logger.info("Pushed backup {} ({} bytes) to {}", name, zip.length, settings.getUrl());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail(e, now);
        } catch (Exception e) {
            return fail(e, now);
        }
    }

    private boolean fail(Exception e, long now) {
        lastError = e.getMessage() == null ? e.toString() : e.getMessage();
        lastErrorAt = Instant.ofEpochMilli(now);
        retryAfter = now + RETRY_SECONDS * 1000L;
        logger.warn("External backup failed: {}", lastError);
        return false;
    }

    /** Checks that a receiver accepts token at url; throws IllegalStateException with the reason if it does not. */
    public void testConnection(String url, String token) {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create(AppSettings.ExternalBackup.validateUrl(url) + "/api/backups"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token).GET().build();
            int status = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 401) {
                throw new IllegalStateException("The receiver rejected the token");
            }
            if (status / 100 != 2) {
                throw new IllegalStateException("The receiver answered " + status);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted");
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the receiver: " + e.getMessage());
        }
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("last_success", lastSuccess == null ? null : lastSuccess.toString());
        status.put("last_backup_name", lastBackupName);
        status.put("last_backup_size", lastBackupSize);
        status.put("last_error", lastError);
        status.put("last_error_at", lastErrorAt == null ? null : lastErrorAt.toString());
        status.put("pending", firstUnbackedChange >= 0);
        return status;
    }

    /** Size and modification time of every data file, or null if there is no data.json yet. */
    private String signature() {
        StringBuilder signature = new StringBuilder();
        for (String name : BackupService.FILES) {
            Path file = baseDir.resolve(name);
            try {
                if (Files.exists(file)) {
                    signature.append(name).append(':').append(Files.size(file)).append(':')
                        .append(Files.getLastModifiedTime(file).toMillis()).append(';');
                } else if (name.equals(BackupService.REQUIRED_FILE)) {
                    return null;
                }
            } catch (IOException e) {
                return null;
            }
        }
        return signature.toString();
    }
}
