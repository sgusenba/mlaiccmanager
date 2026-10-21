package com.competition.portal.config;

import com.competition.portal.i18n.Messages;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.InetAddress;

/**
 * Until the organiser log-in exists (phase 3), the organiser's API
 * (/api/admin/...) answers only requests from this machine itself. A request
 * through the reverse proxy carries the client's address in X-Forwarded-For,
 * which Jetty takes as the remote address, so it is refused with 403.
 * Phase 3 replaces this with the log-in check.
 */
public class AdminGuardFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        if (http.getRequestURI().startsWith("/api/admin/") || http.getRequestURI().equals("/api/admin")) {
            if (!isLoopback(http.getRemoteAddr())) {
                HttpServletResponse out = (HttpServletResponse) response;
                out.setStatus(HttpServletResponse.SC_FORBIDDEN);
                out.setContentType("application/json");
                out.setCharacterEncoding("UTF-8");
                out.getWriter().write("{\"error\":\"" + Messages.get("admin.localOnly").replace("\"", "\\\"") + "\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean isLoopback(String address) {
        try {
            return address != null && InetAddress.getByName(address).isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }
}
