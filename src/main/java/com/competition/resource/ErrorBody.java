package com.competition.resource;

import java.util.Map;
import java.util.Objects;

/** The JSON body of an error response, {"error": message}, serialized by Jackson so quotes in the message stay valid JSON. */
public final class ErrorBody {
    private ErrorBody() {
    }

    public static Map<String, String> of(String message) {
        return Map.of("error", Objects.toString(message, "Unknown error"));
    }
}
