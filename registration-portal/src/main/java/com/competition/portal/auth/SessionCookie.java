package com.competition.portal.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The session cookie. Over HTTPS (the public address) it is
 * "__Host-mlaicportal" with Secure, HttpOnly, SameSite=Strict, Path=/ and no
 * Domain, so it is sent only to this host, only over HTTPS, never to scripts
 * and never with requests from other sites. Locally over plain HTTP the
 * browser would drop a Secure cookie, so it is "mlaicportal" without Secure.
 */
public final class SessionCookie {
    private SessionCookie() {
    }

    public static String name(boolean secure) {
        return secure ? "__Host-mlaicportal" : "mlaicportal";
    }

    /** The Set-Cookie header value that stores the token. */
    public static String set(String token, boolean secure) {
        return name(secure) + "=" + token + "; Path=/; HttpOnly; SameSite=Strict" + (secure ? "; Secure" : "");
    }

    /** The Set-Cookie header value that removes the cookie. */
    public static String clear(boolean secure) {
        return name(secure) + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict" + (secure ? "; Secure" : "");
    }

    /** The token from the request's cookie, or null. */
    public static String token(HttpServletRequest request, boolean secure) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String name = name(secure);
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
