package org.sterl.llmpeon.session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One session's on-disk state and its commit protocol:
 *
 * <ol>
 *   <li>write {@code checkpoints/ckpt-&lt;rev&gt;.json} (atomic: temp + fsync + rename)</li>
 *   <li>switch the {@code CURRENT} pointer (atomic replace)</li>
 *   <li>prune checkpoints, keeping the newest two</li>
 * </ol>
 *
 * A crash between any two steps leaves the previous revision loadable. Recovery follows
 * {@code CURRENT}, validates the checksum, falls back to older checkpoints and quarantines
 * unreadable files (never deletes them).
 *
 * <p>This class is NOT thread-safe on its own; the per-session writer (design §6) must
 * serialize all mutations. {@link #commit} still guards against a second process on a shared
 * store by re-reading {@code CURRENT} and refusing to overwrite a newer revision.</p>
 */
public class SessionDirectory {

    static final String CURRENT = "CURRENT";
    static final String CHECKPOINTS = "checkpoints";
    static final String TRANSCRIPT = "transcript.jsonl";
    private static final int KEEP_CHECKPOINTS = 2;

    /** On-disk file layout: checksum over the mapper bytes of the typed payload. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CheckpointFile(String checksum, Checkpoint payload) {}

    /** A loaded session: the checkpoint plus what the WAL says happened after it. */
    public record LoadedSession(
            Checkpoint checkpoint,
            Transcript.RecoveryState recovery,
            boolean compatible) {

        /** The stored status, overridden to INTERRUPTED when the WAL shows uncommitted turns. */
        public SessionStatus effectiveStatus() {
            if (recovery != null && recovery.interrupted()) return SessionStatus.INTERRUPTED;
            return checkpoint.meta().status();
        }
    }

    /** Thrown when another writer moved CURRENT forward; the attempted commit is preserved as a conflict file. */
    public static class CommitConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final Path conflictFile;
        CommitConflictException(String message, Path conflictFile) {
            super(message);
            this.conflictFile = conflictFile;
        }
    }

    private final Path storeRoot;
    private final Path dir;
    private long currentRevision;

    SessionDirectory(Path storeRoot, Path dir, long currentRevision) {
        this.storeRoot = storeRoot;
        this.dir = dir;
        this.currentRevision = currentRevision;
    }

    public Path dir() {
        return dir;
    }

    public String sessionId() {
        return dir.getFileName().toString();
    }

    public long currentRevision() {
        return currentRevision;
    }

    public Transcript openTranscript() {
        return openTranscript(0);
    }

    public Transcript openTranscript(long maxBytes) {
        return Transcript.open(dir.resolve(TRANSCRIPT), maxBytes);
    }

    public List<TranscriptEvent> readTranscript() {
        return Transcript.readAll(dir.resolve(TRANSCRIPT));
    }

    // ---- creation / opening ----

    /** Creates the directory with its initial checkpoint (revision 1) — the "materialize on first message" step. */
    static SessionDirectory create(Path storeRoot, Path dir, SessionMeta meta, SessionAggregate aggregate) {
        if (Files.exists(dir.resolve(CURRENT)))
            throw new IllegalStateException("Session already exists: " + dir);
        var sd = new SessionDirectory(storeRoot, dir, 0);
        sd.commit(meta, aggregate, 0);
        return sd;
    }

    static Optional<SessionDirectory> open(Path storeRoot, Path dir) {
        var sd = new SessionDirectory(storeRoot, dir, 0);
        var loaded = sd.load();
        return loaded.isEmpty() ? Optional.empty() : Optional.of(sd);
    }

    // ---- commit protocol ----

    /**
     * Commits the next revision. The caller passes the complete state — a checkpoint is
     * self-contained by design, never a delta.
     */
    public Checkpoint commit(SessionMeta meta, SessionAggregate aggregate, long lastTranscriptSeq) {
        long diskRevision = readCurrentPointer().orElse(0L);
        long next = currentRevision + 1;
        var checkpoint = Checkpoint.builder()
                .schemaVersion(Checkpoint.SCHEMA_VERSION)
                .messageFormatVersion(Checkpoint.MESSAGE_FORMAT_VERSION)
                .revision(next)
                .meta(meta)
                .aggregate(aggregate)
                .lastTranscriptSeq(lastTranscriptSeq)
                .build();

        if (diskRevision > currentRevision) {
            // another process moved the session forward — keep our state as a conflict copy
            var conflict = dir.resolve("conflict-" + System.currentTimeMillis() + ".json");
            SessionFiles.writeAtomic(conflict, toFileBytes(checkpoint));
            throw new CommitConflictException("Session " + sessionId() + " was modified by another process"
                    + " (disk revision " + diskRevision + " > local " + currentRevision + ")", conflict);
        }

        SessionFiles.writeAtomic(checkpointPath(next), toFileBytes(checkpoint));   // step 1
        SessionFiles.writeAtomic(dir.resolve(CURRENT), Long.toString(next));       // step 2 — the commit point
        currentRevision = next;
        pruneCheckpoints();                                                        // step 3
        return checkpoint;
    }

    // ---- recovery / loading ----

    /**
     * Loads the newest valid checkpoint: CURRENT first, then older checkpoints newest-first.
     * Unreadable checkpoint files are quarantined. Empty result = nothing loadable (the caller
     * lists the session as damaged/incompatible, it is never silently deleted).
     */
    public Optional<LoadedSession> load() {
        var candidates = listCheckpointFiles();
        // prefer the CURRENT pointer, then anything newer-first the pointer may not cover
        var pointer = readCurrentPointer();
        if (pointer.isPresent()) {
            var p = checkpointPath(pointer.get());
            candidates.remove(p);
            candidates.add(0, p);
        }
        for (Path candidate : candidates) {
            var checkpoint = readValidated(candidate);
            if (checkpoint.isEmpty()) {
                SessionFiles.quarantine(storeRoot, candidate);
                continue;
            }
            var cp = checkpoint.get();
            currentRevision = cp.revision();
            var recovery = Transcript.analyze(readTranscript(), cp.lastTranscriptSeq());
            return Optional.of(new LoadedSession(cp, recovery, cp.isCompatible()));
        }
        return Optional.empty();
    }

    private Optional<Checkpoint> readValidated(Path file) {
        if (!Files.exists(file)) return Optional.empty();
        try {
            var parsed = SessionFiles.mapper().readValue(Files.readAllBytes(file), CheckpointFile.class);
            if (parsed.payload() == null || parsed.checksum() == null) return Optional.empty();
            // A FUTURE schema version deserializes lossily into this class, so its checksum cannot
            // be re-verified here. It is listed as incompatible (never quarantined, never deleted).
            if (parsed.payload().schemaVersion() > Checkpoint.SCHEMA_VERSION)
                return Optional.of(parsed.payload());
            var expected = SessionFiles.sha256Hex(SessionFiles.mapper().writeValueAsBytes(parsed.payload()));
            if (!expected.equals(parsed.checksum())) return Optional.empty();
            return Optional.of(parsed.payload());
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private byte[] toFileBytes(Checkpoint checkpoint) {
        try {
            var payload = SessionFiles.mapper().writeValueAsBytes(checkpoint);
            var file = new CheckpointFile(SessionFiles.sha256Hex(payload), checkpoint);
            return SessionFiles.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- internals ----

    Path checkpointPath(long revision) {
        return dir.resolve(CHECKPOINTS).resolve(String.format("ckpt-%06d.json", revision));
    }

    private Optional<Long> readCurrentPointer() {
        var f = dir.resolve(CURRENT);
        if (!Files.exists(f)) return Optional.empty();
        try {
            return Optional.of(Long.parseLong(Files.readString(f, StandardCharsets.UTF_8).strip()));
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** All checkpoint files, newest revision first. */
    private List<Path> listCheckpointFiles() {
        var cpDir = dir.resolve(CHECKPOINTS);
        if (!Files.isDirectory(cpDir)) return new java.util.ArrayList<>();
        try (var files = Files.list(cpDir)) {
            return files.filter(p -> p.getFileName().toString().matches("ckpt-\\d+\\.json"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void pruneCheckpoints() {
        var files = listCheckpointFiles();
        for (int i = KEEP_CHECKPOINTS; i < files.size(); i++) {
            try {
                Files.deleteIfExists(files.get(i));
            } catch (IOException e) {
                // pruning is best-effort; an undeleted old checkpoint is harmless
            }
        }
    }
}
