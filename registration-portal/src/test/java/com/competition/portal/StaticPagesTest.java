package com.competition.portal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pages work under the strict Content-Security-Policy the reverse proxy
 * sets (scripts and styles only from the portal itself): no inline scripts,
 * no event handler attributes, nothing loaded from other sites.
 */
class StaticPagesTest {
    private static final Path STATIC = Path.of("static");

    private static List<Path> files(String glob) throws IOException {
        try (Stream<Path> paths = Files.walk(STATIC)) {
            return paths.filter(p -> p.getFileSystem().getPathMatcher("glob:" + glob).matches(p.getFileName())).toList();
        }
    }

    @Test
    void pagesHaveNoInlineScriptsOrForeignSources() throws IOException {
        List<Path> pages = files("*.html");
        assertTrue(pages.size() >= 4, "login, sheet, admin, start page");
        Pattern script = Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>", Pattern.CASE_INSENSITIVE);
        Pattern handler = Pattern.compile("\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE);
        Pattern foreign = Pattern.compile("(?:src|href)=\"(?:https?:)?//", Pattern.CASE_INSENSITIVE);
        for (Path page : pages) {
            String html = Files.readString(page);
            assertFalse(script.matcher(html).find(), page + " has an inline script");
            assertFalse(handler.matcher(html).find(), page + " has an event handler attribute");
            Matcher m = foreign.matcher(html);
            assertFalse(m.find(), page + " loads something from another site");
        }
    }

    @Test
    void scriptsImportOnlyFromThePortal() throws IOException {
        Pattern foreignImport = Pattern.compile("import[^;]*from\\s+['\"](?:https?:)?//");
        for (Path js : files("*.js")) {
            assertFalse(foreignImport.matcher(Files.readString(js)).find(), js + " imports from another site");
        }
    }
}
