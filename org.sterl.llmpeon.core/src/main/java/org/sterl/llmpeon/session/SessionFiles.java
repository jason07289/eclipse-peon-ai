package org.sterl.llmpeon.session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Low-level file helpers for the session store.
 *
 * <p>All multi-byte writes go through {@link #writeAtomic(Path, byte[])}: write to a temp file in
 * the same directory, fsync, then rename over the target. A reader therefore only ever sees the
 * old or the new content, never a torn file. This is the building block the checkpoint commit
 * protocol relies on — see the chat-session design document.</p>
 */
public final class SessionFiles {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    private SessionFiles() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static void writeAtomic(Path target, byte[] content) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            try (FileChannel ch = FileChannel.open(tmp,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ch.write(java.nio.ByteBuffer.wrap(content));
                ch.force(true);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            fsyncDirectory(target.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + target, e);
        }
    }

    public static void writeAtomic(Path target, String content) {
        writeAtomic(target, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Durability of a rename needs the directory entry flushed too; a no-op where unsupported (Windows). */
    static void fsyncDirectory(Path dir) {
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            // best effort — not available on all platforms/filesystems
        }
    }

    public static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(String content) {
        return sha256Hex(content.getBytes(StandardCharsets.UTF_8));
    }

    /** Moves an unreadable file into the store's quarantine directory instead of deleting it. */
    public static void quarantine(Path storeRoot, Path file) {
        if (!Files.exists(file)) return;
        try {
            Path dir = storeRoot.resolve("corrupt");
            Files.createDirectories(dir);
            Path target = dir.resolve(System.currentTimeMillis() + "-" + file.getFileName());
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to quarantine " + file, e);
        }
    }
}
