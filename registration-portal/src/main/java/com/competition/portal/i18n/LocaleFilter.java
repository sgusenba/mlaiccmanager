package com.competition.portal.i18n;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.Locale;

/**
 * Sets the language of {@link Messages} for a request: the one picked on the
 * page (the "lang" cookie), else the browser's preferred language if there
 * are messages for it, else English.
 */
public class LocaleFilter implements Filter {
    public static final String COOKIE = "lang";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        Messages.setCurrent(localeOf((HttpServletRequest) request));
        try {
            chain.doFilter(request, response);
        } finally {
            Messages.setCurrent(null);
        }
    }

    static Locale localeOf(HttpServletRequest request) {
        String chosen = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE.equals(cookie.getName())) {
                    chosen = cookie.getValue();
                }
            }
        }
        return localeFor(chosen, request.getHeader("Accept-Language"));
    }

    /** The language for the "lang" cookie's value and the Accept-Language header, either may be null. */
    static Locale localeFor(String cookie, String acceptLanguage) {
        Locale chosen = Messages.supported(cookie);
        if (chosen != null) {
            return chosen;
        }
        if (acceptLanguage != null) {
            try {
                for (Locale.LanguageRange range : Locale.LanguageRange.parse(acceptLanguage)) {
                    Locale supported = Messages.supported(range.getRange());
                    if (supported != null) {
                        return supported;
                    }
                }
            } catch (IllegalArgumentException e) {
                // a malformed header: English
            }
        }
        return Messages.DEFAULT;
    }
}
