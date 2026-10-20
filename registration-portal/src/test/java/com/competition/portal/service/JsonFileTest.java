package com.competition.portal.service;

import com.competition.portal.model.Contact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JsonFileTest {
    @TempDir
    Path dir;

    private JsonFile<Contact> file() {
        return new JsonFile<>(dir.resolve("sub/contact.json"), Contact.class, Contact::new);
    }

    @Test
    void aMissingFileReadsAsTheEmptyDocument() {
        Contact contact = file().read();
        assertNull(contact.name);
        assertFalse(Files.exists(dir.resolve("sub/contact.json")), "reading creates nothing");
    }

    @Test
    void savedDataComesBackAndNoTempFileIsLeft() throws Exception {
        JsonFile<Contact> file = file();
        file.update(c -> { c.name = "Anna"; c.email = "anna@example.org"; return null; });

        Contact read = new JsonFile<>(file.path(), Contact.class, Contact::new).read();
        assertEquals("Anna", read.name);
        assertEquals("anna@example.org", read.email);
        assertFalse(Files.exists(dir.resolve("sub/contact.json.tmp")));
        assertTrue(Files.readString(file.path()).contains("\"email\""), "snake_case JSON on disk");
    }

    @Test
    void aFailedChangeSavesNothing() throws Exception {
        JsonFile<Contact> file = file();
        file.update(c -> { c.name = "Anna"; return null; });
        assertThrows(IllegalArgumentException.class, () -> file.update(c -> {
            c.name = "Changed";
            throw new IllegalArgumentException("refused");
        }));
        assertEquals("Anna", file.read().name);
    }

    @Test
    void everyReadIsACopy() throws Exception {
        JsonFile<Contact> file = file();
        file.update(c -> { c.name = "Anna"; return null; });
        file.read().name = "Changed in memory";
        assertEquals("Anna", file.read().name);
    }

    @Test
    void anUnreadableFileIsAHardErrorAndIsNeverOverwritten() throws Exception {
        JsonFile<Contact> file = file();
        Files.createDirectories(file.path().getParent());
        Files.writeString(file.path(), "{ this is not json");

        assertThrows(StorageException.class, file::read);
        assertThrows(StorageException.class, () -> file.update(c -> { c.name = "x"; return null; }));
        assertEquals("{ this is not json", Files.readString(file.path()));
    }

    @Test
    void unknownFieldsAreIgnored() throws Exception {
        JsonFile<Contact> file = file();
        Files.createDirectories(file.path().getParent());
        Files.writeString(file.path(), "{\"name\":\"Anna\",\"added_later\":1}");
        assertEquals("Anna", file.read().name);
    }
}
