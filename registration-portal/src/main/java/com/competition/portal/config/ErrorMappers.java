package com.competition.portal.config;

import com.competition.portal.i18n.Messages;
import com.competition.portal.service.ConflictException;
import com.competition.portal.service.RecordNotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every error answers with a JSON body {"error": message}, like the Manager's
 * API, so the pages can show it inline. Unexpected errors are logged and
 * answered with a generic message that reveals nothing about the server.
 */
public final class ErrorMappers {
    private static final Logger logger = LoggerFactory.getLogger(ErrorMappers.class);

    private ErrorMappers() {
    }

    static Response error(Response.Status status, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", message != null ? message : Messages.get("error.unknown"))).build();
    }

    /** 409 with the record as stored now, so the page can reload it. */
    @Provider
    public static class Conflict implements ExceptionMapper<ConflictException> {
        @Override
        public Response toResponse(ConflictException e) {
            // LinkedHashMap because "current" may be null (record was deleted)
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", e.getMessage());
            body.put("current", e.getCurrent());
            return Response.status(Response.Status.CONFLICT).type(MediaType.APPLICATION_JSON).entity(body).build();
        }
    }

    @Provider
    public static class NotFound implements ExceptionMapper<RecordNotFoundException> {
        @Override
        public Response toResponse(RecordNotFoundException e) {
            return error(Response.Status.NOT_FOUND, e.getMessage());
        }
    }

    /** Invalid input; the message says what to fix. */
    @Provider
    public static class BadRequest implements ExceptionMapper<IllegalArgumentException> {
        @Override
        public Response toResponse(IllegalArgumentException e) {
            return error(Response.Status.BAD_REQUEST, e.getMessage());
        }
    }

    /** 401, 403 or 429 from the log-in; 429 says when to try again, a missing authenticator code says so. */
    @Provider
    public static class Auth implements ExceptionMapper<com.competition.portal.auth.AuthException> {
        @Override
        public Response toResponse(com.competition.portal.auth.AuthException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", e.getMessage());
            if (e.isTotpRequired()) {
                body.put("totp_required", true);
            }
            Response.ResponseBuilder response = Response.status(e.getStatus()).type(MediaType.APPLICATION_JSON).entity(body);
            if (e.getRetryAfter() != null) {
                response.header("Retry-After", Math.max(1, e.getRetryAfter().getSeconds()));
            }
            return response.build();
        }
    }

    @Provider
    public static class Unexpected implements ExceptionMapper<Exception> {
        @Override
        public Response toResponse(Exception e) {
            // Jersey's own answers (unknown path 404, wrong method 405, unreadable JSON 400) stay as they are
            if (e instanceof WebApplicationException web) {
                Response original = web.getResponse();
                Response.Status status = Response.Status.fromStatusCode(original.getStatus());
                return error(status != null ? status : Response.Status.BAD_REQUEST,
                    status == Response.Status.NOT_FOUND ? Messages.get("error.noSuchPath") : Messages.get("error.request"));
            }
            logger.error("Unexpected error", e);
            return error(Response.Status.INTERNAL_SERVER_ERROR, Messages.get("error.internal"));
        }
    }
}
