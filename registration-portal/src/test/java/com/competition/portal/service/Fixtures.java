package com.competition.portal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/** Test files: real exports from the Manager (src/test/resources/fixtures) and the repository's shipped defaults. */
public final class Fixtures {
    private Fixtures() {
    }

    /** The OÖ VL LM rankings: O/R rankings with two entries, sums (Remington, Schulhof), team rankings. */
    public static ObjectNode ooeRankings() {
        return (ObjectNode) resource("fixtures/rankings-ooe-vl-lm.json");
    }

    /** The MLAIC discipline catalog the OÖ rankings were made with. */
    public static ObjectNode mlaicCatalog() {
        return (ObjectNode) resource("fixtures/disciplines-mlaic.json");
    }

    /** The Manager's shipped default rankings and catalog, in the repository root: the export format as it is now. */
    public static JsonNode repoFile(String name) {
        try {
            return JsonFile.MAPPER.readTree(Path.of("..", name).toFile());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode resource(String path) {
        try (InputStream in = Fixtures.class.getClassLoader().getResourceAsStream(path)) {
            return JsonFile.MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
