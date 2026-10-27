package org.sterl.llmpeon.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

class SessionViewTest {

    static final Predicate<String> EDIT_TOOLS = name -> name.equals("writeFile");
    static final SessionView.Header HEADER = new SessionView.Header("ses_a3f9c2d1", "fix login", "DEV", 0, false);

    /** Builds transcript events the way the recorder writes them. */
    static class Events {
        final List<TranscriptEvent> list = new ArrayList<>();
        long seq;

        Events turn(String id, String user, String ai) {
            add(id, TranscriptEvent.Type.TURN_STARTED, null, null, null);
            add(id, TranscriptEvent.Type.USER, user, null, null);
            if (ai != null) add(id, TranscriptEvent.Type.AI, ai, null, null);
            return this;
        }

        Events tool(String id, String name, String args) {
            return add(id, TranscriptEvent.Type.TOOL_INTENT, args, "c" + seq, name);
        }

        Events add(String turnId, TranscriptEvent.Type type, String text, String callId, String name) {
            list.add(new TranscriptEvent(++seq, turnId, "DEV", 0, type, text, callId, name));
            return this;
        }
    }

    @Test
    void test_budget_isQuarterOfCompactThreshold_clamped() {
        assertThat(SessionView.budgetTokens(0)).isEqualTo(8_000);
        assertThat(SessionView.budgetTokens(4_000)).isEqualTo(2_000);
        assertThat(SessionView.budgetTokens(40_000)).isEqualTo(10_000);
        assertThat(SessionView.budgetTokens(200_000)).isEqualTo(15_000);
    }

    @Test
    void test_estimate_countsNonAsciiHeavier() {
        assertThat(SessionView.estimateTokens("abc")).isEqualTo(1);
        assertThat(SessionView.estimateTokens("가나다")).isEqualTo(5); // 3 × 1.5 → 4.5 → 5
        assertThat(SessionView.estimateTokens("가".repeat(100)))
                .isGreaterThan(SessionView.estimateTokens("a".repeat(400)));
    }

    @Test
    void test_clip_keepsHeadAndTail_withoutSplittingSurrogates() {
        var text = "😀".repeat(50); // every char is a surrogate pair
        var clipped = SessionView.clip(text, 21);
        assertThat(clipped).contains("characters omitted");
        // every code point decodes cleanly — a split pair would surface as a lone surrogate
        assertThat(clipped.codePoints().noneMatch(cp -> Character.isSurrogate((char) cp) && cp <= 0xFFFF)).isTrue();
        assertThat(SessionView.clip("short", 100)).isEqualTo("short");
    }

    @Test
    void test_smallSession_isRenderedWhole() {
        var events = new Events()
                .turn("t1", "fix the login bug", "Looking at LoginService")
                .tool("t1", "readFile", "{\"filePath\":\"LoginService.java\"}")
                .tool("t1", "writeFile", "{\"filePath\":\"LoginService.java\",\"content\":\"x\"}")
                .tool("t1", "runOsCommand", "{\"command\":\"mvn test\"}");

        var view = SessionView.render(HEADER, events.list, List.of(), EDIT_TOOLS, null, null, 8_000);

        assertThat(view)
                .contains("# ses_a3f9c2d1 — \"fix login\"")
                .contains("## Goal\nfix the login bug")
                .contains("Edited: LoginService.java")
                .contains("Read: LoginService.java")
                .contains("Commands: mvn test")
                .contains("### T1", "User: fix the login bug", "Assistant: Looking at LoginService")
                .contains("→ writeFile(LoginService.java)")
                .doesNotContain("omitted");
    }

    @Test
    void test_largeSession_fitsBudget_keepsGoalAndNewest_marksOmitted() {
        var events = new Events();
        // long user messages too: even user-only (L3) turns cannot all fit → L4 omission is needed
        for (int i = 1; i <= 60; i++) events.turn("t" + i, "request " + i + " " + "detail ".repeat(200), "answer ".repeat(1_500));
        events.turn("t61", "the latest request", "the latest answer");

        int budget = 2_000;
        var view = SessionView.render(HEADER, events.list, List.of(), EDIT_TOOLS, null, null, budget);

        assertThat(SessionView.estimateTokens(view)).isLessThanOrEqualTo(budget);
        assertThat(view)
                .contains("## Goal\nrequest 1 detail")
                .contains("### T61", "User: the latest request", "Assistant: the latest answer")
                .contains("omitted — readChatSession(\"ses_a3f9c2d1\", fromTurn=1");
    }

    @Test
    void test_levels_areRaisedBreadthFirst() {
        var turns = List.of(
                new SessionView.Turn(1, "u1", "a".repeat(5_000), List.of(), List.of()),
                new SessionView.Turn(2, "u2", "b".repeat(5_000), List.of(), List.of()),
                new SessionView.Turn(3, "u3", "c".repeat(5_000), List.of(), List.of()),
                new SessionView.Turn(4, "latest", "done", List.of(), List.of()));
        // exactly enough room for every older turn at L1 — none may be pushed to L2 first
        int budget = SessionView.estimateTokens(SessionView.renderTurn(turns.get(0), 1))
                + SessionView.estimateTokens(SessionView.renderTurn(turns.get(1), 1))
                + SessionView.estimateTokens(SessionView.renderTurn(turns.get(2), 1))
                + SessionView.estimateTokens(SessionView.renderTurn(turns.get(3), 0));

        var out = SessionView.renderTurns("ses_x", turns, budget);

        assertThat(out).isEqualTo(SessionView.renderTurn(turns.get(0), 1)
                + SessionView.renderTurn(turns.get(1), 1)
                + SessionView.renderTurn(turns.get(2), 1)
                + SessionView.renderTurn(turns.get(3), 0));
    }

    @Test
    void test_shortUserMessages_surviveAsLevelTwo_insteadOfBeingOmitted() {
        var events = new Events();
        for (int i = 1; i <= 60; i++) events.turn("t" + i, "request " + i, "answer ".repeat(1_500));
        events.turn("t61", "latest", "done");

        var view = SessionView.render(HEADER, events.list, List.of(), EDIT_TOOLS, null, null, 2_000);

        // user prompts carry the decisions (Codex/Cline) — they stay when assistant text must go
        assertThat(view).contains("User: request 1\n", "User: request 60\n").doesNotContain("omitted — readChatSession");
    }

    @Test
    void test_levelTwo_keepsUserAndActivity_dropsAssistant() {
        var turn = new SessionView.Turn(3, "use approach B, not A", "long reasoning",
                List.of(new SessionView.ToolCall("writeFile", "A.java", SessionView.Kind.EDITED)), List.of());

        var rendered = SessionView.renderTurn(turn, 2);

        assertThat(rendered)
                .contains("User: use approach B, not A")
                .contains("→ edited: A.java")
                .contains("(assistant reply omitted)")
                .doesNotContain("long reasoning");
    }

    @Test
    void test_hugeNewestTurn_isClipped_neverOmitted() {
        var events = new Events()
                .turn("t1", "old", "old answer")
                .turn("t2", "newest " + "x".repeat(50_000), "y".repeat(100_000));

        int budget = 2_000;
        var view = SessionView.render(HEADER, events.list, List.of(), EDIT_TOOLS, null, null, budget);

        assertThat(SessionView.estimateTokens(view)).isLessThanOrEqualTo(budget);
        assertThat(view).contains("### T2", "User: newest", "characters omitted");
    }

    @Test
    void test_paging_returnsOnlyTheRequestedRange() {
        var events = new Events();
        for (int i = 1; i <= 5; i++) events.turn("t" + i, "request " + i, "answer " + i);

        var view = SessionView.render(HEADER, events.list, List.of(), EDIT_TOOLS, 2, 3, 8_000);

        assertThat(view)
                .contains("turns 2–3 of 5")
                .contains("### T2", "### T3")
                .doesNotContain("### T1", "### T4", "## Goal");
        assertThat(SessionView.render(HEADER, events.list, List.of(), EDIT_TOOLS, 9, 12, 8_000))
                .contains("is empty");
    }

    @Test
    void test_interrupted_listsUnknownOutcomeTools() {
        var events = new Events()
                .turn("t1", "refactor", null)
                .tool("t1", "writeFile", "{\"filePath\":\"Big.java\"}");
        var header = new SessionView.Header("ses_a3f9c2d1", "refactor", "DEV", 0, true);
        var unresolved = List.of(events.list.getLast());

        var view = SessionView.render(header, events.list, unresolved, EDIT_TOOLS, null, null, 8_000);

        assertThat(view)
                .contains("⚠ interrupted")
                .contains("## ⚠ Interrupted")
                .contains("- → writeFile(Big.java)")
                .contains("verify on disk");
    }

    @Test
    void test_compaction_isSummarizedInHeader_andMarkedInConversation() {
        var events = new Events()
                .turn("t1", "start", "ok")
                .add(null, TranscriptEvent.Type.COMPACTED, "SUMMARY OF EARLIER WORK", null, null)
                .turn("t2", "continue", "done");

        var view = SessionView.render(HEADER, events.list, List.of(), EDIT_TOOLS, null, null, 8_000);

        assertThat(view)
                .contains("## Earlier summary\nSUMMARY OF EARLIER WORK")
                .contains("(context compacted here");
        assertThat(view.indexOf("(context compacted here")).isGreaterThan(view.indexOf("### T2"));
    }
}
