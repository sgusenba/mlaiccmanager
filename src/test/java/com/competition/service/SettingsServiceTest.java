package com.competition.service;

import com.competition.model.AppSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SettingsServiceTest {

    @TempDir
    Path tempDir;

    private AppSettings.ExternalBackup settings(boolean enabled, String url, int debounce, int maxDelay) {
        AppSettings.ExternalBackup s = new AppSettings.ExternalBackup();
        s.setEnabled(enabled);
        s.setUrl(url);
        s.setToken("tok");
        s.setDebounceSeconds(debounce);
        s.setMaxDelaySeconds(maxDelay);
        return s;
    }

    @Test
    void missingFileMeansBackupIsOff() {
        AppSettings.ExternalBackup s = new SettingsService(tempDir.resolve("appsettings.json")).getExternalBackup();
        assertFalse(s.isEnabled());
        assertEquals("", s.getUrl());
        assertEquals("", s.getToken());
        assertEquals("", new SettingsService(tempDir.resolve("appsettings.json")).getBackupReceiverToken());
        assertEquals(60, s.getDebounceSeconds());
        assertEquals(300, s.getMaxDelaySeconds());
    }

    @Test
    void savedSettingsSurviveARestart() throws Exception {
        Path file = tempDir.resolve("appsettings.json");
        new SettingsService(file).updateExternalBackup(settings(true, "http://backup.local:5100/", 30, 120));

        AppSettings.ExternalBackup s = new SettingsService(file).getExternalBackup();
        assertTrue(s.isEnabled());
        assertEquals("http://backup.local:5100", s.getUrl());
        assertEquals("tok", s.getToken());
        assertEquals(30, s.getDebounceSeconds());
        assertEquals(120, s.getMaxDelaySeconds());
        assertFalse(Files.exists(tempDir.resolve("appsettings.json.tmp")));
    }

    @Test
    void corruptFileFallsBackToDefaults() throws Exception {
        Path file = tempDir.resolve("appsettings.json");
        Files.writeString(file, "{ not json");
        assertFalse(new SettingsService(file).getExternalBackup().isEnabled());
    }

    @Test
    void unknownKeysInTheFileAreIgnored() throws Exception {
        Path file = tempDir.resolve("appsettings.json");
        Files.writeString(file, "{\"other\":1,\"externalBackup\":{\"enabled\":true,\"url\":\"http://x\",\"extra\":2}}");
        AppSettings.ExternalBackup s = new SettingsService(file).getExternalBackup();
        assertTrue(s.isEnabled());
        assertEquals("http://x", s.getUrl());
    }

    @Test
    void rejectsBadValuesAndKeepsWhatWasStored() throws Exception {
        SettingsService service = new SettingsService(tempDir.resolve("appsettings.json"));
        service.updateExternalBackup(settings(true, "http://ok", 60, 300));

        assertThrows(IllegalArgumentException.class,
            () -> service.updateExternalBackup(settings(true, "", 60, 300)));
        assertThrows(IllegalArgumentException.class,
            () -> service.updateExternalBackup(settings(true, "ftp://host", 60, 300)));
        assertThrows(IllegalArgumentException.class,
            () -> service.updateExternalBackup(settings(true, "not a url", 60, 300)));
        assertThrows(IllegalArgumentException.class,
            () -> service.updateExternalBackup(settings(true, "http://ok", 5, 300)));
        assertThrows(IllegalArgumentException.class,
            () -> service.updateExternalBackup(settings(true, "http://ok", 120, 60)));
        assertThrows(IllegalArgumentException.class,
            () -> service.updateExternalBackup(settings(true, "http://ok", 60, 7200)));

        assertEquals("http://ok", service.getExternalBackup().getUrl());
    }

    @Test
    void disabledSettingsMayLeaveTheUrlEmpty() throws Exception {
        SettingsService service = new SettingsService(tempDir.resolve("appsettings.json"));
        service.updateExternalBackup(settings(false, "", 60, 300));
        assertFalse(service.getExternalBackup().isEnabled());
    }

    @Test
    void getReturnsACopy() throws Exception {
        SettingsService service = new SettingsService(tempDir.resolve("appsettings.json"));
        service.getExternalBackup().setEnabled(true);
        assertFalse(service.getExternalBackup().isEnabled());
    }

    @Test
    void anEmptyTokenKeepsTheStoredOneButEnablingNeedsOne() throws Exception {
        SettingsService service = new SettingsService(tempDir.resolve("appsettings.json"));
        AppSettings.ExternalBackup noToken = settings(true, "http://ok", 60, 300);
        noToken.setToken("");
        assertThrows(IllegalArgumentException.class, () -> service.updateExternalBackup(noToken));

        service.updateExternalBackup(settings(true, "http://ok", 60, 300));
        assertEquals("tok", service.updateExternalBackup(noToken).getToken());
        assertEquals("tok", new SettingsService(tempDir.resolve("appsettings.json")).getExternalBackup().getToken());
    }

    @Test
    void readsTheReceiversToken() throws Exception {
        Files.writeString(tempDir.resolve("appsettings.json"), "{\"backupReceiver\":{\"token\":\" abc \"}}");
        assertEquals("abc", new SettingsService(tempDir.resolve("appsettings.json")).getBackupReceiverToken());
    }
}
