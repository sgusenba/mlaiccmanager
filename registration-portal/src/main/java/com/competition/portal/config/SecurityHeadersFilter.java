package com.competition.portal.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Security headers on every response. The reverse proxy sets HSTS and the
 * Content-Security-Policy; these are the ones the portal sets itself, so
 * they also hold when it is reached without the proxy (e.g. locally).
 */
public class SecurityHeadersFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletResponse http = (HttpServletResponse) response;
        http.setHeader("X-Content-Type-Options", "nosniff");
        http.setHeader("X-Frame-Options", "DENY");
        http.setHeader("Referrer-Policy", "no-referrer");
        // API answers hold personal data: never keep them in a browser or proxy cache
        if (((HttpServletRequest) request).getRequestURI().startsWith("/api/")) {
            http.setHeader("Cache-Control", "no-store");
        }
        chain.doFilter(request, response);
    }
}
