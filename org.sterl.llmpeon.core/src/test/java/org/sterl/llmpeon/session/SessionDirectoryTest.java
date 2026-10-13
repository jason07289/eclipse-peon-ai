package org.sterl.llmpeon.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sterl.llmpeon.PeonMode;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

class SessionDirectoryTest {

    @TempDir
    Path storeRoot;

    private SessionMeta meta(String id) {
        long now = System.currentTimeMillis();
        return SessionMeta.builder()
                .id(id).title("test session").status(SessionStatus.COMPLETED)
                .createdAt(now).updatedAt(now)
                .workspacePath("/tmp/ws").projectName("demo").contextTokens(42)
                .build();
    }

    /** Realistic message mix: plain, tool call + result, multi-content — the §8 fixtures. */
    private List<ChatMessage> devMessages() {
        var toolRequest = ToolExecutionRequest.builder()
                .id("call1").name("write_file").arguments("{\"path\":\"a.txt\"}").build();
        return List.of(
                UserMessage.from("write a file"),
                AiMessage.from(toolRequest),
                ToolExecutionResultMessage.from(toolRequest, "written"),
                AiMessage.from("done"),
                UserMessage.from(TextContent.from("context info"), TextContent.from("and the question")));
    }

    private SessionDirectory create(String id) {
        var aggregate = SessionAggregate.of(PeonMode.DEV, devMessages(), List.of(UserMessage.from("plan it")));
        return SessionDirectory.create(storeRoot, storeRoot.resolve(id), meta(id), aggregate);
    }

    @Test
    void test_createAndLoad_roundTrip_includingMessages() {
        create("s1");

        var loaded = SessionDirectory.open(storeRoot, storeRoot.resolve("s1")).orElseThrow().load().orElseThrow();

        assertThat(loaded.compatible()).isTrue();
        assertThat(loaded.checkpoint().revision()).isEqualTo(1);
        assertThat(loaded.checkpoint().meta().title()).isEqualTo("test session");
        assertThat(loaded.checkpoint().aggregate().activeMode()).isEqualTo(PeonMode.DEV);
        assertThat(loaded.effectiveStatus()).isEqualTo(SessionStatus.COMPLETED);

        var dev = loaded.checkpoint().aggregate().devMessages();
        assertThat(dev).hasSize(5);
        assertThat(((AiMessage) dev.get(1)).toolExecutionRequests().get(0).name()).isEqualTo("write_file");
        assertThat(((ToolExecutionResultMessage) dev.get(2)).text()).isEqualTo("written");
        assertThat(((UserMessage) dev.get(4)).contents()).hasSize(2);
        assertThat(loaded.checkpoint().aggregate().planMessages()).hasSize(1);
    }

    @Test
    void test_commit_advancesRevision_andPrunesToTwo() throws IOException {
        var session = create("s1");
        for (int i = 0; i < 4; i++) {
            session.commit(meta("s1"), session.load().orElseThrow().checkpoint().aggregate(), i);
        }

        assertThat(session.currentRevision()).isEqualTo(5);
        try (var files = Files.list(storeRoot.resolve("s1").resolve(SessionDirectory.CHECKPOINTS))) {
            assertThat(files.toList()).hasSize(2); // keep newest two
        }
        assertThat(session.load().orElseThrow().checkpoint().revision()).isEqualTo(5);
    }

    @Test
    void test_crashBeforeCurrentSwitch_loadsPreviousRevision() throws IOException {
        var session = create("s1");
        session.commit(meta("s1"), session.load().orElseThrow().checkpoint().aggregate(), 7); // rev 2

        // simulate: step 1 wrote ckpt-3 but the process died before CURRENT was switched
        var orphan = session.checkpointPath(3);
        Files.copy(session.checkpointPath(2), orphan);

        var reopened = SessionDirectory.open(storeRoot, storeRoot.resolve("s1")).orElseThrow().load().orElseThrow();

        // CURRENT still points at 2 — but the orphan at the head of the fallback list must not win either:
        // its checksum matches a revision-2 payload, so whichever loads, the content is revision 2's
        assertThat(reopened.checkpoint().lastTranscriptSeq()).isEqualTo(7);
    }

    @Test
    void test_corruptCurrentCheckpoint_fallsBackAndQuarantines() throws IOException {
        var session = create("s1");
        var aggregate = session.load().orElseThrow().checkpoint().aggregate();
        session.commit(meta("s1"), aggregate, 7); // rev 2

        Files.writeString(session.checkpointPath(2), "{\"checksum\":\"bad\",\"payload\":garbage");

        var reopened = SessionDirectory.open(storeRoot, storeRoot.resolve("s1")).orElseThrow().load().orElseThrow();

        assertThat(reopened.checkpoint().revision()).isEqualTo(1); // previous good revision
        try (var files = Files.list(storeRoot.resolve("corrupt"))) {
            assertThat(files.toList()).hasSize(1); // damaged file preserved, not deleted
        }
    }

    @Test
    void test_checksumMismatch_fallsBack() throws IOException {
        var session = create("s1");
        var aggregate = session.load().orElseThrow().checkpoint().aggregate();
        session.commit(meta("s1"), aggregate, 7); // rev 2

        // valid JSON, valid structure — but tampered payload
        var content = Files.readString(session.checkpointPath(2));
        Files.writeString(session.checkpointPath(2), content.replace("test session", "tampered!!!"));

        var reopened = SessionDirectory.open(storeRoot, storeRoot.resolve("s1")).orElseThrow().load().orElseThrow();
        assertThat(reopened.checkpoint().revision()).isEqualTo(1);
    }

    @Test
    void test_allCheckpointsCorrupt_loadsNothing_filesPreserved() throws IOException {
        var session = create("s1");
        Files.writeString(session.checkpointPath(1), "garbage");

        assertThat(SessionDirectory.open(storeRoot, storeRoot.resolve("s1"))).isEmpty();
        try (var files = Files.list(storeRoot.resolve("corrupt"))) {
            assertThat(files.toList()).isNotEmpty();
        }
    }

    @Test
    void test_futureSchemaVersion_listedAsIncompatible_notQuarantined() throws IOException {
        var session = create("s1");
        var content = Files.readString(session.checkpointPath(1));
        Files.writeString(session.checkpointPath(1), content.replace("\"schemaVersion\" : 1", "\"schemaVersion\" : 99"));

        var loaded = SessionDirectory.open(storeRoot, storeRoot.resolve("s1")).orElseThrow().load().orElseThrow();

        assertThat(loaded.compatible()).isFalse();
        assertThat(Files.exists(storeRoot.resolve("corrupt"))).isFalse();
    }

    @Test
    void test_commitConflict_fromAnotherProcess_writesConflictCopy() throws IOException {
        var session = create("s1");
        var aggregate = session.load().orElseThrow().checkpoint().aggregate();

        // another process committed revision 5 behind our back
        Files.writeString(storeRoot.resolve("s1").resolve(SessionDirectory.CURRENT), "5");

        assertThatThrownBy(() -> session.commit(meta("s1"), aggregate, 9))
                .isInstanceOf(SessionDirectory.CommitConflictException.class);
        try (var files = Files.list(storeRoot.resolve("s1"))) {
            assertThat(files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("conflict-"))).hasSize(1);
        }
    }

    @Test
    void test_interruptedTurn_overridesStoredStatus() {
        var session = create("s1");
        try (var t = session.openTranscript()) {
            t.append(TranscriptEvent.Type.TURN_STARTED, "t9", "DEV", null);
            t.append(TranscriptEvent.Type.TOOL_INTENT, "t9", "DEV", null, "c1", "write_file");
            // hard kill: no TURN_COMMITTED, no new checkpoint
        }

        var loaded = session.load().orElseThrow();

        assertThat(loaded.checkpoint().meta().status()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(loaded.effectiveStatus()).isEqualTo(SessionStatus.INTERRUPTED);
        assertThat(loaded.recovery().unresolvedToolIntents()).hasSize(1);
    }
}
