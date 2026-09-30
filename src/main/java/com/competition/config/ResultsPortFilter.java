package com.competition.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Restricts requests on the results port to the Enter Results page and the
 * API calls it makes, so a result entry station can be given that port
 * without reaching the rest of the app. Requests on any other port pass.
 */
public class ResultsPortFilter implements Filter {
    public static final String PAGE = "/results/";

    private static final Pattern STATIC = Pattern.compile("/results/.*|/js/.*|/img/.*|/style\\.css");
    private static final Pattern READABLE_API = Pattern.compile("/api/(competitors|available-disciplines|active-disciplines|results)");
    private static final Pattern RESULT_BY_ID = Pattern.compile("/api/results/\\d+");

    private final int resultsPort;

    public ResultsPortFilter(int resultsPort) {
        this.resultsPort = resultsPort;
    }

    /** Whether the results port serves this request; path is the decoded path below the context. */
    static boolean isAllowed(String method, String path) {
        switch (method) {
            case "GET":
            case "HEAD":
                return STATIC.matcher(path).matches() || READABLE_API.matcher(path).matches();
            case "POST":
                return path.equals("/api/results");
            case "DELETE":
                return RESULT_BY_ID.matcher(path).matches();
            default:
                return false;
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        if (httpRequest.getLocalPort() != resultsPort) {
            chain.doFilter(request, response);
            return;
        }

        String path = httpRequest.getServletPath() + (httpRequest.getPathInfo() != null ? httpRequest.getPathInfo() : "");
        if (path.isEmpty() || path.equals("/") || path.equals("/results")) {
            httpResponse.sendRedirect(PAGE);
        } else if (isAllowed(httpRequest.getMethod(), path)) {
            chain.doFilter(request, response);
        } else {
            httpResponse.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }
}
