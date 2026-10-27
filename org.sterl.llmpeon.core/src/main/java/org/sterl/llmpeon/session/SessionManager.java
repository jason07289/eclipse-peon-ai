package org.sterl.llmpeon.session;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.sterl.llmpeon.PeonMode;

import lombok.extern.slf4j.Slf4j;

/**
 * Session lifecycle for the chat view (chat-session design §6/§9):
 *
 * <ul>
 *   <li><b>Lazy persistence</b> — {@link #startFresh()} is purely in-memory; the session
 *       directory, initial checkpoint and index row are materialized on the first
 *       {@code turnStarted}, before the WAL record and before the model call.</li>
 *   <li><b>Per-session writer</b> — checkpoint commits run on a single-threaded executor, so
 *       turn commits, compaction, clear, resume and delete are serialized. WAL appends
 *       ({@code TURN_STARTED}, {@code TOOL_INTENT}) stay synchronous on the turn thread because
 *       they must be durable before the action they announce.</li>
 *   <li><b>Stale protection</b> — every turn is registered against the session and generation it
 *       started under. Events of unknown turns are dropped; a turn that ends after a session
 *       switch is NOT committed (its WAL records then correctly classify it as interrupted).</li>
 * </ul>
 *
 * The UI protocol (cancel the running job, then switch) is the primary guard; the checks here
 * are the safety net that makes protocol violations harmless.
 */
@Slf4j
public class SessionManager implements AutoCloseable {

    /** The files a running turn writes to — captured at turnStarted, immune to later switches. */
    private record TurnContext(SessionDirectory dir, Transcript transcript, SessionMeta meta, long generation) {}

    /** Per-session transcript detail cap (design §9); checkpoints keep working beyond it. */
    static final long TRANSCRIPT_MAX_BYTES = 50L * 1024 * 1024;
    /** Covers a reopened view racing the previous view's background close (same JVM only). */
    static final Duration STORE_LOCK_WAIT = Duration.ofSeconds(3);

    private final SessionStore store;
    private final SessionSource source;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "peon-session-writer");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong generation = new AtomicLong();
    private final Map<String, TurnContext> activeTurns = new ConcurrentHashMap<>();
    /** Surfaced to the status line; persistence failures must never break the chat itself. */
    private volatile Consumer<Exception> onPersistenceError = e -> log.error("Session persistence failed", e);

    // current session — null dir/transcript until materialized by the first message
    private final Object lock = new Object();
    private SessionDirectory dir;
    private Transcript transcript;
    private SessionMeta meta;

    public SessionManager(Path baseDir, Path workspace, SessionSource source) {
        this(SessionStore.open(baseDir, workspace, STORE_LOCK_WAIT), source);
    }

    SessionManager(SessionStore store, SessionSource source) {
        this.store = store;
        this.source = source;
    }

    public SessionStore store() {
        return store;
    }

    public void setOnPersistenceError(Consumer<Exception> handler) {
        if (handler != null) this.onPersistenceError = handler;
    }

    /** False when another instance holds the store lock: history is browsable, nothing is saved. */
    public boolean isWritable() {
        return store.isWritable();
    }

    // ---- lifecycle ----

    /**
     * New empty in-memory session; nothing is created on disk (design: no empty sessions).
     * Never blocks: commits already queued still hold their own transcript reference, which is
     * closed on the writer after them.
     */
    public void startFresh() {
        synchronized (lock) {
            closeTranscript();
            dir = null;
            meta = null;
            generation.incrementAndGet();
        }
    }

    /**
     * Loads a saved session and makes it current. The caller (PeonAiService) injects the
     * aggregate's memories and mode — with no reset, no re-injection, no auto-run.
     */
    public Optional<SessionDirectory.LoadedSession> resume(String sessionId) {
        drain(); // the session may be the one just left — its last commit must be on disk; NOT under lock
        synchronized (lock) {
            var session = store.openSession(sessionId);
            if (session.isEmpty()) return Optional.empty();
            var loaded = session.get().load();
            if (loaded.isEmpty()) return Optional.empty();

            closeTranscript();
            dir = session.get();
            transcript = dir.openTranscript(TRANSCRIPT_MAX_BYTES);
            meta = loaded.get().checkpoint().meta().toBuilder()
                    .updatedAt(System.currentTimeMillis()).build();
            generation.incrementAndGet();
            final var d = dir;
            submit(() -> store.updateIndex(d)); // touch updatedAt before any cleanup can see it
            return loaded;
        }
    }

    /**
     * Deleting the active session first switches to a fresh one (design §9.1). The delete itself
     * runs on the writer — after any queued commit of that session and serialized with index
     * updates — so the caller never blocks; the future completes (or fails) once it is gone.
     */
    public CompletableFuture<Void> deleteSession(String sessionId) {
        synchronized (lock) {
            if (dir != null && dir.sessionId().equals(sessionId)) startFresh();
        }
        return CompletableFuture.runAsync(() -> store.deleteSession(sessionId), writer);
    }

    public java.util.List<SessionStore.IndexEntry> listSessions() {
        return store.listSessions();
    }

    /** Current session id, or null while the session is still unpersisted. */
    public String currentSessionId() {
        synchronized (lock) {
            return dir == null ? null : dir.sessionId();
        }
    }

    public long generation() {
        return generation.get();
    }

    public boolean isCurrent(long gen) {
        return generation.get() == gen;
    }

    /** Events of the current session's transcript — the render source after a resume. */
    public java.util.List<TranscriptEvent> currentTranscript() {
        synchronized (lock) {
            if (transcript != null) transcript.flush();
            return dir == null ? java.util.List.of() : dir.readTranscript();
        }
    }

    /** Runs cleanup on the writer (serialized with commits); a no-op on a read-only store. */
    public void applyRetentionAsync(RetentionPolicy policy) {
        submit(() -> {
            if (store.isWritable()) {
                int deleted = store.applyRetention(policy, currentSessionId());
                if (deleted > 0) log.info("Session retention removed {} session(s)", deleted);
            }
        });
    }

    /** Blocks until all queued commits ran — used before switching and in shutdown paths off the UI thread. */
    public void drain() {
        try {
            writer.submit(() -> {}).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Session writer drain failed", e);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closeTranscript(); // queued behind pending commits, so it must precede shutdown
        }
        writer.shutdown();
        try {
            if (!writer.awaitTermination(30, TimeUnit.SECONDS)) log.warn("Session writer did not finish in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        store.close();
    }

    // ---- TurnRecorder wiring ----

    /** The recorder a chat service gets; {@code mode} tags this service's transcript events. */
    public TurnRecorder recorder(PeonMode mode) {
        return new ModeRecorder(mode.name());
    }

    private class ModeRecorder implements TurnRecorder {
        private final String mode;

        private ModeRecorder(String mode) {
            this.mode = mode;
        }

        @Override
        public void turnStarted(String turnId, String userText) {
            synchronized (lock) {
                // read-only store (another instance owns it): the chat runs, nothing is recorded —
                // the UI already shows the state once instead of failing on every turn
                if (dir == null && !store.isWritable()) return;
                try {
                    if (dir == null) materialize(userText);
                    activeTurns.put(turnId, new TurnContext(dir, transcript, meta, generation.get()));
                    // TURN_STARTED is fsynced by the transcript (requiresSync); USER may be buffered
                    transcript.append(TranscriptEvent.Type.TURN_STARTED, turnId, mode, null);
                    transcript.append(TranscriptEvent.Type.USER, turnId, mode, userText);
                } catch (RuntimeException e) {
                    onPersistenceError.accept(e);
                }
            }
        }

        @Override
        public void aiMessage(String turnId, String text) {
            append(turnId, TranscriptEvent.Type.AI, text, null, null);
        }

        @Override
        public void think(String turnId, String text) {
            append(turnId, TranscriptEvent.Type.THINK, text, null, null);
        }

        @Override
        public void toolIntent(String turnId, String callId, String name, String arguments) {
            append(turnId, TranscriptEvent.Type.TOOL_INTENT, arguments, callId, name); // fsynced
        }

        @Override
        public void toolResult(String turnId, String callId, String name, String result) {
            append(turnId, TranscriptEvent.Type.TOOL_RESULT, abbreviate(result), callId, name);
        }

        @Override
        public void problem(String turnId, String text) {
            append(turnId, TranscriptEvent.Type.PROBLEM, text, null, null);
        }

        @Override
        public void compacted(String turnId, String summary) {
            synchronized (lock) {
                if (dir == null) return; // compacting an unpersisted session persists nothing
                try {
                    transcript.append(TranscriptEvent.Type.COMPACTED, turnId, mode, summary);
                    commitCurrent(null);
                } catch (RuntimeException e) {
                    onPersistenceError.accept(e);
                }
            }
        }

        @Override
        public void cleared() {
            synchronized (lock) {
                if (dir == null) return; // clearing an untouched empty session stays a no-op on disk
                try {
                    transcript.append(TranscriptEvent.Type.CLEARED, null, mode, null);
                    commitCurrent(null);
                } catch (RuntimeException e) {
                    onPersistenceError.accept(e);
                }
            }
        }

        @Override
        public void turnFinished(String turnId, SessionStatus status) {
            var ctx = activeTurns.remove(turnId);
            if (ctx == null) return; // never started under a session we know — drop
            synchronized (lock) {
                if (ctx.generation() != generation.get()) {
                    // session switched mid-turn: do NOT commit a snapshot of the wrong memories.
                    // The turn's WAL records classify it as interrupted on the next load.
                    log.warn("Turn {} ended after a session switch — left uncommitted", turnId);
                    return;
                }
                try {
                    commitCurrent(statusMeta -> statusMeta.status(status));
                    final var t = transcript;
                    final String rev = Long.toString(dir.currentRevision());
                    submit(() -> t.append(TranscriptEvent.Type.TURN_COMMITTED, turnId, mode, rev));
                } catch (RuntimeException e) {
                    onPersistenceError.accept(e);
                }
            }
        }

        /** Snapshot on the calling thread (memory is complete there), file IO on the writer. */
        private void commitCurrent(Consumer<SessionMeta.SessionMetaBuilder> change) {
            var aggregate = source.snapshotAggregate();
            var builder = meta.toBuilder()
                    .updatedAt(System.currentTimeMillis())
                    .projectName(source.projectName())
                    .projectLocation(source.projectLocation())
                    .contextTokens(source.contextTokens());
            if (change != null) change.accept(builder);
            meta = builder.build();
            final var newMeta = meta;
            final var d = dir;
            final var t = transcript;
            final long lastSeq = t.lastSeq();
            submit(() -> {
                t.flush();
                d.commit(newMeta, aggregate, lastSeq);
                store.updateIndex(d);
            });
        }

        private void append(String turnId, TranscriptEvent.Type type, String text, String callId, String name) {
            var ctx = turnId == null ? null : activeTurns.get(turnId);
            if (ctx == null) return; // stale or unrecorded turn
            try {
                ctx.transcript().append(type, turnId, mode, text, callId, name);
            } catch (RuntimeException e) {
                onPersistenceError.accept(e);
            }
        }

        private void materialize(String firstUserText) {
            long now = System.currentTimeMillis();
            var initial = SessionMeta.builder()
                    .title(SessionMeta.buildTitle(firstUserText))
                    .status(SessionStatus.COMPLETED)
                    .createdAt(now).updatedAt(now)
                    .projectName(source.projectName())
                    .projectLocation(source.projectLocation())
                    .contextTokens(source.contextTokens())
                    .build();
            dir = store.createSession(initial, source.snapshotAggregate());
            transcript = dir.openTranscript(TRANSCRIPT_MAX_BYTES);
            meta = dir.load().orElseThrow().checkpoint().meta();
        }
    }

    // ---- internals ----

    private void submit(Runnable task) {
        writer.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                onPersistenceError.accept(e);
            }
        });
    }

    /** Closes on the writer, behind every queued commit that still flushes/appends to it. */
    private void closeTranscript() {
        final var old = transcript;
        transcript = null;
        if (old == null) return;
        writer.submit(() -> {
            try {
                old.close();
            } catch (RuntimeException e) {
                log.warn("Failed to close transcript", e);
            }
        });
    }

    private static String abbreviate(String text) {
        if (text == null || text.length() <= 4000) return text;
        return text.substring(0, 4000) + "… [truncated]";
    }
}
