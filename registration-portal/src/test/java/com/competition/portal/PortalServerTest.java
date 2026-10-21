package com.competition.portal;

import com.competition.portal.service.Fixtures;
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
import java.time.Clock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** The assembled server on a free port: pages, API, filters, log-in and error answers. */
class PortalServerTest {
    private static final String ORIGIN = "http://localhost";
    private static final String PASSWORD = "a long enough password";

    @TempDir
    Path dir;

    private Server server;
    private PortalServices services;
    private String base;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        Path staticDir = Files.createDirectories(dir.resolve("static"));
        Files.writeString(staticDir.resolve("index.html"), "<!DOCTYPE html><title>Portal</title>");
        services = new PortalServices(new PortalConfig("127.0.0.1", 0, dir.resolve("data"), staticDir, ORIGIN), Clock.systemUTC());
        services.organiserService.create("stefan", PASSWORD);
        server = PortalMain.createServer(services);
        server.start();
        base = "http://127.0.0.1:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    @AfterEach
    void stop() throws Exception {
        server.stop();
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A write as the portal's pages send it: JSON, with the portal's origin. */
    private HttpResponse<String> send(String method, String path, String json, String cookie, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
            .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        boolean ownHeaders = headers.length > 0;
        if (!ownHeaders) {
            b.header("Content-Type", "application/json").header("Origin", ORIGIN);
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** "mlaicportal=<token>" from a log-in answer's Set-Cookie. */
    private static String cookie(HttpResponse<String> response) {
        String header = response.headers().firstValue("Set-Cookie").orElseThrow();
        Matcher m = Pattern.compile("^(mlaicportal=[^;]+);").matcher(header);
        assertTrue(m.find(), header);
        assertTrue(header.contains("HttpOnly") && header.contains("SameSite=Strict"), header);
        return m.group(1);
    }

    private String organiserCookie() throws Exception {
        HttpResponse<String> login = send("POST", "/api/auth/organiser",
            "{\"username\":\"stefan\",\"password\":\"" + PASSWORD + "\"}", null);
        assertEquals(204, login.statusCode(), login.body());
        return cookie(login);
    }

    @Test
    void healthIsOkAndUncached() throws Exception {
        HttpResponse<String> response = get("/api/health", null);
        assertEquals(200, response.statusCode());
        assertEquals("{\"ok\":true}", response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null));
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null));
        assertTrue(response.headers().firstValue("Server").isEmpty(), "no server version");
    }

    @Test
    void healthFailsWhenTheDataCannotBeRead() throws Exception {
        Files.writeString(dir.resolve("data/portal.json"), "{broken");
        HttpResponse<String> response = get("/api/health", null);
        assertEquals(503, response.statusCode());
        assertEquals("{\"ok\":false}", response.body());
    }

    @Test
    void servesThePages() throws Exception {
        HttpResponse<String> response = get("/", null);
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

    @Test
    void theOrganisersApiNeedsAnOrganiserLogIn() throws Exception {
        assertEquals(401, get("/api/admin/meets", null).statusCode());
        assertEquals(401, get("/api/admin/meets", "mlaicportal=made-up").statusCode());

        HttpResponse<String> wrong = send("POST", "/api/auth/organiser", "{\"username\":\"stefan\",\"password\":\"nope nope nope\"}", null);
        assertEquals(401, wrong.statusCode());
        assertTrue(wrong.headers().firstValue("Set-Cookie").isEmpty());

        String cookie = organiserCookie();
        assertEquals(200, get("/api/admin/meets", cookie).statusCode());
        HttpResponse<String> session = get("/api/session", cookie);
        assertTrue(session.body().contains("\"role\":\"organiser\"") && session.body().contains("\"username\":\"stefan\""), session.body());

        assertEquals(204, send("POST", "/api/auth/logout", "{}", cookie).statusCode());
        assertEquals(401, get("/api/admin/meets", cookie).statusCode(), "logged out");
        assertEquals(401, get("/api/session", cookie).statusCode());
    }

    @Test
    void writesMustBeJsonFromThePortalsOwnPages() throws Exception {
        String login = "{\"username\":\"stefan\",\"password\":\"" + PASSWORD + "\"}";
        assertEquals(403, send("POST", "/api/auth/organiser", login, null,
            "Content-Type", "application/json", "Origin", "https://evil.example").statusCode());
        assertEquals(403, send("POST", "/api/auth/organiser", login, null,
            "Content-Type", "application/json").statusCode(), "no origin at all");
        assertEquals(415, send("POST", "/api/auth/organiser", "username=stefan", null,
            "Content-Type", "application/x-www-form-urlencoded", "Origin", ORIGIN).statusCode(), "a plain form");
        assertEquals(204, send("POST", "/api/auth/organiser", login, null,
            "Content-Type", "application/json", "Referer", ORIGIN + "/login/").statusCode(), "Referer when Origin is missing");
    }

    @Test
    void organiserCreatesAMeetWithAClubAndTheClubLogsIn() throws Exception {
        String cookie = organiserCookie();
        HttpResponse<String> created = send("POST", "/api/admin/meets",
            "{\"key\":\"ooe-lm\",\"name\":\"OÖ VL LM\",\"deadline\":\"2027-04-30T23:59\"}", cookie);
        assertEquals(200, created.statusCode(), created.body());

        String body = "{\"rankings\":" + Fixtures.ooeRankings() + ",\"catalog\":" + Fixtures.mlaicCatalog() + "}";
        HttpResponse<String> uploaded = send("PUT", "/api/admin/meets/1/snapshot", body, cookie);
        assertEquals(200, uploaded.statusCode(), uploaded.body());
        assertTrue(uploaded.body().contains("\"ranking_id\":84"));

        HttpResponse<String> club = send("POST", "/api/admin/meets/1/clubs", "{\"name\":\"SV Bad Zell\",\"default_country\":\"Austria\"}", cookie);
        assertEquals(200, club.statusCode(), club.body());
        Matcher code = Pattern.compile("\"code\":\"([0-9A-Z-]{19})\"").matcher(club.body());
        assertTrue(code.find(), club.body());
        assertTrue(club.body().contains("\"invite_url\":\"http://localhost/login/#c=" + code.group(1) + "\""));
        assertFalse(club.body().contains("code_hash"));

        HttpResponse<String> notOpen = send("POST", "/api/auth/club", "{\"code\":\"" + code.group(1) + "\"}", null);
        assertEquals(403, notOpen.statusCode(), "the meet is still a draft");

        HttpResponse<String> opened = send("PUT", "/api/admin/meets/1",
            "{\"key\":\"ooe-lm\",\"name\":\"OÖ VL LM\",\"deadline\":\"2027-04-30T23:59\",\"status\":\"open\",\"version\":2}", cookie);
        assertEquals(200, opened.statusCode(), opened.body());

        HttpResponse<String> clubLogin = send("POST", "/api/auth/club", "{\"code\":\"" + code.group(1) + "\"}", null);
        assertEquals(204, clubLogin.statusCode(), clubLogin.body());
        String clubCookie = cookie(clubLogin);
        HttpResponse<String> session = get("/api/session", clubCookie);
        assertTrue(session.body().contains("\"role\":\"club\"") && session.body().contains("\"name\":\"SV Bad Zell\""), session.body());
        assertEquals(401, get("/api/admin/meets", clubCookie).statusCode(), "a club is not an organiser");

        // A new code logs the club out
        assertEquals(200, send("POST", "/api/admin/meets/1/clubs/1/code", "{}", cookie).statusCode());
        assertEquals(401, get("/api/session", clubCookie).statusCode());
    }

    @Test
    void tooManyWrongClubCodesAreRefusedForAWhile() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertEquals(401, send("POST", "/api/auth/club", "{\"code\":\"0000-0000-0000-000" + i + "\"}", null).statusCode());
        }
        HttpResponse<String> blocked = send("POST", "/api/auth/club", "{\"code\":\"0000-0000-0000-0000\"}", null);
        assertEquals(429, blocked.statusCode());
        assertTrue(blocked.headers().firstValue("Retry-After").isPresent());
    }

    @Test
    void refusesBodiesOverOneMegabyte() throws Exception {
        byte[] big = new byte[(int) com.competition.portal.config.RequestSizeFilter.MAX_BYTES + 1];
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/club"))
            .header("Content-Type", "application/json").header("Origin", ORIGIN)
            .POST(HttpRequest.BodyPublishers.ofByteArray(big)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(413, response.statusCode());
        assertTrue(response.body().startsWith("{\"error\":"));
    }
}
