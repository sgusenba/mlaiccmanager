package com.competition.portal.config;

import com.competition.portal.service.JsonFile;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

/** Error answers written by the servlet filters, in the same {"error": ...} form as the API's. */
final class JsonErrors {
    private JsonErrors() {
    }

    static void send(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(JsonFile.MAPPER.writer().without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
            .writeValueAsString(Map.of("error", message)));
    }
}
