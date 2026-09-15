package com.competition.config;

import com.competition.service.RecordNotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

@Provider
public class RecordNotFoundExceptionMapper implements ExceptionMapper<RecordNotFoundException> {
    @Override
    public Response toResponse(RecordNotFoundException e) {
        return Response.status(Response.Status.NOT_FOUND)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", e.getMessage()))
            .build();
    }
}
