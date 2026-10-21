package com.competition.portal;

import com.competition.portal.config.AdminGuardFilter;
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
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.servlet.ServletContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;

/**
 * The registration portal: clubs enter their starters for a meet, the
 * organiser downloads them as one file for the MLAICC Manager's Import
 * Registrations page. It runs on its own, behind a reverse proxy (Caddy)
 * that terminates HTTPS; the Manager itself never goes on the internet.
 */
public class PortalMain {
    private static final Logger logger = LoggerFactory.getLogger(PortalMain.class);

    public static void main(String[] args) throws Exception {
        PortalConfig config = PortalConfig.fromEnvironment();
        Server server = createServer(config);
        try {
            server.start();
            logger.info("Registration portal on http://{}:{}, data in {}", config.bind(), config.port(), config.dataDir());
            server.join();
        } catch (Exception e) {
            logger.error("Error starting the portal", e);
            System.exit(1);
        }
    }

    /** The configured server, not yet started. */
    public static Server createServer(PortalConfig config) {
        Server server = new Server();

        HttpConfiguration http = new HttpConfiguration();
        http.setSendServerVersion(false);
        http.setSendXPoweredBy(false);
        // Client address and scheme from the reverse proxy's X-Forwarded-* headers
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
        context.addFilter(new FilterHolder(new AdminGuardFilter()), "/api/*", requests);

        ServletHolder jersey = new ServletHolder(new ServletContainer(new PortalJerseyConfig(config)));
        jersey.setInitOrder(0);
        context.addServlet(jersey, "/api/*");

        // The pages; "no-cache" makes browsers revalidate, so an update never runs old JS against a newer API
        context.setResourceBase(config.staticDir().toString());
        ServletHolder staticFiles = new ServletHolder(new DefaultServlet());
        staticFiles.setInitParameter("cacheControl", "no-cache");
        staticFiles.setInitParameter("dirAllowed", "false");
        context.addServlet(staticFiles, "/*");

        server.setHandler(context);
        return server;
    }
}
