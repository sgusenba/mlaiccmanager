package com.competition.portal;

import com.competition.portal.config.AuthFilter;
import com.competition.portal.config.OriginCsrfFilter;
import com.competition.portal.config.PortalJerseyConfig;
import com.competition.portal.config.RequestSizeFilter;
import com.competition.portal.config.SecurityHeadersFilter;
import com.competition.portal.i18n.LocaleFilter;
import jakarta.servlet.DispatcherType;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.ee10.servlet.DefaultServlet;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.glassfish.jersey.servlet.ServletContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Properties;

/**
 * The registration portal: clubs enter their starters for a meet, the
 * organiser downloads them as one file for the MLAICC Manager's Import
 * Registrations page. It runs on its own, behind a reverse proxy (Caddy)
 * that terminates HTTPS; the Manager itself never goes on the internet.
 *
 * <p>With arguments it runs a server command instead (see {@link PortalCli}),
 * e.g. --add-organiser NAME to create the first organiser account, or prints
 * its version (--version), which the update script uses to check that a
 * downloaded jar runs.
 */
public class PortalMain {
    private static final Logger logger = LoggerFactory.getLogger(PortalMain.class);

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--version")) {
            System.out.println(version());
            return;
        }
        PortalConfig config = PortalConfig.fromEnvironment();
        if (args.length > 0) {
            System.exit(PortalCli.run(args, new PortalServices(config, Clock.systemUTC())));
        }
        PortalServices services = new PortalServices(config, Clock.systemUTC());
        services.startBackgroundJobs();
        Server server = createServer(services);
        try {
            server.start();
            logger.info("{} on http://{}:{}, data in {}, public address {}",
                version(), config.bind(), config.port(), config.dataDir(), config.baseUrl());
            server.join();
        } catch (Exception e) {
            logger.error("Error starting the portal", e);
            System.exit(1);
        }
    }

    /** "mlaic-portal 1.0.0 (commit …, built …)", from build-info.properties. */
    static String version() {
        Properties info = new Properties();
        try (InputStream in = PortalMain.class.getClassLoader().getResourceAsStream("build-info.properties")) {
            if (in != null) {
                info.load(in);
            }
        } catch (IOException e) {
            // no build information: shown as unknown
        }
        return "mlaic-portal " + info.getProperty("version", "?") + " (commit " + info.getProperty("commit", "?")
            + ", built " + info.getProperty("built", "?") + ")";
    }

    /** The configured server, not yet started. */
    public static Server createServer(PortalServices services) {
        PortalConfig config = services.config;
        Server server = new Server();

        HttpConfiguration http = new HttpConfiguration();
        http.setSendServerVersion(false);
        http.setSendXPoweredBy(false);
        // Client address and scheme from the reverse proxy's X-Forwarded-* headers. Trusted because
        // the portal listens on 127.0.0.1 only, where nothing but the proxy can reach it
        http.addCustomizer(new ForwardedRequestCustomizer());
        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(http));
        connector.setHost(config.bind());
        connector.setPort(config.port());
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        EnumSet<DispatcherType> requests = EnumSet.of(DispatcherType.REQUEST);
        context.addFilter(new FilterHolder(new RequestSizeFilter()), "/*", requests);
        context.addFilter(new FilterHolder(new SecurityHeadersFilter()), "/*", requests);
        context.addFilter(new FilterHolder(new LocaleFilter()), "/*", requests);
        context.addFilter(new FilterHolder(new OriginCsrfFilter(config.baseUrl())), "/api/*", requests);
        context.addFilter(new FilterHolder(new AuthFilter(services)), "/api/*", requests);

        ServletHolder jersey = new ServletHolder(new ServletContainer(new PortalJerseyConfig(services)));
        jersey.setInitOrder(0);
        context.addServlet(jersey, "/api/*");

        // The pages, from the jar (or a folder while working on them); "no-cache" makes browsers
        // revalidate, so an update never runs old JS against a newer API
        if (config.staticDir() != null) {
            context.setBaseResourceAsString(config.staticDir().toString());
        } else {
            Resource pages = ResourceFactory.of(context).newClassLoaderResource("static");
            if (pages == null) {
                throw new IllegalStateException("The jar has no pages (static/)");
            }
            context.setBaseResource(pages);
        }
        ServletHolder staticFiles = new ServletHolder(new DefaultServlet());
        staticFiles.setInitParameter("cacheControl", "no-cache");
        staticFiles.setInitParameter("dirAllowed", "false");
        context.addServlet(staticFiles, "/*");

        server.setHandler(context);
        return server;
    }
}
