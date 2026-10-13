package org.sterl.llmpeon.session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The per-workspace session store under {@code <base>/sessions/<wsName>-<hash16>/}.
 *
 * <p>Opening verifies the store identity ({@code store.json} records the full normalized
 * workspace path — a mismatch means a hash collision or a copied store and is refused) and takes
 * the {@code .lock} FileLock. Without the lock the store opens read-only: listing and loading
 * work, every mutation throws.</p>
 *
 * <p>{@code index.json} is only a cache for cheap listing; it is rebuilt from the checkpoints
 * whenever missing or unreadable. Losing it can never lose a session.</p>
 */
public class SessionStore implements AutoCloseable {

    static final String STORE_JSON = "store.json";
    static final String LOCK_FILE = ".lock";
    static final String INDEX_JSON = "index.json";
    static final int HASH_LENGTH = 16;

    record StoreIdentity(String workspacePath, long createdAt) {}

    public record IndexEntry(
            String id,
            String title,
            String projectName,
            SessionStatus status,
            String mode,
            long createdAt,
            long updatedAt,
            boolean compatible) {}

    record Index(List<IndexEntry> entries) {}

    public static class StoreOwnershipException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public StoreOwnershipException(String message) {
            super(message);
        }
    }

    private final Path root;
    private final String workspacePath;
    private final FileChannel lockChannel;
    private final FileLock lock;

    private SessionStore(Path root, String workspacePath, FileChannel lockChannel, FileLock lock) {
        this.root = root;
        this.workspacePath = workspacePath;
        this.lockChannel = lockChannel;
        this.lock = lock;
    }

    // ---- opening ----

    /** Resolves {@code <base>/sessions/<sanitized-name>-<sha256(realPath)[0:16]>} for a workspace. */
    public static Path storeDirectory(Path baseDir, Path workspace) {
        var normalized = normalize(workspace);
        var name = sanitize(normalized.getFileName() == null ? "workspace" : normalized.getFileName().toString());
        var hash = SessionFiles.sha256Hex(normalized.toString()).substring(0, HASH_LENGTH);
        return baseDir.resolve("sessions").resolve(name + "-" + hash);
    }

    public static SessionStore open(Path baseDir, Path workspace) {
        var normalized = normalize(workspace);
        var root = storeDirectory(baseDir, workspace);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create session store " + root, e);
        }
        verifyOrWriteIdentity(root, normalized.toString());

        FileChannel channel = null;
        FileLock lock = null;
        try {
            channel = FileChannel.open(root.resolve(LOCK_FILE),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
        } catch (IOException | OverlappingFileLockException e) {
            // keep channel for cleanup; lock stays null → read-only
        }
        if (lock == null && channel != null) {
            try { channel.close(); } catch (IOException ignored) {}
            channel = null;
        }
        return new SessionStore(root, normalized.toString(), channel, lock);
    }

    private static void verifyOrWriteIdentity(Path root, String workspacePath) {
        var identityFile = root.resolve(STORE_JSON);
        if (Files.exists(identityFile)) {
            try {
                var identity = SessionFiles.mapper().readValue(Files.readAllBytes(identityFile), StoreIdentity.class);
                if (identity.workspacePath() != null && !identity.workspacePath().equals(workspacePath)) {
                    throw new StoreOwnershipException("Session store " + root + " belongs to workspace '"
                            + identity.workspacePath() + "', not '" + workspacePath + "' — refusing to share it.");
                }
                return;
            } catch (IOException e) {
                // unreadable identity: quarantine and rewrite below — sessions themselves are untouched
                SessionFiles.quarantine(root, identityFile);
            }
        }
        try {
            SessionFiles.writeAtomic(identityFile, SessionFiles.mapper()
                    .writeValueAsBytes(new StoreIdentity(workspacePath, System.currentTimeMillis())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path normalize(Path workspace) {
        try {
            return workspace.toRealPath();
        } catch (IOException e) {
            return workspace.toAbsolutePath().normalize();
        }
    }

    static String sanitize(String name) {
        var cleaned = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        return cleaned.isEmpty() ? "workspace" : cleaned;
    }

    // ---- state ----

    public Path root() {
        return root;
    }

    public String workspacePath() {
        return workspacePath;
    }

    /** False when another process holds the lock — mutations are refused then. */
    public boolean isWritable() {
        return lock != null && lock.isValid();
    }

    private void requireWritable() {
        if (!isWritable())
            throw new IllegalStateException("Session store " + root + " is read-only (locked by another instance)");
    }

    // ---- sessions ----

    /**
     * Materializes a new session (the lazy-persistence step on the first submitted message):
     * creates the directory and its initial checkpoint, and updates the index.
     */
    public SessionDirectory createSession(SessionMeta meta, SessionAggregate aggregate) {
        requireWritable();
        var id = meta.id() != null ? meta.id() : UUID.randomUUID().toString();
        var withId = meta.toBuilder().id(id).workspacePath(workspacePath).build();
        var dir = SessionDirectory.create(root, root.resolve(id), withId, aggregate);
        updateIndex(dir);
        return dir;
    }

    public Optional<SessionDirectory> openSession(String sessionId) {
        var dir = root.resolve(sessionId);
        if (!Files.isDirectory(dir)) return Optional.empty();
        return SessionDirectory.open(root, dir);
    }

    public void deleteSession(String sessionId) {
        requireWritable();
        var dir = root.resolve(sessionId);
        if (!Files.isDirectory(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete session " + sessionId, e);
        }
        writeIndex(new Index(listSessions().stream().filter(s -> !s.id().equals(sessionId)).toList()));
    }

    /** Recent-first listing from the index cache; rebuilt from checkpoints when missing or stale. */
    public List<IndexEntry> listSessions() {
        var indexFile = root.resolve(INDEX_JSON);
        if (Files.exists(indexFile)) {
            try {
                var index = SessionFiles.mapper().readValue(Files.readAllBytes(indexFile), Index.class);
                if (index.entries() != null) return sorted(index.entries());
            } catch (IOException e) {
                // fall through to rebuild
            }
        }
        return rebuildIndex();
    }

    /** Scans every session directory and rewrites the cache — the index is never authoritative. */
    public List<IndexEntry> rebuildIndex() {
        var entries = new ArrayList<IndexEntry>();
        try (var dirs = Files.list(root)) {
            for (var dir : dirs.toList()) {
                if (!Files.isDirectory(dir) || dir.getFileName().toString().equals("corrupt")) continue;
                SessionDirectory.open(root, dir)
                        .flatMap(SessionDirectory::load)
                        .ifPresent(loaded -> entries.add(toEntry(loaded)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan session store " + root, e);
        }
        var result = sorted(entries);
        if (isWritable()) writeIndex(new Index(result));
        return result;
    }

    /** Refreshes one session's row after a commit; call from the session writer. */
    public void updateIndex(SessionDirectory session) {
        if (!isWritable()) return;
        var loaded = session.load();
        if (loaded.isEmpty()) return;
        var entry = toEntry(loaded.get());
        var entries = new ArrayList<>(listSessions().stream()
                .filter(s -> !s.id().equals(entry.id())).toList());
        entries.add(entry);
        writeIndex(new Index(sorted(entries)));
    }

    private static IndexEntry toEntry(SessionDirectory.LoadedSession loaded) {
        var meta = loaded.checkpoint().meta();
        return new IndexEntry(meta.id(), meta.title(), meta.projectName(), loaded.effectiveStatus(),
                loaded.checkpoint().aggregate() == null || loaded.checkpoint().aggregate().activeMode() == null
                        ? null : loaded.checkpoint().aggregate().activeMode().name(),
                meta.createdAt(), meta.updatedAt(), loaded.compatible());
    }

    private static List<IndexEntry> sorted(List<IndexEntry> entries) {
        return entries.stream().sorted(Comparator.comparingLong(IndexEntry::updatedAt).reversed()).toList();
    }

    private void writeIndex(Index index) {
        try {
            SessionFiles.writeAtomic(root.resolve(INDEX_JSON),
                    SessionFiles.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(index));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            if (lock != null && lock.isValid()) lock.release();
            if (lockChannel != null) lockChannel.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
