package com.competition.portal.config;

import com.competition.portal.service.JsonFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.Provider;

/** JSON in snake_case for the API, as the data files are written, but compact. */
@Provider
public class ObjectMapperResolver implements ContextResolver<ObjectMapper> {
    private static final ObjectMapper API = JsonFile.MAPPER.copy().disable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public ObjectMapper getContext(Class<?> type) {
        return API;
    }
}
