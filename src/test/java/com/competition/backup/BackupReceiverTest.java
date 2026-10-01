package com.competition.backup;

import com.competition.service.BackupService;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The receiver over real HTTP on a free local port. */
class BackupReceiverTest {

    private static final String TOKEN = "secret-token";
    private static final String NAME = "mlaiccmanager-backup-20261001-120000.zip";

    @TempDir
    Path tempDir;

    private Server server;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        server = BackupReceiver.create(0, tempDir.resolve("received"), TOKEN);
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort() + "/api/backups" + path;
    }

    private static byte[] zip(String entry, String content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private static byte[] validBackup() throws Exception {
        return zip("data.json", "{\"competitors\":[],\"results\":[]}");
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private HttpResponse<byte[]> put(String name, byte[] body, String token, String hash) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url("/" + name)))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (hash != null) {
            request.header("X-Content-SHA256", hash);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url(path))).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void refusesToStartWithoutAToken() {
        assertThrows(IllegalArgumentException.class,
            () -> BackupReceiver.create(0, tempDir.resolve("other"), ""));
    }

    @Test
    void everyRequestNeedsTheToken() throws Exception {
        byte[] backup = validBackup();
        assertEquals(401, put(NAME, backup, null, null).statusCode());
        assertEquals(401, put(NAME, backup, "wrong", null).statusCode());
        assertEquals(401, get("", null).statusCode());
        assertEquals(401, get("", "wrong").statusCode());
        assertEquals(401, get("/" + NAME, "wrong").statusCode());
        assertFalse(Files.exists(tempDir.resolve("received").resolve(NAME)));
    }

    @Test
    void storesListsAndDownloadsABackup() throws Exception {
        byte[] backup = validBackup();
        assertEquals(200, put(NAME, backup, TOKEN, sha256(backup)).statusCode());

        HttpResponse<byte[]> list = get("", TOKEN);
        assertEquals(200, list.statusCode());
        assertTrue(new String(list.body(), StandardCharsets.UTF_8).contains(NAME));

        HttpResponse<byte[]> download = get("/" + NAME, TOKEN);
        assertEquals(200, download.statusCode());
        assertArrayEquals(backup, download.body());
        assertTrue(BackupService.readBackup(new ByteArrayInputStream(download.body())).containsKey("data.json"),
            "what was stored restores like any backup");
        assertEquals(404, get("/mlaiccmanager-backup-20200101-000000.zip", TOKEN).statusCode());
    }

    @Test
    void storingTheSameBackupAgainIsHarmless() throws Exception {
        byte[] backup = validBackup();
        assertEquals(200, put(NAME, backup, TOKEN, null).statusCode());
        assertEquals(200, put(NAME, backup, TOKEN, null).statusCode());
        assertEquals(1, new BackupStore(tempDir.resolve("received")).list().size());
    }

    @Test
    void rejectsABackupWithAWrongChecksum() throws Exception {
        HttpResponse<byte[]> response = put(NAME, validBackup(), TOKEN, "0".repeat(64));
        assertEquals(400, response.statusCode());
        assertTrue(new String(response.body(), StandardCharsets.UTF_8).contains("checksum"));
        assertEquals(0, new BackupStore(tempDir.resolve("received")).list().size());
    }

    @Test
    void rejectsWhatIsNotABackup() throws Exception {
        assertEquals(400, put(NAME, "not a zip".getBytes(StandardCharsets.UTF_8), TOKEN, null).statusCode());
        assertEquals(400, put(NAME, zip("teams.json", "{}"), TOKEN, null).statusCode(), "data.json is missing");
        assertEquals(400, put(NAME, zip("data.json", "[1]"), TOKEN, null).statusCode(), "not a JSON object");
        assertEquals(0, new BackupStore(tempDir.resolve("received")).list().size());
    }

    @Test
    void rejectsOtherFileNames() throws Exception {
        byte[] backup = validBackup();
        assertEquals(400, put("evil.zip", backup, TOKEN, null).statusCode());
        assertEquals(400, put("mlaiccmanager-backup-20261301-120000.zip", backup, TOKEN, null).statusCode());
        assertEquals(404, get("/..%2Fsecret.zip", TOKEN).statusCode());
    }

    @Test
    void oldBackupsArePrunedAfterAStore() throws Exception {
        byte[] backup = validBackup();
        // Two backups of one day long ago: only the newest of the day is kept
        assertEquals(200, put("mlaiccmanager-backup-20200101-080000.zip", backup, TOKEN, null).statusCode());
        assertEquals(200, put("mlaiccmanager-backup-20200101-200000.zip", backup, TOKEN, null).statusCode());
        assertEquals(1, new BackupStore(tempDir.resolve("received")).list().size());
    }
}
