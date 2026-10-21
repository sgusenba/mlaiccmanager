package com.competition.portal.service;

import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The countries a starter can be registered for, by their English name, which
 * is how the MLAICC Manager stores a competitor's country. countries-en.json
 * holds the Manager's list of ISO codes (static/js/countries.js) with the
 * English names a browser gives them; the portal's pages get the same list
 * from GET /api/countries, so page and server always agree.
 */
public final class Countries {
    private static final List<Map<String, String>> LIST = load();
    private static final Map<String, String> BY_LOWER_NAME = new LinkedHashMap<>();

    static {
        LIST.forEach(c -> BY_LOWER_NAME.put(c.get("name").toLowerCase(Locale.ROOT), c.get("name")));
    }

    private Countries() {
    }

    /** [{code, name}] in the Manager's order. */
    public static List<Map<String, String>> list() {
        return LIST;
    }

    /** The country's name as stored (case does not matter in the input), or null if it is not in the list. */
    public static String canonical(String name) {
        return name == null ? null : BY_LOWER_NAME.get(name.trim().toLowerCase(Locale.ROOT));
    }

    private static List<Map<String, String>> load() {
        try (InputStream in = Countries.class.getClassLoader().getResourceAsStream("countries-en.json")) {
            return List.copyOf(JsonFile.MAPPER.readValue(in, new TypeReference<List<Map<String, String>>>() {}));
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("countries-en.json missing or broken", e);
        }
    }
}
