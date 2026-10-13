package org.sterl.llmpeon.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sterl.llmpeon.PeonMode;

import dev.langchain4j.data.message.UserMessage;

class SessionRetentionTest {

    private static final long DAY = 24L * 60 * 60 * 1000;

    @TempDir
    Path base;
    @TempDir
    Path workspace;

    SessionStore store;

    @BeforeEach
    void setUp() {
        store = SessionStore.open(base, workspace);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private SessionDirectory create(String title, long ageDays, SessionStatus status) {
        long ts = System.currentTimeMillis() - ageDays * DAY;
        var meta = SessionMeta.builder()
                .title(title).status(status).createdAt(ts).updatedAt(ts).build();
        var dir = store.createSession(meta,
                SessionAggregate.of(PeonMode.DEV, List.of(UserMessage.from(title)), List.of()));
        // createSession committed "now" — rewrite with the aged meta so updatedAt is in the past
        var loaded = dir.load().orElseThrow();
        dir.commit(loaded.checkpoint().meta().toBuilder().updatedAt(ts).status(status).build(),
                loaded.checkpoint().aggregate(), 0);
        store.updateIndex(dir);
        return dir;
    }

    private List<String> listedTitles() {
        return store.listSessions().stream().map(SessionStore.IndexEntry::title).toList();
    }

    @Test
    void test_maxAge_expiresOldSessions_evenInterruptedOnes() {
        create("fresh", 2, SessionStatus.COMPLETED);
        create("ancient", 40, SessionStatus.COMPLETED);
        create("ancient-interrupted", 40, SessionStatus.INTERRUPTED);

        int deleted = store.applyRetention(new RetentionPolicy(30, 50, 1, Long.MAX_VALUE), null);

        assertThat(deleted).isEqualTo(2);
        assertThat(listedTitles()).containsExactly("fresh");
    }

    @Test
    void test_maxCount_evictsOldest_butProtectsInterrupted() {
        create("newest", 2, SessionStatus.COMPLETED);
        create("middle", 5, SessionStatus.COMPLETED);
        create("old-interrupted", 9, SessionStatus.INTERRUPTED);
        create("oldest", 10, SessionStatus.COMPLETED);

        store.applyRetention(new RetentionPolicy(365, 2, 1, Long.MAX_VALUE), null);

        // oldest completed went first; the interrupted one survives count eviction
        var titles = listedTitles();
        assertThat(titles).contains("newest", "old-interrupted");
        assertThat(titles).doesNotContain("oldest");
    }

    @Test
    void test_minRetention_protectsYoungSessions() {
        create("young-but-expired", 0, SessionStatus.COMPLETED); // updatedAt = now

        int deleted = store.applyRetention(new RetentionPolicy(0, 50, 1, Long.MAX_VALUE), null);

        assertThat(deleted).isZero();
        assertThat(listedTitles()).containsExactly("young-but-expired");
    }

    @Test
    void test_protectedSession_isNeverDeleted() {
        var current = create("current-but-ancient", 100, SessionStatus.COMPLETED);
        create("other-ancient", 100, SessionStatus.COMPLETED);

        store.applyRetention(new RetentionPolicy(30, 50, 1, Long.MAX_VALUE), current.sessionId());

        assertThat(listedTitles()).containsExactly("current-but-ancient");
    }

    @Test
    void test_sizeCap_evictsOldestUntilUnderLimit() {
        create("newest", 2, SessionStatus.COMPLETED);
        create("oldest", 10, SessionStatus.COMPLETED);
        long total = SessionStore.directorySize(store.root());

        // cap below current total → the oldest must go
        store.applyRetention(new RetentionPolicy(365, 50, 1, total - 1), null);

        assertThat(listedTitles()).containsExactly("newest");
    }

    @Test
    void test_transcriptCap_dropsDetail_keepsWalRecords() {
        var dir = create("capped", 2, SessionStatus.COMPLETED);
        try (var t = dir.openTranscript(600)) { // tiny cap for the test
            t.append(TranscriptEvent.Type.TURN_STARTED, "t1", "DEV", null);
            for (int i = 0; i < 30; i++) t.append(TranscriptEvent.Type.AI, "t1", "DEV", "x".repeat(100));
            t.append(TranscriptEvent.Type.TOOL_INTENT, "t1", "DEV", null, "c1", "write_file");
            t.append(TranscriptEvent.Type.TURN_COMMITTED, "t1", "DEV", "2");
        }

        var events = dir.readTranscript();
        var types = events.stream().map(TranscriptEvent::type).distinct().toList();

        // WAL + commit records survive; the flood of AI detail was cut off with one notice
        assertThat(types).contains(TranscriptEvent.Type.TURN_STARTED,
                TranscriptEvent.Type.TOOL_INTENT, TranscriptEvent.Type.TURN_COMMITTED,
                TranscriptEvent.Type.PROBLEM);
        assertThat(events.stream().filter(e -> e.type() == TranscriptEvent.Type.AI).count())
                .isLessThan(30);
    }
}
