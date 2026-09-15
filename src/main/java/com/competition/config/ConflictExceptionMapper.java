package com.competition.config;

import com.competition.service.ConflictException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.LinkedHashMap;
import java.util.Map;

@Provider
public class ConflictExceptionMapper implements ExceptionMapper<ConflictException> {
    @Override
    public Response toResponse(ConflictException e) {
        // LinkedHashMap because "current" may be null (record was deleted)
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.put("current", e.getCurrent());
        return Response.status(Response.Status.CONFLICT)
            .type(MediaType.APPLICATION_JSON)
            .entity(body)
            .build();
    }
}
