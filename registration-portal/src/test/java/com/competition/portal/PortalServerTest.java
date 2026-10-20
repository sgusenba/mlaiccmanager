package com.competition.portal;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The assembled server on a free port: static pages, the API, filters and error answers. */
class PortalServerTest {
    @TempDir
    Path dir;

    private Server server;
    private String base;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        Path staticDir = Files.createDirectories(dir.resolve("static"));
        Files.writeString(staticDir.resolve("index.html"), "<!DOCTYPE html><title>Portal</title>");
        server = PortalMain.createServer(new PortalConfig("127.0.0.1", 0, dir.resolve("data"), staticDir, "http://localhost"));
        server.start();
        base = "http://127.0.0.1:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    @AfterEach
    void stop() throws Exception {
        server.stop();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthIsOkAndUncached() throws Exception {
        HttpResponse<String> response = get("/api/health");
        assertEquals(200, response.statusCode());
        assertEquals("{\"ok\":true}", response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null));
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null));
        assertTrue(response.headers().firstValue("Server").isEmpty(), "no server version");
    }

    @Test
    void healthFailsWhenTheDataCannotBeRead() throws Exception {
        Files.createDirectories(dir.resolve("data"));
        Files.writeString(dir.resolve("data/portal.json"), "{broken");
        HttpResponse<String> response = get("/api/health");
        assertEquals(503, response.statusCode());
        assertEquals("{\"ok\":false}", response.body());
    }

    @Test
    void servesThePages() throws Exception {
        HttpResponse<String> response = get("/");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("<title>Portal</title>"));
        assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElse(null));
    }

    @Test
    void unknownApiPathsAnswerWithAJsonError() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/nothing"))
            .header("Accept-Language", "de-AT").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
        assertEquals("{\"error\":\"Unter dieser Adresse gibt es nichts.\"}", response.body());
    }

    private HttpResponse<String> send(String method, String path, String json, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json")
            .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theOrganisersApiAnswersOnlyThisMachineUntilTheLogInExists() throws Exception {
        assertEquals(200, get("/api/admin/meets").statusCode());
        // Through the reverse proxy the client's address arrives in X-Forwarded-For
        HttpResponse<String> proxied = send("GET", "/api/admin/meets", null, "X-Forwarded-For", "203.0.113.5");
        assertEquals(403, proxied.statusCode());
        assertTrue(proxied.body().startsWith("{\"error\":"));
    }

    @Test
    void createsAMeetAndUploadsItsRankings() throws Exception {
        HttpResponse<String> created = send("POST", "/api/admin/meets",
            "{\"key\":\"ooe-lm\",\"name\":\"OÖ VL LM\",\"deadline\":\"2027-04-30T23:59\"}");
        assertEquals(200, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"time_zone\":\"Europe/Vienna\""));

        String body = "{\"rankings\":" + com.competition.portal.service.Fixtures.ooeRankings()
            + ",\"catalog\":" + com.competition.portal.service.Fixtures.mlaicCatalog() + "}";
        HttpResponse<String> uploaded = send("PUT", "/api/admin/meets/1/snapshot", body);
        assertEquals(200, uploaded.statusCode(), uploaded.body());
        assertTrue(uploaded.body().contains("\"ranking_id\":84"));
        assertTrue(uploaded.body().contains("\"label\":\"REMO\""));
        assertFalse(uploaded.body().contains("code_hash"));

        HttpResponse<String> wrong = send("PUT", "/api/admin/meets/1/snapshot", "{\"rankings\":{},\"catalog\":{}}");
        assertEquals(400, wrong.statusCode());
        assertEquals(404, get("/api/admin/meets/9").statusCode());
    }

    @Test
    void refusesBodiesOverOneMegabyte() throws Exception {
        byte[] big = new byte[(int) com.competition.portal.config.RequestSizeFilter.MAX_BYTES + 1];
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/health"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(big)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(413, response.statusCode());
        assertTrue(response.body().startsWith("{\"error\":"));
    }
}
