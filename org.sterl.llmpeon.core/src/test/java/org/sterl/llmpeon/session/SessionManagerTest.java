package org.sterl.llmpeon.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sterl.llmpeon.PeonMode;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

class SessionManagerTest {

    @TempDir
    Path base;
    @TempDir
    Path workspace;

    /** Mutable fake of the chat layer — what a checkpoint snapshot would capture. */
    static class FakeSource implements SessionSource {
        List<ChatMessage> dev = List.of();
        List<ChatMessage> plan = List.of();
        PeonMode mode = PeonMode.DEV;
        String project = "demo";

        @Override public SessionAggregate snapshotAggregate() {
            return SessionAggregate.of(mode, dev, plan);
        }
        @Override public String projectName() { return project; }
        @Override public String projectLocation() { return "/p/" + project; }
        @Override public int contextTokens() { return 7; }
    }

    FakeSource source = new FakeSource();
    SessionManager manager;

    @BeforeEach
    void setUp() {
        manager = new SessionManager(base, workspace, source);
        manager.startFresh();
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    private Path sessionsRoot() {
        return manager.store().root();
    }

    @Test
    void test_startFresh_persistsNothing() throws Exception {
        manager.startFresh();
        manager.startFresh();

        assertThat(manager.currentSessionId()).isNull();
        assertThat(manager.listSessions()).isEmpty();
        try (var files = Files.list(sessionsRoot())) {
            // only store.json, .lock — no session directories, no index rows
            assertThat(files.filter(Files::isDirectory).toList()).isEmpty();
        }
    }

    @Test
    void test_firstMessage_materializesSession_withTitle() {
        var recorder = manager.recorder(PeonMode.DEV);

        recorder.turnStarted("t1", "please fix the build");

        assertThat(manager.currentSessionId()).isNotNull();
        manager.drain();
        var listed = manager.listSessions();
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).title()).isEqualTo("please fix the build");
        assertThat(listed.get(0).projectName()).isEqualTo("demo");
        // WAL is already durable before the model call
        var events = manager.currentTranscript();
        assertThat(events).extracting(TranscriptEvent::type)
                .containsExactly(TranscriptEvent.Type.TURN_STARTED, TranscriptEvent.Type.USER);
    }

    @Test
    void test_completedTurn_commitsAggregateOfBothModes() {
        var recorder = manager.recorder(PeonMode.DEV);
        recorder.turnStarted("t1", "hello");
        source.dev = List.of(UserMessage.from("hello"), AiMessage.from("world"));
        source.plan = List.of(UserMessage.from("the plan"));

        recorder.aiMessage("t1", "world");
        recorder.turnFinished("t1", SessionStatus.COMPLETED);
        manager.drain();

        var loaded = manager.store().openSession(manager.currentSessionId())
                .orElseThrow().load().orElseThrow();
        assertThat(loaded.checkpoint().revision()).isEqualTo(2); // 1 = materialize, 2 = turn commit
        assertThat(loaded.effectiveStatus()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(loaded.checkpoint().aggregate().devMessages()).hasSize(2);
        assertThat(loaded.checkpoint().aggregate().planMessages()).hasSize(1);
        assertThat(loaded.checkpoint().meta().contextTokens()).isEqualTo(7);
        // TURN_COMMITTED recorded → not interrupted
        assertThat(loaded.recovery().interrupted()).isFalse();
    }

    @Test
    void test_turnWithoutCommit_isInterrupted_onReload() {
        var recorder = manager.recorder(PeonMode.DEV);
        recorder.turnStarted("t1", "hello");
        recorder.toolIntent("t1", "c1", "write_file", "{}");
        // hard kill: no turnFinished
        var id = manager.currentSessionId();
        manager.drain();

        var loaded = manager.store().openSession(id).orElseThrow().load().orElseThrow();

        assertThat(loaded.effectiveStatus()).isEqualTo(SessionStatus.INTERRUPTED);
        assertThat(loaded.recovery().unresolvedToolIntents()).hasSize(1);
    }

    @Test
    void test_turnEndingAfterSwitch_isNotCommitted() {
        var recorder = manager.recorder(PeonMode.DEV);
        recorder.turnStarted("t1", "hello");
        var firstId = manager.currentSessionId();

        manager.startFresh(); // protocol violation: switch while t1 runs
        source.dev = List.of(UserMessage.from("WRONG session state"));
        recorder.turnFinished("t1", SessionStatus.COMPLETED);
        manager.drain();

        var loaded = manager.store().openSession(firstId).orElseThrow().load().orElseThrow();
        // revision 1 only (materialize) — the stale snapshot was refused, WAL says interrupted
        assertThat(loaded.checkpoint().revision()).isEqualTo(1);
        assertThat(loaded.effectiveStatus()).isEqualTo(SessionStatus.INTERRUPTED);
    }

    @Test
    void test_eventsOfUnknownTurn_areDropped() {
        var recorder = manager.recorder(PeonMode.DEV);

        recorder.aiMessage("ghost", "text");
        recorder.turnFinished("ghost", SessionStatus.COMPLETED);
        manager.drain();

        assertThat(manager.currentSessionId()).isNull();
        assertThat(manager.listSessions()).isEmpty();
    }

    @Test
    void test_clear_keepsSessionAndTranscript() {
        var recorder = manager.recorder(PeonMode.DEV);
        recorder.turnStarted("t1", "hello");
        recorder.turnFinished("t1", SessionStatus.COMPLETED);

        recorder.cleared();
        manager.drain();

        var loaded = manager.store().openSession(manager.currentSessionId())
                .orElseThrow().load().orElseThrow();
        assertThat(loaded.checkpoint().revision()).isEqualTo(3);
        assertThat(manager.currentTranscript())
                .extracting(TranscriptEvent::type)
                .contains(TranscriptEvent.Type.USER, TranscriptEvent.Type.CLEARED);
        assertThat(manager.listSessions()).hasSize(1); // history row survives
    }

    @Test
    void test_clearOnUnpersistedSession_staysNoOp() {
        manager.recorder(PeonMode.DEV).cleared();
        manager.drain();

        assertThat(manager.listSessions()).isEmpty();
    }

    @Test
    void test_compacted_commitsRevision_keepsOriginalsInTranscript() {
        var recorder = manager.recorder(PeonMode.DEV);
        recorder.turnStarted("t1", "long conversation");
        recorder.turnFinished("t1", SessionStatus.COMPLETED);

        source.dev = List.of(AiMessage.from("[Context summary]\nshort"));
        recorder.compacted(null, "short");
        manager.drain();

        var events = manager.currentTranscript();
        assertThat(events).extracting(TranscriptEvent::type).contains(TranscriptEvent.Type.COMPACTED);
        // the original USER event is still in the transcript — compaction never rewrites history
        assertThat(events).extracting(TranscriptEvent::type).contains(TranscriptEvent.Type.USER);
        var loaded = manager.store().openSession(manager.currentSessionId())
                .orElseThrow().load().orElseThrow();
        assertThat(loaded.checkpoint().aggregate().devMessages()).hasSize(1);
    }

    @Test
    void test_resume_returnsAggregate_andContinuesSameSession() {
        var recorder = manager.recorder(PeonMode.DEV);
        recorder.turnStarted("t1", "hello");
        source.dev = List.of(UserMessage.from("hello"), AiMessage.from("world"));
        recorder.turnFinished("t1", SessionStatus.COMPLETED);
        var id = manager.currentSessionId();

        manager.startFresh();
        assertThat(manager.currentSessionId()).isNull();

        var resumed = manager.resume(id).orElseThrow();
        assertThat(resumed.checkpoint().aggregate().devMessages()).hasSize(2);
        assertThat(manager.currentSessionId()).isEqualTo(id);

        // continuing the resumed session commits to the same directory
        recorder.turnStarted("t2", "continue");
        recorder.turnFinished("t2", SessionStatus.COMPLETED);
        manager.drain();
        assertThat(manager.listSessions()).hasSize(1);
        assertThat(manager.store().openSession(id).orElseThrow().currentRevision()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void test_deleteActiveSession_switchesToFreshFirst() {
        var recorder = manager.recorder(PeonMode.DEV);
        recorder.turnStarted("t1", "hello");
        recorder.turnFinished("t1", SessionStatus.COMPLETED);
        var id = manager.currentSessionId();

        var deleted = manager.deleteSession(id);
        // the switch is synchronous; the file removal follows on the writer
        assertThat(manager.currentSessionId()).isNull();

        deleted.join();
        assertThat(manager.listSessions()).isEmpty();
        assertThat(Files.exists(sessionsRoot().resolve(id))).isFalse();
    }

    @Test
    void test_deleteOtherSession_keepsCurrentSession() {
        var recorder = manager.recorder(PeonMode.DEV);
        recorder.turnStarted("t1", "first");
        recorder.turnFinished("t1", SessionStatus.COMPLETED);
        var first = manager.currentSessionId();
        manager.startFresh();
        recorder.turnStarted("t2", "second");
        recorder.turnFinished("t2", SessionStatus.COMPLETED);
        var second = manager.currentSessionId();

        manager.deleteSession(first).join();

        assertThat(manager.currentSessionId()).isEqualTo(second);
        assertThat(manager.listSessions()).extracting(SessionStore.IndexEntry::id).containsExactly(second);
    }

    @Test
    void test_generation_advancesOnSwitch() {
        var before = manager.generation();
        manager.startFresh();
        assertThat(manager.generation()).isGreaterThan(before);
        assertThat(manager.isCurrent(before)).isFalse();
        assertThat(manager.isCurrent(manager.generation())).isTrue();
    }
}
