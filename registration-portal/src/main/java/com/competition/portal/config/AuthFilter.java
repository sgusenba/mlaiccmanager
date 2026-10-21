package com.competition.portal.config;

import com.competition.portal.PortalServices;
import com.competition.portal.auth.Session;
import com.competition.portal.auth.SessionCookie;
import com.competition.portal.i18n.Messages;
import com.competition.portal.model.Meet;
import com.competition.portal.service.RecordNotFoundException;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * The one place that decides who may call which part of the API:
 * <ul>
 * <li>/api/health, /api/auth/... and /api/session: everyone;</li>
 * <li>/api/admin/...: logged-in organisers;</li>
 * <li>/api/club/...: logged-in clubs, while their code is not revoked and
 *     their meet still exists. Which meet and club comes from the session,
 *     never from the address.</li>
 * </ul>
 * The session of the request's cookie is put into the request attribute
 * {@link Session#ATTRIBUTE}.
 */
public class AuthFilter implements Filter {
    private final PortalServices services;

    public AuthFilter(PortalServices services) {
        this.services = services;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String token = SessionCookie.token(request, services.secure());
        Session session = services.sessions.find(token);
        String path = request.getRequestURI();

        if (path.startsWith("/api/admin/") || path.equals("/api/admin")) {
            if (session == null || !services.organiserService.exists(session)) {
                JsonErrors.send(response, 401, Messages.get("auth.organiserOnly"));
                return;
            }
        } else if (path.startsWith("/api/club/") || path.equals("/api/club")) {
            if (session == null || !session.isClub() || !clubStillValid(session)) {
                if (session != null && session.isClub()) {
                    services.sessions.end(token);
                }
                JsonErrors.send(response, 401, Messages.get("auth.clubOnly"));
                return;
            }
        }
        if (session != null) {
            request.setAttribute(Session.ATTRIBUTE, session);
        }
        chain.doFilter(req, res);
    }

    private boolean clubStillValid(Session session) {
        try {
            Meet meet = services.meetStore.get(session.meetId);
            return meet.clubs.stream().anyMatch(c -> c.id == session.clubId && !c.revoked);
        } catch (RecordNotFoundException e) {
            return false;
        }
    }
}
