package com.competition;

import com.competition.config.CorsFilter;
import com.competition.config.JerseyConfig;
import com.competition.config.ResultsPortFilter;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.servlet.ServletContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static final int PORT = 5000;
    // Serves only the Enter Results page, e.g. for result entry stations
    private static final int RESULTS_PORT = 5001;

    public static void main(String[] args) throws Exception {
        logger.info("Starting Competition Management System...");

        Server server = new Server(PORT);
        ServerConnector resultsConnector = new ServerConnector(server);
        resultsConnector.setPort(RESULTS_PORT);
        server.addConnector(resultsConnector);

        // Create servlet context handler
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        // On the results port, allow only the Enter Results page and its API calls
        context.addFilter(new FilterHolder(new ResultsPortFilter(RESULTS_PORT)), "/*", null);
        
        // Add CORS filter
        context.addFilter(CorsFilter.class, "/*", null);

        // Register Jersey servlet
        ServletHolder jerseyServlet = new ServletHolder(new ServletContainer(new JerseyConfig()));
        jerseyServlet.setInitOrder(0);
        context.addServlet(jerseyServlet, "/api/*");

        // Serve static files. "no-cache" makes browsers revalidate on every load
        // (a cheap 304 when unchanged), so an update never runs stale JS/CSS
        // against a newer API.
        context.setResourceBase(System.getProperty("user.dir") + "/static");
        ServletHolder staticFiles = new ServletHolder(new org.eclipse.jetty.servlet.DefaultServlet());
        staticFiles.setInitParameter("cacheControl", "no-cache");
        context.addServlet(staticFiles, "/*");

        server.setHandler(context);

        try {
            server.start();
            logger.info("Server started on port {}", PORT);
            logger.info("Access the application at http://localhost:{}", PORT);
            logger.info("Enter Results only at http://localhost:{}", RESULTS_PORT);
            server.join();
        } catch (Exception e) {
            logger.error("Error starting server", e);
            System.exit(1);
        }
    }
}