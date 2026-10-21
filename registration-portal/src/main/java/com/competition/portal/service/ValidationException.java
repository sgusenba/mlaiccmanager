package com.competition.portal.service;

import java.util.List;
import java.util.Map;

/**
 * A sheet that cannot be saved: rows with errors. Nothing is saved. Mapped to
 * 400 with {"error", "rows": [{"index", "id", "errors": [...]}]}, so the page
 * can mark each row.
 */
public class ValidationException extends RuntimeException {
    private final transient List<Map<String, Object>> rows;

    public ValidationException(String message, List<Map<String, Object>> rows) {
        super(message);
        this.rows = rows;
    }

    public List<Map<String, Object>> getRows() {
        return rows;
    }
}
