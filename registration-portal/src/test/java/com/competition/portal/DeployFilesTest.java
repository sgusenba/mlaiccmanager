package com.competition.portal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * portal-install.sh and portal-update.sh download files from the release by
 * name; each must exist in deploy/portal/ and be published by
 * portal-release.yml, else installing or updating fails on a server.
 */
class DeployFilesTest {
    private static final Path DEPLOY = Path.of("..", "deploy", "portal");

    /** The names in the bash array NAME=(...) of the script. */
    private static List<String> array(String script, String name) throws IOException {
        Matcher m = Pattern.compile("(?m)^" + name + "=\\(([^)]*)\\)").matcher(Files.readString(DEPLOY.resolve(script)));
        assertTrue(m.find(), script + " has no " + name);
        return Arrays.asList(m.group(1).trim().split("\\s+"));
    }

    @Test
    void everyFileTheScriptsDownloadIsPublished() throws IOException {
        String workflow = Files.readString(Path.of("..", ".github", "workflows", "portal-release.yml"));
        List<String> files = new ArrayList<>();
        files.addAll(array("portal-install.sh", "SCRIPTS"));
        files.addAll(array("portal-install.sh", "UNITS"));
        files.addAll(List.of("Caddyfile.example", "portal.env.example"));
        for (String file : files) {
            assertTrue(Files.exists(DEPLOY.resolve(file)), "deploy/portal/" + file + " is missing");
            assertTrue(workflow.contains("deploy/portal/" + file), "portal-release.yml does not publish " + file);
        }
        assertTrue(workflow.contains("portal-release/mlaic-portal.jar") && workflow.contains("SHA256SUMS"), "jar and checksums are published");
        // The update check keeps the same scripts current
        assertEquals(array("portal-install.sh", "SCRIPTS"), array("portal-update.sh", "SCRIPTS"));
    }
}
