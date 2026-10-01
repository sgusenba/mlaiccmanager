package com.competition.service;

import com.competition.model.AppSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reads and writes appsettings.json. A missing or unreadable file means the
 * defaults, i.e. every optional feature off. The settings are kept in memory
 * after the first read; this process is the file's only writer.
 */
public class SettingsService {
    private static final Logger logger = LoggerFactory.getLogger(SettingsService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path file;
    private final ReentrantLock lock = new ReentrantLock();
    private AppSettings settings;

    public SettingsService(Path file) {
        this.file = file;
    }

    /** A copy of the current external backup settings. */
    public AppSettings.ExternalBackup getExternalBackup() {
        lock.lock();
        try {
            return load().getExternalBackup().copy();
        } finally {
            lock.unlock();
        }
    }

    /** The token the backup receiver requires; empty if none is set. */
    public String getBackupReceiverToken() {
        lock.lock();
        try {
            return load().getBackupReceiver().getToken();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Validates and saves the external backup settings, returning what was stored (the URL is
     * normalized). An empty token in the update keeps the stored one, so a form that never
     * shows the token can save the other fields.
     */
    public AppSettings.ExternalBackup updateExternalBackup(AppSettings.ExternalBackup update) throws IOException {
        AppSettings.ExternalBackup copy = update.copy();
        copy.validate();
        lock.lock();
        try {
            AppSettings next = load();
            if (copy.getToken().isEmpty()) {
                copy.setToken(next.getExternalBackup().getToken());
            }
            if (copy.isEnabled() && copy.getToken().isEmpty()) {
                throw new IllegalArgumentException("Enter the receiver's token");
            }
            next.setExternalBackup(copy);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            objectMapper.writeValue(temp.toFile(), next);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            settings = next;
            return copy.copy();
        } finally {
            lock.unlock();
        }
    }

    private AppSettings load() {
        if (settings == null) {
            settings = new AppSettings();
            if (Files.exists(file)) {
                try {
                    settings = objectMapper.readValue(file.toFile(), AppSettings.class);
                } catch (IOException e) {
                    logger.warn("Could not read {}, using the defaults: {}", file.getFileName(), e.getMessage());
                    settings = new AppSettings();
                }
            }
        }
        return settings;
    }
}
