package com.competition.backup;

import com.competition.service.BackupService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PUT /api/backups/{name}.zip stores a backup, GET /api/backups lists them and
 * GET /api/backups/{name}.zip downloads one, all with the receiver's token as a
 * bearer token. A PUT is checked like a restore
 * (a zip with a valid data.json) and against the X-Content-SHA256 header if sent.
 */
class BackupReceiverServlet extends HttpServlet {
    private static final Logger logger = LoggerFactory.getLogger(BackupReceiverServlet.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** The same cap as for one file inside a restored zip; the zip itself may not be larger either. */
    static final long MAX_BYTES = 50L * 1024 * 1024;
    static final String HASH_HEADER = "X-Content-SHA256";

    private final BackupStore store;
    private final byte[] token;

    BackupReceiverServlet(BackupStore store, String token) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("The receiver needs a token");
        }
        this.store = store;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    /** Every request must carry the token as a bearer token, whatever the method. */
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        byte[] given = header != null && header.startsWith("Bearer ")
            ? header.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (!MessageDigest.isEqual(given, token)) {
            response.setHeader("WWW-Authenticate", "Bearer");
            error(response, HttpServletResponse.SC_UNAUTHORIZED, "Missing or wrong token");
            return;
        }
        super.service(request, response);
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String name = nameOf(request);
        if (name == null || !BackupStore.isValidName(name)) {
            error(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid backup name");
            return;
        }
        if (request.getContentLengthLong() > MAX_BYTES) {
            error(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "The backup is too large");
            return;
        }
        byte[] body = readLimited(request.getInputStream());
        if (body == null) {
            error(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "The backup is too large");
            return;
        }
        String expected = request.getHeader(HASH_HEADER);
        if (expected != null && !expected.equalsIgnoreCase(sha256(body))) {
            error(response, HttpServletResponse.SC_BAD_REQUEST, "The checksum does not match the content");
            return;
        }
        try {
            BackupService.readBackup(new ByteArrayInputStream(body));
        } catch (IllegalArgumentException e) {
            error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            return;
        }
        try {
            store.store(name, body);
            store.prune(LocalDateTime.now());
        } catch (IOException e) {
            logger.error("Could not store backup {}", name, e);
            error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Could not store the backup");
            return;
        }
        logger.info("Stored backup {} ({} bytes)", name, body.length);
        json(response, HttpServletResponse.SC_OK, Map.of("name", name, "size", body.length));
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String name = nameOf(request);
        if (name == null) {
            List<Map<String, Object>> list = store.list().stream().map(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", e.name());
                m.put("size", e.size());
                m.put("time", e.time().toString());
                return m;
            }).toList();
            json(response, HttpServletResponse.SC_OK, list);
            return;
        }
        Optional<byte[]> content = store.read(name);
        if (content.isEmpty()) {
            error(response, HttpServletResponse.SC_NOT_FOUND, "No such backup");
            return;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + name + "\"");
        response.setContentLength(content.get().length);
        response.getOutputStream().write(content.get());
    }

    /** The file name in the path, or null for the collection itself. */
    private static String nameOf(HttpServletRequest request) {
        String path = request.getPathInfo();
        if (path == null || path.equals("/")) {
            return null;
        }
        return path.substring(1);
    }

    /** The whole body, or null if it is larger than MAX_BYTES. */
    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        for (int n; (n = in.read(buffer)) > 0; ) {
            total += n;
            if (total > MAX_BYTES) {
                return null;
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void error(HttpServletResponse response, int status, String message) throws IOException {
        json(response, status, Map.of("error", message));
    }

    private static void json(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
