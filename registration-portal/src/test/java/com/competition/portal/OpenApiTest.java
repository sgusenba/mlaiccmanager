package com.competition.portal;

import com.competition.portal.resource.AdminClubResource;
import com.competition.portal.resource.AdminMeetResource;
import com.competition.portal.resource.AdminStarterResource;
import com.competition.portal.resource.AdminUserResource;
import com.competition.portal.resource.AuthResource;
import com.competition.portal.resource.ClubResource;
import com.competition.portal.resource.CountriesResource;
import com.competition.portal.resource.HealthResource;
import com.competition.portal.resource.SettingsResource;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** openapi.yaml lists exactly the endpoints of the resource classes. */
class OpenApiTest {
    private static final List<Class<?>> RESOURCES = List.of(HealthResource.class, AuthResource.class,
        AdminMeetResource.class, AdminClubResource.class, AdminUserResource.class, AdminStarterResource.class,
        ClubResource.class, CountriesResource.class, SettingsResource.class);
    private static final Map<Class<? extends Annotation>, String> METHODS =
        Map.of(GET.class, "get", POST.class, "post", PUT.class, "put", DELETE.class, "delete");

    /** "/admin/meets/{id: \\d+}" and "/x" → "/admin/meets/{id}/x" */
    private static String join(String base, String sub) {
        String path = ("/" + base + "/" + sub).replaceAll("\\{(\\w+):[^}]*}", "{$1}").replaceAll("/+", "/");
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static TreeSet<String> inCode() {
        TreeSet<String> endpoints = new TreeSet<>();
        for (Class<?> resource : RESOURCES) {
            String base = resource.getAnnotation(Path.class).value();
            for (Method m : resource.getDeclaredMethods()) {
                for (var verb : METHODS.entrySet()) {
                    if (m.isAnnotationPresent(verb.getKey())) {
                        Path sub = m.getAnnotation(Path.class);
                        endpoints.add(verb.getValue() + " " + join(base, sub == null ? "" : sub.value()));
                    }
                }
            }
        }
        return endpoints;
    }

    private static TreeSet<String> inSpec() throws IOException {
        TreeSet<String> endpoints = new TreeSet<>();
        String path = null;
        boolean inPaths = false;
        Matcher m;
        for (String line : Files.readAllLines(java.nio.file.Path.of("openapi.yaml"))) {
            if (!line.startsWith(" ") && !line.isBlank()) {
                inPaths = line.equals("paths:");
            } else if (inPaths && (m = Pattern.compile("^  (/\\S*):$").matcher(line)).matches()) {
                path = m.group(1);
            } else if (inPaths && path != null && (m = Pattern.compile("^    (get|post|put|delete):$").matcher(line)).matches()) {
                endpoints.add(m.group(1) + " " + path);
            }
        }
        return endpoints;
    }

    @Test
    void theSpecListsEveryEndpointAndNoOther() throws IOException {
        TreeSet<String> code = inCode();
        TreeSet<String> spec = inSpec();
        assertTrue(code.size() > 40, code.toString());
        TreeSet<String> missing = new TreeSet<>(code);
        missing.removeAll(spec);
        TreeSet<String> extra = new TreeSet<>(spec);
        extra.removeAll(code);
        assertEquals(new TreeSet<>(), missing, "in the code, not in openapi.yaml");
        assertEquals(new TreeSet<>(), extra, "in openapi.yaml, not in the code");
    }
}
