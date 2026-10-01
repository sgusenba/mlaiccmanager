package com.competition.backup;

import com.competition.service.SettingsService;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The backup receiver: a small server, started with --backup-server, that
 * stores the backups the running app pushes to it. Every request must carry the
 * token from the "backupReceiver" section of appsettings.json in the receiver's
 * working directory, and the receiver does not start without one:
 *
 * <pre>{ "backupReceiver": { "token": "a long random string" } }</pre>
 *
 * <pre>java -jar mlaiccmanager.jar --backup-server [--port 5100] [--dir ./received-backups]</pre>
 *
 * The token is sent in clear text over plain HTTP, so use HTTPS (a reverse
 * proxy) once the receiver is reached over anything but a trusted network.
 */
public final class BackupReceiver {
    private static final Logger logger = LoggerFactory.getLogger(BackupReceiver.class);
    public static final int DEFAULT_PORT = 5100;

    private BackupReceiver() {}

    /** Builds the server, not yet started; port 0 picks a free one. */
    public static Server create(int port, Path dir, String token) throws Exception {
        Server server = new Server(port);
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(new BackupReceiverServlet(new BackupStore(dir), token)), "/api/backups/*");
        server.setHandler(context);
        return server;
    }

    /** Runs the receiver until the process ends. */
    public static void run(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        Path dir = Paths.get("received-backups");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--dir" -> dir = Paths.get(args[++i]);
                case "--backup-server" -> { }
                default -> throw new IllegalArgumentException("Unknown argument " + args[i]);
            }
        }
        Path settingsFile = Paths.get(System.getProperty("user.dir"), "appsettings.json");
        String token = new SettingsService(settingsFile).getBackupReceiverToken();
        if (token.isEmpty()) {
            throw new IllegalStateException("Set backupReceiver.token in " + settingsFile + " before starting the receiver");
        }
        Server server = create(port, dir, token);
        server.start();
        logger.info("Backup receiver started on port {}, storing backups in {}", port, dir.toAbsolutePath());
        server.join();
    }
}
