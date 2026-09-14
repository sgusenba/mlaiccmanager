package com.competition;

import com.competition.config.CorsFilter;
import com.competition.config.JerseyConfig;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.servlet.ServletContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static final int PORT = 5000;

    public static void main(String[] args) throws Exception {
        logger.info("Starting Competition Management System...");

        Server server = new Server(PORT);

        // Create servlet context handler
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        
        // Add CORS filter
        context.addFilter(CorsFilter.class, "/*", null);

        // Register Jersey servlet
        ServletHolder jerseyServlet = new ServletHolder(new ServletContainer(new JerseyConfig()));
        jerseyServlet.setInitOrder(0);
        context.addServlet(jerseyServlet, "/api/*");

        // Serve static files
        context.setResourceBase(System.getProperty("user.dir") + "/static");
        context.addServlet(new ServletHolder(new org.eclipse.jetty.servlet.DefaultServlet()), "/*");

        server.setHandler(context);

        try {
            server.start();
            logger.info("Server started on port {}", PORT);
            logger.info("Access the application at http://localhost:{}", PORT);
            server.join();
        } catch (Exception e) {
            logger.error("Error starting server", e);
            System.exit(1);
        }
    }
}