package com.competition.resource;

import com.competition.i18n.Messages;

import java.util.Map;

/** The JSON body of an error response, {"error": message}, serialized by Jackson so quotes in the message stay valid JSON. */
public final class ErrorBody {
    private ErrorBody() {
    }

    public static Map<String, String> of(String message) {
        return Map.of("error", message != null ? message : Messages.get("error.unknown"));
    }
}
