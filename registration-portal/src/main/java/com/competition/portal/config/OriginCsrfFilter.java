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
import java.net.URI;
import java.util.Set;

/**
 * Protection against cross-site requests (CSRF), on top of the SameSite=Strict
 * cookie: every API request that changes something (any method but GET, HEAD,
 * OPTIONS) must come from the portal's own pages, i.e. carry an Origin (or
 * else Referer) of the portal's public address, and must be JSON, which a
 * plain HTML form on another site cannot send. GET requests never change
 * anything.
 */
public class OriginCsrfFilter implements Filter {
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    private final String origin;

    public OriginCsrfFilter(String baseUrl) {
        this.origin = originOf(baseUrl);
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        if (!SAFE.contains(request.getMethod())) {
            String contentType = request.getContentType();
            if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
                JsonErrors.send(response, 415, Messages.get("auth.jsonOnly"));
                return;
            }
            if (!fromOwnPages(request)) {
                JsonErrors.send(response, 403, Messages.get("auth.foreignOrigin"));
                return;
            }
        }
        chain.doFilter(req, res);
    }

    private boolean fromOwnPages(HttpServletRequest request) {
        String requestOrigin = request.getHeader("Origin");
        if (requestOrigin != null) {
            return origin.equals(requestOrigin);
        }
        String referer = request.getHeader("Referer");
        return referer != null && origin.equals(originOf(referer));
    }

    /** scheme://host[:port] of a URL, lower case, without a default port; "" if it is not a URL. */
    static String originOf(String url) {
        try {
            URI uri = URI.create(url.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return "";
            }
            String scheme = uri.getScheme().toLowerCase();
            int port = uri.getPort();
            boolean defaultPort = port == -1 || (scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80);
            return scheme + "://" + uri.getHost().toLowerCase() + (defaultPort ? "" : ":" + port);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
