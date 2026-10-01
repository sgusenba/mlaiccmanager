package com.competition.service;

import com.competition.model.AppSettings;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** The sender, against a stub receiver on a loopback port; time is driven by hand through tick(). */
class ExternalBackupServiceTest {

    private record Put(String method, String path, String hash, String auth, byte[] body) {}

    @TempDir
    Path tempDir;

    private HttpServer receiver;
    private final List<Put> puts = new CopyOnWriteArrayList<>();
    private final AtomicInteger answer = new AtomicInteger(200);
    private final AtomicLong now = new AtomicLong(1_000_000);
    private int fileVersion = 0;

    private SettingsService settingsService;
    private ExternalBackupService service;

    @BeforeEach
    void setUp() throws Exception {
        receiver = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        receiver.createContext("/", exchange -> {
            puts.add(new Put(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("X-Content-SHA256"),
                exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestBody().readAllBytes()));
            byte[] reply = "{\"error\":\"stub\"}".getBytes();
            exchange.sendResponseHeaders(answer.get(), reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        receiver.start();

        Files.writeString(tempDir.resolve("disciplines.json"), "[{\"id\":1,\"level\":\"individual\"}]");
        writeData();
        DataService dataService = new DataService(tempDir.resolve("data.json").toString(),
            tempDir.resolve("disciplines.json").toString(), tempDir.resolve("competition.json").toString());
        TeamService teamService = new TeamService(tempDir.resolve("teams.json").toString(), dataService);
        RelayService relayService = new RelayService(tempDir.resolve("relays.json").toString(), dataService);
        MeetService meetService = new MeetService(tempDir.resolve("meet.json").toString());
        BackupService backupService = new BackupService(tempDir, dataService, teamService, relayService, meetService);

        settingsService = new SettingsService(tempDir.resolve("appsettings.json"));
        configure(true, url(), 60, 300);
        service = new ExternalBackupService(backupService, settingsService, tempDir, now::get);
    }

    @AfterEach
    void tearDown() {
        receiver.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + receiver.getAddress().getPort();
    }

    private void configure(boolean enabled, String url, int debounce, int maxDelay) throws Exception {
        AppSettings.ExternalBackup s = new AppSettings.ExternalBackup();
        s.setEnabled(enabled);
        s.setUrl(url);
        s.setToken("secret-token");
        s.setDebounceSeconds(debounce);
        s.setMaxDelaySeconds(maxDelay);
        settingsService.updateExternalBackup(s);
    }

    /** Changes data.json; the version makes the size and time differ, so the change is always noticed. */
    private void writeData() throws Exception {
        fileVersion++;
        Path file = tempDir.resolve("data.json");
        Files.writeString(file, "{\"competitors\":[],\"results\":[],\"v\":" + fileVersion + "}");
        Files.setLastModifiedTime(file, FileTime.fromMillis(2_000_000_000L + fileVersion * 1000L));
    }

    private void advance(int seconds) {
        now.addAndGet(seconds * 1000L);
        service.tick();
    }

    @Test
    void doesNothingWhileDisabled() throws Exception {
        configure(false, "", 60, 300);
        advance(10);
        advance(600);
        assertTrue(puts.isEmpty());
    }

    @Test
    void doesNothingWithoutData() throws Exception {
        Files.delete(tempDir.resolve("data.json"));
        advance(10);
        advance(600);
        assertTrue(puts.isEmpty());
    }

    @Test
    void firstPollPushesAFullBackupAndThenNothingUntilTheDataChanges() throws Exception {
        advance(10);
        assertEquals(1, puts.size());
        Put put = puts.get(0);
        assertEquals("PUT", put.method());
        assertEquals("Bearer secret-token", put.auth());
        assertTrue(put.path().matches("/api/backups/mlaiccmanager-backup-\\d{8}-\\d{6}\\.zip"), put.path());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(put.body())), put.hash());
        assertTrue(BackupService.readBackup(new ByteArrayInputStream(put.body())).containsKey("data.json"));

        advance(600);
        advance(600);
        assertEquals(1, puts.size(), "unchanged data is not pushed again");
        assertNotNull(service.status().get("last_success"));
        assertEquals(false, service.status().get("pending"));
    }

    @Test
    void aChangeIsPushedOnceTheDataHasBeenQuietForTheDebounceTime() throws Exception {
        advance(10);
        puts.clear();

        writeData();
        advance(10);
        advance(40);
        assertTrue(puts.isEmpty(), "still within the quiet time");
        assertEquals(true, service.status().get("pending"));

        advance(20);
        assertEquals(1, puts.size());
        assertEquals(false, service.status().get("pending"));
    }

    @Test
    void everyChangeRestartsTheQuietTime() throws Exception {
        advance(10);
        puts.clear();

        for (int i = 0; i < 4; i++) {
            writeData();
            advance(40);
        }
        assertTrue(puts.isEmpty(), "constant editing keeps postponing");
    }

    @Test
    void continuousChangesAreStillPushedAtTheMaximumDelay() throws Exception {
        advance(10);
        puts.clear();

        int elapsed = 0;
        while (elapsed < 300) {
            writeData();
            advance(10);
            elapsed += 10;
        }
        assertTrue(puts.isEmpty());
        writeData();
        advance(10);
        assertEquals(1, puts.size(), "300 seconds after the first unbacked change");
    }

    @Test
    void aFailedPushIsRetriedWithTheNewestData() throws Exception {
        answer.set(503);
        advance(10);
        assertEquals(1, puts.size());
        assertNotNull(service.status().get("last_error"));
        assertNull(service.status().get("last_success"));
        assertEquals(true, service.status().get("pending"));

        advance(30);
        assertEquals(1, puts.size(), "waits before retrying");

        answer.set(200);
        advance(40);
        assertEquals(2, puts.size());
        assertNull(service.status().get("last_error"));
        assertNotNull(service.status().get("last_success"));
        assertEquals(false, service.status().get("pending"));
    }

    @Test
    void aRejectedBackupIsAnError() throws Exception {
        answer.set(400);
        advance(10);
        assertTrue(String.valueOf(service.status().get("last_error")).contains("400"));
    }

    @Test
    void anUnreachableReceiverIsAnErrorNotACrash() throws Exception {
        receiver.stop(0);
        advance(10);
        assertNotNull(service.status().get("last_error"));
        assertEquals(true, service.status().get("pending"));
    }

    @Test
    void aNewReceiverGetsAFullBackupEvenIfTheDataDidNotChange() throws Exception {
        advance(10);
        puts.clear();
        configure(true, url() + "/", 60, 300);
        advance(10);
        assertEquals(0, puts.size(), "only the trailing slash differs");

        HttpServer other = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        List<String> otherPaths = new CopyOnWriteArrayList<>();
        other.createContext("/", exchange -> {
            otherPaths.add(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        other.start();
        try {
            configure(true, "http://127.0.0.1:" + other.getAddress().getPort(), 60, 300);
            advance(10);
            assertEquals(1, otherPaths.size());
        } finally {
            other.stop(0);
        }
    }

    @Test
    void backUpNowIgnoresTheQuietTime() throws Exception {
        advance(10);
        puts.clear();
        writeData();

        Map<String, Object> status = service.runNow();
        assertEquals(1, puts.size());
        assertNotNull(status.get("last_success"));
    }

    @Test
    void backUpNowReportsAFailure() throws Exception {
        answer.set(500);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.runNow());
        assertTrue(e.getMessage().contains("500"));

        configure(false, "", 60, 300);
        assertThrows(IllegalStateException.class, () -> service.runNow());
    }

    @Test
    void testConnectionChecksTheListEndpoint() {
        service.testConnection(url(), "secret-token");
        assertEquals("GET", puts.get(0).method());
        assertEquals("/api/backups", puts.get(0).path());
        assertEquals("Bearer secret-token", puts.get(0).auth());

        answer.set(401);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.testConnection(url(), "x"));
        assertTrue(e.getMessage().contains("token"));
        answer.set(404);
        assertThrows(IllegalStateException.class, () -> service.testConnection(url(), "x"));
        assertThrows(IllegalArgumentException.class, () -> service.testConnection("nonsense", "x"));
        receiver.stop(0);
        assertThrows(IllegalStateException.class, () -> service.testConnection(url(), "x"));
    }
}
