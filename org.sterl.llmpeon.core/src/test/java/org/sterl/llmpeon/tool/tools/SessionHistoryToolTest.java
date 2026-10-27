package org.sterl.llmpeon.tool.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sterl.llmpeon.PeonMode;
import org.sterl.llmpeon.session.SessionAggregate;
import org.sterl.llmpeon.session.SessionIds;
import org.sterl.llmpeon.session.SessionManager;
import org.sterl.llmpeon.session.SessionMeta;
import org.sterl.llmpeon.session.SessionSource;
import org.sterl.llmpeon.session.SessionStatus;

class SessionHistoryToolTest {

    @TempDir
    Path base;
    @TempDir
    Path workspace;

    SessionManager manager;
    SessionHistoryTool tool;

    @BeforeEach
    void setUp() {
        manager = new SessionManager(base, workspace, new SessionSource() {
            @Override public SessionAggregate snapshotAggregate() { return SessionAggregate.of(PeonMode.DEV, List.of(), List.of()); }
            @Override public String projectName() { return "demo"; }
            @Override public String projectLocation() { return "/p/demo"; }
            @Override public int contextTokens() { return 0; }
        });
        manager.startFresh();
        tool = new SessionHistoryTool(manager, name -> name.equals("writeFile"));
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    /** Records one completed turn into a new session and leaves it (so it is not the current one). */
    private String savedSession(String userText) {
        var recorder = manager.recorder(PeonMode.DEV);
        recorder.turnStarted("t-" + userText, userText);
        recorder.aiMessage("t-" + userText, "answer to " + userText);
        recorder.toolIntent("t-" + userText, "c1", "writeFile", "{\"filePath\":\"Login.java\"}");
        recorder.toolResult("t-" + userText, "c1", "writeFile", "ok");
        recorder.turnFinished("t-" + userText, SessionStatus.COMPLETED);
        var id = manager.currentSessionId();
        manager.startFresh();
        manager.drain();
        return id;
    }

    @Test
    void test_resolvesDisplayId_upperCase_andShortPrefix() {
        var id = savedSession("fix login");
        var displayId = SessionIds.displayId(id);

        for (var query : List.of(displayId, displayId.toUpperCase(), displayId.substring(4, 10))) {
            assertThat(tool.readChatSession(query, null, null))
                    .as(query)
                    .contains("# " + displayId)
                    .contains("## Goal\nfix login")
                    .contains("Edited: Login.java")
                    .contains("Assistant: answer to fix login");
        }
    }

    @Test
    void test_currentSession_returnsNotice() {
        var recorder = manager.recorder(PeonMode.DEV);
        recorder.turnStarted("t1", "current work");
        recorder.turnFinished("t1", SessionStatus.COMPLETED);
        manager.drain();

        var result = tool.readChatSession(SessionIds.displayId(manager.currentSessionId()), null, null);

        assertThat(result).contains("is the current conversation");
    }

    @Test
    void test_unknownOrInvalidId_listsRecentSessions() {
        var id = savedSession("older work");

        assertThat(tool.readChatSession("ses_ffffffff", null, null))
                .contains("No saved session matches")
                .contains(SessionIds.displayId(id));
        assertThat(tool.readChatSession("abc", null, null))
                .contains("is not a session ID")
                .contains(SessionIds.displayId(id));
    }

    @Test
    void test_ambiguousPrefix_returnsCandidates() {
        var aggregate = SessionAggregate.of(PeonMode.DEV, List.of(), List.of());
        long now = System.currentTimeMillis();
        for (var id : List.of("abcdef11-0000-0000-0000-000000000000", "abcdef22-0000-0000-0000-000000000000")) {
            manager.store().createSession(SessionMeta.builder().id(id).title("t " + id)
                    .status(SessionStatus.COMPLETED).createdAt(now).updatedAt(now).build(), aggregate);
        }

        var result = tool.readChatSession("ses_abcdef", null, null);

        assertThat(result).contains("Several sessions match").contains("ses_abcdef11").contains("ses_abcdef22");
    }

    @Test
    void test_interruptedSession_isFlaggedWithUnknownTool() {
        var recorder = manager.recorder(PeonMode.DEV);
        recorder.turnStarted("t1", "big refactor");
        recorder.toolIntent("t1", "c1", "writeFile", "{\"filePath\":\"Big.java\"}");
        var id = manager.currentSessionId();
        manager.startFresh(); // hard-kill equivalent: the turn never finishes
        manager.drain();

        var result = tool.readChatSession(SessionIds.displayId(id), null, null);

        assertThat(result).contains("⚠ interrupted").contains("- → writeFile(Big.java)");
    }
}
