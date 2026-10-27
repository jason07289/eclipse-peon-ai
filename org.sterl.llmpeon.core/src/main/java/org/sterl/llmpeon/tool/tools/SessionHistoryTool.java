package org.sterl.llmpeon.tool.tools;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.sterl.llmpeon.session.SessionIds;
import org.sterl.llmpeon.session.SessionManager;
import org.sterl.llmpeon.session.SessionStatus;
import org.sterl.llmpeon.session.SessionStore;
import org.sterl.llmpeon.session.SessionView;
import org.sterl.llmpeon.session.Transcript;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Continue by session ID (chat-session design §9.2): the user types e.g. "ses_a3f9c2d1 이어서
 * 작업해줘" into a fresh chat and the model loads that saved conversation through this tool. Read
 * only — the source session is never written; the view lands in the NEW session's memory as an
 * ordinary tool result. Stateless apart from its collaborators, so concurrent turns are safe.
 */
public class SessionHistoryTool extends AbstractTool {

    public static final String NAME = "readChatSession";
    static final int RECENT_LIMIT = 10;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final SessionManager sessions;
    /** Classifies a recorded tool call as a file edit — resolved by tool name, MCP tools count as not editing. */
    private final Predicate<String> isEditTool;

    public SessionHistoryTool(SessionManager sessions, Predicate<String> isEditTool) {
        this.sessions = sessions;
        this.isEditTool = isEditTool;
    }

    @Tool(name = NAME, value = """
            Read an earlier saved chat session of this workspace by its session ID.
            Any token "ses_" + hex characters (e.g. ses_a3f9c2d1) in the user's message is a chat session ID,
            never a file, class or project name. When the user mentions one, call this tool FIRST,
            before any file or project tool, then continue the work described in that session.
            Returns a read-only, size-limited view: goal, files edited/read, interruption state and the turns.
            Omitted turn ranges are marked and can be loaded with fromTurn/toTurn.
            """)
    public String readChatSession(
            @P(description = "session ID as shown in History, e.g. ses_a3f9c2d1", name = "sessionId") String sessionId,
            @P(description = "first turn to load (1-based), only to fetch an omitted range", required = false, name = "fromTurn")
            Integer fromTurn,
            @P(description = "last turn to load (1-based, inclusive)", required = false, name = "toTurn")
            Integer toTurn) {

        var entries = sessions.listSessions();
        var query = SessionIds.normalizeQuery(sessionId);
        if (query.isEmpty()) {
            return "'" + sessionId + "' is not a session ID — IDs look like ses_a3f9c2d1.\n" + recent(entries);
        }
        var matches = entries.stream().filter(e -> SessionIds.matches(e.id(), query.get())).toList();
        if (matches.isEmpty()) {
            return "No saved session matches '" + sessionId + "'.\n" + recent(entries);
        }
        if (matches.size() > 1) {
            return "Several sessions match '" + sessionId + "' — ask the user which one is meant:\n" + list(matches);
        }

        var entry = matches.getFirst();
        var displayId = SessionIds.displayId(entry.id());
        if (entry.id().equals(sessions.currentSessionId())) {
            return displayId + " is the current conversation — its messages are already in this context.";
        }
        try {
            var events = sessions.store().readTranscript(entry.id());
            if (events.isEmpty()) return displayId + " has no readable history (it may have been deleted).";

            // analyze from 0: committed turns pair TURN_STARTED/TURN_COMMITTED, no checkpoint needed
            var recovery = Transcript.analyze(events, 0);
            var header = new SessionView.Header(displayId, entry.title(), entry.mode(), entry.updatedAt(),
                    recovery.interrupted() || entry.status() == SessionStatus.INTERRUPTED);
            onTool("Reading chat session " + displayId);
            return SessionView.render(header, events, recovery.unresolvedToolIntents(), isEditTool,
                    fromTurn, toTurn, SessionView.budgetTokens(autoCompactAfter()));
        } catch (UncheckedIOException e) {
            return displayId + " could not be read: " + e.getMessage();
        }
    }

    private int autoCompactAfter() {
        return request == null || request.getModel() == null ? 0 : request.getModel().getAutoCompactAfter();
    }

    private String recent(List<SessionStore.IndexEntry> entries) {
        var current = sessions.currentSessionId();
        var others = entries.stream().filter(e -> !e.id().equals(current)).limit(RECENT_LIMIT).toList();
        return others.isEmpty() ? "There are no other saved sessions." : "Recent sessions:\n" + list(others);
    }

    private static String list(List<SessionStore.IndexEntry> entries) {
        return entries.stream()
                .map(e -> "- " + SessionIds.displayId(e.id()) + " — \"" + e.title() + "\" ("
                        + TIME.format(Instant.ofEpochMilli(e.updatedAt()).atZone(ZoneId.systemDefault())) + ")")
                .collect(Collectors.joining("\n"));
    }
}
