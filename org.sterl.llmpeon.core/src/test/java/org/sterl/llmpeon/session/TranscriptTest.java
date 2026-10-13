package org.sterl.llmpeon.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sterl.llmpeon.session.TranscriptEvent.Type;

class TranscriptTest {

    @TempDir
    Path tempDir;

    private Path file() {
        return tempDir.resolve("transcript.jsonl");
    }

    @Test
    void test_appendAndRead_roundTrip() {
        try (var t = Transcript.open(file())) {
            t.append(Type.TURN_STARTED, "t1", "DEV", null);
            t.append(Type.USER, "t1", "DEV", "hello");
            t.append(Type.AI, "t1", "DEV", "world");
            t.append(Type.TURN_COMMITTED, "t1", "DEV", "1");
        }

        var events = Transcript.readAll(file());

        assertThat(events).hasSize(4);
        assertThat(events.get(0).seq()).isEqualTo(1);
        assertThat(events.get(3).seq()).isEqualTo(4);
        assertThat(events.get(1).text()).isEqualTo("hello");
        assertThat(events.get(0).type()).isEqualTo(Type.TURN_STARTED);
    }

    @Test
    void test_seq_continuesAfterReopen() {
        try (var t = Transcript.open(file())) {
            t.append(Type.USER, "t1", "DEV", "first");
        }
        try (var t = Transcript.open(file())) {
            var event = t.append(Type.AI, "t1", "DEV", "second");
            assertThat(event.seq()).isEqualTo(2);
        }
    }

    @Test
    void test_tornLastLine_isDropped() throws IOException {
        try (var t = Transcript.open(file())) {
            t.append(Type.USER, "t1", "DEV", "ok");
        }
        // simulate a crash mid-append: half a JSON object, no trailing newline
        Files.writeString(file(), "{\"seq\":2,\"type\":\"AI\",\"tex", StandardOpenOption.APPEND);

        var events = Transcript.readAll(file());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).text()).isEqualTo("ok");
        // and appending afterwards still works
        try (var t = Transcript.open(file())) {
            t.append(Type.AI, "t1", "DEV", "recovered");
        }
    }

    @Test
    void test_damagedMiddleLine_isSkipped() throws IOException {
        Files.writeString(file(), """
                {"seq":1,"turnId":"t1","ts":1,"type":"USER","text":"a"}
                not json at all
                {"seq":3,"turnId":"t1","ts":3,"type":"AI","text":"b"}
                """);

        var events = Transcript.readAll(file());

        assertThat(events).extracting(TranscriptEvent::text).containsExactly("a", "b");
    }

    @Test
    void test_unknownEventType_isSkipped() throws IOException {
        Files.writeString(file(), """
                {"seq":1,"turnId":"t1","ts":1,"type":"USER","text":"a"}
                {"seq":2,"turnId":"t1","ts":2,"type":"FUTURE_FEATURE","text":"?"}
                """);

        assertThat(Transcript.readAll(file())).hasSize(1);
    }

    @Test
    void test_analyze_committedTurn_isNotInterrupted() {
        try (var t = Transcript.open(file())) {
            t.append(Type.TURN_STARTED, "t1", "DEV", null);
            t.append(Type.TOOL_INTENT, "t1", "DEV", null, "call1", "write_file");
            t.append(Type.TOOL_RESULT, "t1", "DEV", "done", "call1", "write_file");
            t.append(Type.TURN_COMMITTED, "t1", "DEV", "1");
        }

        var state = Transcript.analyze(Transcript.readAll(file()), 0);

        assertThat(state.interrupted()).isFalse();
        assertThat(state.unresolvedToolIntents()).isEmpty();
    }

    @Test
    void test_analyze_uncommittedTurn_isInterrupted_withUnresolvedTool() {
        try (var t = Transcript.open(file())) {
            t.append(Type.TURN_COMMITTED, "t0", "DEV", "1"); // old, covered by checkpoint
            t.append(Type.TURN_STARTED, "t1", "DEV", null);
            t.append(Type.TOOL_INTENT, "t1", "DEV", null, "call1", "write_file");
            t.append(Type.TOOL_INTENT, "t1", "DEV", null, "call2", "read_file");
            t.append(Type.TOOL_RESULT, "t1", "DEV", "ok", "call2", "read_file");
            // hard kill here: no TOOL_RESULT for call1, no TURN_COMMITTED
        }

        var state = Transcript.analyze(Transcript.readAll(file()), 1);

        assertThat(state.interrupted()).isTrue();
        assertThat(state.pendingTurnIds()).containsExactly("t1");
        assertThat(state.unresolvedToolIntents()).hasSize(1);
        assertThat(state.unresolvedToolIntents().get(0).toolCallId()).isEqualTo("call1");
        assertThat(state.unresolvedToolIntents().get(0).toolName()).isEqualTo("write_file");
    }

    @Test
    void test_analyze_eventsBeforeCheckpoint_areIgnored() {
        List<TranscriptEvent> events;
        try (var t = Transcript.open(file())) {
            t.append(Type.TURN_STARTED, "t1", "DEV", null); // seq 1
            t.append(Type.USER, "t1", "DEV", "hi");          // seq 2
            events = Transcript.readAll(file());
        }

        // checkpoint already covers seq 2 → nothing pending
        assertThat(Transcript.analyze(events, 2).interrupted()).isFalse();
        // checkpoint covers nothing → turn t1 pending
        assertThat(Transcript.analyze(events, 0).interrupted()).isTrue();
    }
}
