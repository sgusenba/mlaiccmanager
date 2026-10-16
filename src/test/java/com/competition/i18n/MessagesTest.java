package com.competition.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class MessagesTest {
    @AfterEach
    void reset() {
        Messages.setCurrent(null);
    }

    @Test
    void englishByDefaultWithArguments() {
        assertEquals("Start 1-52-1 does not exist", Messages.get("start.doesNotExist", "1-52-1"));
    }

    @Test
    void germanForTheRequestLanguage() {
        Messages.setCurrent(Locale.GERMAN);
        assertEquals("Start 1-52-1 existiert nicht", Messages.get("start.doesNotExist", "1-52-1"));
        assertEquals("Colt (Original)", Messages.disciplineWithType("Colt", "original"));
    }

    @Test
    void argumentsAreNotFormattedAsNumbers() {
        assertEquals("Ranking 1234 not found", Messages.get("ranking.notFound", 1234));
    }

    @Test
    void unknownKeysAndValuesFallBack() {
        Messages.setCurrent(Locale.GERMAN);
        assertEquals("no.such.key", Messages.get("no.such.key"));
        assertEquals("Colt (flintlock)", Messages.disciplineWithType("Colt", "flintlock"));
    }

    @Test
    void supportedLanguages() {
        assertEquals(Locale.GERMAN, Messages.supported("de-AT"));
        assertEquals(Locale.ENGLISH, Messages.supported("en_GB"));
        assertNull(Messages.supported("fr"));
        assertNull(Messages.supported(""));
    }

    @Test
    void everyLanguageHasTheSameKeysAndPlaceholders() throws Exception {
        Properties english = load("messages.properties");
        Properties german = load("messages_de.properties");
        assertEquals(new TreeSet<>(english.stringPropertyNames()), new TreeSet<>(german.stringPropertyNames()));
        for (String key : english.stringPropertyNames()) {
            assertEquals(placeholders(english.getProperty(key)), placeholders(german.getProperty(key)), key);
        }
    }

    private static Properties load(String name) throws Exception {
        Properties properties = new Properties();
        try (InputStream in = MessagesTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, name);
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }

    private static Set<String> placeholders(String message) {
        Set<String> found = new TreeSet<>();
        Matcher m = Pattern.compile("\\{\\d+}").matcher(message);
        while (m.find()) {
            found.add(m.group());
        }
        return found;
    }
}
