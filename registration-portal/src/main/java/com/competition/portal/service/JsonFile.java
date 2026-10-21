package com.competition.portal.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * One JSON document on disk, as the Manager keeps its files: every access
 * holds the file's lock, a save writes a .tmp file next to it and moves it
 * over the old one in one step, so a crash never leaves half a file. A file
 * that exists but cannot be read is a hard error ({@link StorageException})
 * and is never overwritten.
 *
 * <p>Each read deserializes the file anew, so callers get their own copy and
 * a change that fails half-way leaves nothing behind.
 */
public class JsonFile<T> {
    /** snake_case on disk, like the Manager's files and the export formats. */
    public static final ObjectMapper MAPPER = new ObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final ReentrantLock lock = new ReentrantLock();
    private final Path path;
    private final Class<T> type;
    private final Supplier<T> empty;

    /**
     * @param empty the document while the file does not exist yet
     */
    public JsonFile(Path path, Class<T> type, Supplier<T> empty) {
        this.path = path;
        this.type = type;
        this.empty = empty;
    }

    public Path path() {
        return path;
    }

    /** A copy of the stored document. */
    public T read() {
        lock.lock();
        try {
            return load();
        } finally {
            lock.unlock();
        }
    }

    /** Runs change on the stored document and saves it if change returns normally. */
    public <R> R update(Change<T, R> change) throws Exception {
        return update(change, null);
    }

    /**
     * Like {@link #update(Change)}, then runs afterSave with the result while
     * the lock is still held, e.g. to append to a log in the same order as the
     * saves. afterSave does not run if the change or the save fails.
     */
    public <R> R update(Change<T, R> change, Change<R, Void> afterSave) throws Exception {
        lock.lock();
        try {
            T data = load();
            R result = change.apply(data);
            save(data);
            if (afterSave != null) {
                afterSave.apply(result);
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    /** Saves the document change returns in place of the stored one (given to change to check it). */
    public T replace(Change<T, T> change) throws Exception {
        lock.lock();
        try {
            T data = change.apply(load());
            save(data);
            return data;
        } finally {
            lock.unlock();
        }
    }

    /** Runs action while holding the lock, e.g. to copy or delete the file as a whole. */
    public <R> R exclusive(Change<Path, R> action) throws Exception {
        lock.lock();
        try {
            return action.apply(path);
        } finally {
            lock.unlock();
        }
    }

    /** The stored document, or the empty one while the file does not exist. Caller holds the lock. */
    private T load() {
        if (!Files.exists(path)) {
            return empty.get();
        }
        try {
            T data = MAPPER.readValue(path.toFile(), type);
            if (data == null) {
                throw new IOException("empty document");
            }
            return data;
        } catch (IOException e) {
            throw new StorageException("Cannot read " + path + ": " + e.getMessage(), e);
        }
    }

    /** Writes the document via a .tmp file and an atomic move. Caller holds the lock. */
    void save(T data) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        MAPPER.writeValue(temp.toFile(), data);
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
