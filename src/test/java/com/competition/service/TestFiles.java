package com.competition.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Test fixtures: a discipline catalog and the meet's rankings written as the services expect them. */
final class TestFiles {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private TestFiles() {
    }

    /** The disciplines (a JSON array) as a catalog file. */
    static String catalog(String disciplines) throws IOException {
        ObjectNode catalog = objectMapper.createObjectNode();
        catalog.put("format", "mlaic-disciplines");
        catalog.put("version", 1);
        catalog.put("max_disciplines", 4);
        catalog.set("disciplines", objectMapper.readTree(disciplines));
        return objectMapper.writeValueAsString(catalog);
    }

    /**
     * Writes the catalog to dir/disciplines.json and a rankings.json with one
     * ranking per discipline, so every discipline is in the meet's program.
     */
    static void writeCatalog(Path dir, String disciplines) throws IOException {
        Files.writeString(dir.resolve("disciplines.json"), catalog(disciplines));
        ObjectNode config = objectMapper.createObjectNode();
        config.put("max_disciplines_per_ranking", 4);
        ArrayNode rankings = config.putArray("rankings");
        int id = 1;
        for (JsonNode d : objectMapper.readTree(disciplines)) {
            ObjectNode ranking = rankings.addObject();
            ranking.put("id", id++);
            ranking.put("name", d.path("name").asText(d.path("event").asText("Discipline " + d.path("id").asInt())));
            ranking.put("level", "team".equals(d.path("level").asText()) ? "team" : "individual");
            ranking.putArray("entries").addObject().putArray("discipline_ids").add(d.path("id").asInt());
        }
        Files.writeString(dir.resolve("rankings.json"), objectMapper.writeValueAsString(config));
    }

    static DataService dataService(Path dir) {
        return new DataService(dir.resolve("data.json").toString(), dir.resolve("disciplines.json").toString(),
            dir.resolve("rankings.json").toString());
    }
}
