package org.sterl.llmpeon.session;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Builds the size-limited, read-only view of a saved session that {@code readChatSession} hands
 * to the model (chat-session design §9.2). Pure functions over transcript events — no IO, no
 * state, so it is thread-safe by construction.
 *
 * <p>Reduction (OpenCode-style fidelity levels): while the view exceeds the budget, every turn
 * except the newest is raised one level, oldest first and breadth-first. The newest turn is never
 * omitted; if it alone is too big it is clipped. The result therefore always fits.</p>
 */
public final class SessionView {

    /** L0 whole · L1 assistant clipped · L2 user + activity line · L3 user clipped · L4 omitted. */
    static final int OMITTED = 4;
    static final int CLIP_CHARS = 2_000;            // head 1000 + tail 1000 (OpenCode TRANSCRIPT_CLIP)
    static final int GOAL_MAX_CHARS = 2_000;
    static final int ACTIVITY_CAP = 30;
    static final int TOOL_LINES_CAP = 20;
    static final int COMMAND_MAX_CHARS = 100;
    static final double HEADER_SHARE = 0.4;
    static final double BUDGET_SHARE = 0.25;        // Kilo tail budget: usable × 0.25
    static final int MIN_BUDGET = 2_000;
    static final int MAX_BUDGET = 15_000;
    static final int UNKNOWN_BUDGET = 8_000;

    private static final String[] PATH_KEYS = { "filePath", "path", "targetPath", "sourcePath", "file" };
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private SessionView() {}

    /** What the view header says about the session — taken from the index row. */
    public record Header(String displayId, String title, String mode, long updatedAt, boolean interrupted) {}

    enum Kind { EDITED, READ, COMMAND, OTHER }

    record ToolCall(String name, String target, Kind kind) {
        String line() {
            return "→ " + name + "(" + (target == null ? "" : target) + ")";
        }
    }

    record Turn(int number, String user, String assistant, List<ToolCall> tools, List<String> notes) {}

    record Parsed(List<Turn> turns, List<String> trailingNotes, String goal, String lastSummary) {}

    // ---- budget & estimation ----

    /** {@code clamp(autoCompactAfter × 0.25, 2k, 15k)} tokens; 8k when the threshold is unknown. */
    public static int budgetTokens(int autoCompactAfter) {
        if (autoCompactAfter <= 0) return UNKNOWN_BUDGET;
        return (int) Math.max(MIN_BUDGET, Math.min(MAX_BUDGET, Math.round(autoCompactAfter * BUDGET_SHARE)));
    }

    /** Gemini CLI estimate: 0.33 tokens per ASCII char, 1.5 per other char — chars/4 undercounts Korean. */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        double tokens = text.codePoints().mapToDouble(c -> c < 128 ? 0.33 : 1.5).sum();
        return (int) Math.ceil(tokens);
    }

    /** Head/tail clip with a visible marker; never splits a surrogate pair. */
    static String clip(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text;
        int head = safeIndex(text, maxChars / 2);
        int tail = safeIndex(text, text.length() - (maxChars - maxChars / 2));
        if (tail < head) tail = head;
        return text.substring(0, head)
                + "\n[… " + (tail - head) + " characters omitted …]\n"
                + text.substring(tail);
    }

    private static int safeIndex(String text, int index) {
        if (index > 0 && index < text.length() && Character.isLowSurrogate(text.charAt(index))) return index - 1;
        return index;
    }

    // ---- parsing ----

    static Parsed parse(List<TranscriptEvent> events, Predicate<String> isEditTool) {
        var builders = new LinkedHashMap<String, TurnBuilder>();
        var pendingNotes = new ArrayList<String>();
        String goal = null;
        String lastSummary = null;
        for (var e : events) {
            switch (e.type()) {
                case TURN_STARTED -> turn(builders, e.turnId(), pendingNotes);
                case USER -> {
                    if (goal == null && hasText(e.text())) goal = e.text();
                    turn(builders, e.turnId(), pendingNotes).user.add(e.text());
                }
                case AI -> turn(builders, e.turnId(), pendingNotes).assistant.add(e.text());
                case TOOL_INTENT -> turn(builders, e.turnId(), pendingNotes).tools
                        .add(classify(e.toolName(), e.text(), isEditTool));
                case COMPACTED -> {
                    if (hasText(e.text())) lastSummary = e.text();
                    note(builders, e.turnId(), pendingNotes,
                            "(context compacted here — earlier messages were summarized for the model)");
                }
                case CLEARED -> note(builders, e.turnId(), pendingNotes, "(model context cleared here)");
                default -> { /* WAL bookkeeping, THINK and tool payloads never reach the view */ }
            }
        }
        var turns = new ArrayList<Turn>();
        int n = 0;
        for (var b : builders.values()) turns.add(b.build(++n));
        return new Parsed(turns, List.copyOf(pendingNotes), goal, lastSummary);
    }

    private static TurnBuilder turn(Map<String, TurnBuilder> builders, String turnId, List<String> pendingNotes) {
        var key = turnId == null ? "" : turnId;
        return builders.computeIfAbsent(key, k -> {
            var b = new TurnBuilder();
            b.notes.addAll(pendingNotes);
            pendingNotes.clear();
            return b;
        });
    }

    private static void note(Map<String, TurnBuilder> builders, String turnId, List<String> pendingNotes, String text) {
        var existing = turnId == null ? null : builders.get(turnId);
        if (existing != null) existing.notes.add(text);
        else pendingNotes.add(text);
    }

    static ToolCall classify(String name, String arguments, Predicate<String> isEditTool) {
        var args = parseArgs(arguments);
        var command = text(args, "command");
        if (command != null) return new ToolCall(name, clip(command.strip(), COMMAND_MAX_CHARS), Kind.COMMAND);
        String path = null;
        for (var key : PATH_KEYS) {
            path = text(args, key);
            if (path != null) break;
        }
        if (path == null) return new ToolCall(name, null, Kind.OTHER);
        return new ToolCall(name, path, name != null && isEditTool.test(name) ? Kind.EDITED : Kind.READ);
    }

    private static JsonNode parseArgs(String arguments) {
        if (!hasText(arguments)) return null;
        try {
            return SessionFiles.mapper().readTree(arguments);
        } catch (Exception e) {
            return null; // free-form or truncated arguments: no target, still listed by name
        }
    }

    private static String text(JsonNode node, String key) {
        if (node == null || !node.hasNonNull(key)) return null;
        var value = node.get(key).asText();
        return hasText(value) ? value : null;
    }

    private static final class TurnBuilder {
        final List<String> user = new ArrayList<>();
        final List<String> assistant = new ArrayList<>();
        final List<ToolCall> tools = new ArrayList<>();
        final List<String> notes = new ArrayList<>();

        Turn build(int number) {
            return new Turn(number, join(user), join(assistant), List.copyOf(tools), List.copyOf(notes));
        }

        private static String join(List<String> parts) {
            var text = parts.stream().filter(SessionView::hasText).collect(Collectors.joining("\n\n"));
            return text.isEmpty() ? null : text;
        }
    }

    // ---- rendering ----

    /**
     * The view the tool returns. {@code fromTurn}/{@code toTurn} (1-based, inclusive, either may
     * be null) select a range for paging; then the header shrinks to one title line.
     */
    public static String render(Header header, List<TranscriptEvent> events, List<TranscriptEvent> unresolvedIntents,
            Predicate<String> isEditTool, Integer fromTurn, Integer toTurn, int budgetTokens) {
        var parsed = parse(events, isEditTool);
        var all = parsed.turns();
        boolean paging = fromTurn != null || toTurn != null;
        int from = 1;
        int to = all.size();
        if (paging) {
            from = Math.max(1, fromTurn == null ? 1 : fromTurn);
            to = Math.min(all.size(), toTurn == null ? all.size() : toTurn);
            if (from > to) {
                return header.displayId() + " has " + all.size() + " turns — the range "
                        + fromTurn + "–" + toTurn + " is empty.";
            }
        }
        var selected = all.subList(from - 1, to);
        var head = paging
                ? rangeHeader(header, from, to, all.size())
                : fullHeader(header, parsed, unresolvedIntents, isEditTool, all.size(), budgetTokens);
        var tail = paging || parsed.trailingNotes().isEmpty() ? "" : String.join("\n", parsed.trailingNotes()) + "\n";
        if (selected.isEmpty()) return head + "(no turns recorded)\n" + tail;
        return head + renderTurns(header.displayId(), selected,
                budgetTokens - estimateTokens(head) - estimateTokens(tail)) + tail;
    }

    /** Applies the levels until the turns fit {@code budget}; the newest turn is clipped as a last resort. */
    static String renderTurns(String displayId, List<Turn> turns, int budget) {
        int n = turns.size();
        int[][] cost = new int[n][OMITTED + 1];
        for (int i = 0; i < n; i++) {
            for (int level = 0; level < OMITTED; level++) cost[i][level] = estimateTokens(renderTurn(turns.get(i), level));
        }
        int[] levels = new int[n];
        raise:
        for (int level = 1; level <= OMITTED; level++) {
            for (int i = 0; i < n - 1; i++) { // the newest turn is never raised
                if (total(cost, levels, turns, displayId) <= budget) break raise;
                levels[i] = level;
            }
        }

        var out = new StringBuilder();
        int i = 0;
        while (i < n - 1) {
            if (levels[i] == OMITTED) {
                int start = i;
                while (i < n - 1 && levels[i] == OMITTED) i++;
                out.append(omittedMarker(displayId, turns.get(start).number(), turns.get(i - 1).number()));
            } else {
                out.append(renderTurn(turns.get(i), levels[i]));
                i++;
            }
        }
        int othersCost = total(cost, levels, turns, displayId) - cost[n - 1][0];
        out.append(fitNewest(turns.get(n - 1), budget - othersCost));
        return out.toString();
    }

    private static int total(int[][] cost, int[] levels, List<Turn> turns, String displayId) {
        int sum = 0;
        for (int i = 0; i < levels.length; i++) {
            if (levels[i] < OMITTED) {
                sum += cost[i][levels[i]];
            } else if (i == 0 || levels[i - 1] < OMITTED) { // one marker per omitted run, costed exactly
                int end = i;
                while (end + 1 < levels.length && levels[end + 1] == OMITTED) end++;
                sum += estimateTokens(omittedMarker(displayId, turns.get(i).number(), turns.get(end).number()));
            }
        }
        return sum;
    }

    static String renderTurn(Turn t, int level) {
        return renderTurn(t, level, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    private static String renderTurn(Turn t, int level, int assistantMax, int userMax) {
        var sb = new StringBuilder("### T").append(t.number()).append('\n');
        if (level < 3) t.notes().forEach(note -> sb.append(note).append('\n'));
        var user = t.user() == null ? "(no user message)" : t.user();
        switch (level) {
            case 0, 1 -> {
                sb.append("User: ").append(clip(user, userMax)).append('\n');
                if (t.assistant() != null) {
                    int max = level == 1 ? Math.min(CLIP_CHARS, assistantMax) : assistantMax;
                    if (max > 0) sb.append("Assistant: ").append(clip(t.assistant(), max)).append('\n');
                    else sb.append("(assistant reply omitted)\n");
                }
                appendToolLines(sb, t.tools());
            }
            case 2 -> {
                sb.append("User: ").append(user).append('\n');
                var activity = activityLine(t.tools());
                if (activity != null) sb.append(activity).append('\n');
                if (t.assistant() != null) sb.append("(assistant reply omitted)\n");
            }
            default -> sb.append("User: ").append(clip(user, CLIP_CHARS)).append('\n');
        }
        return sb.append('\n').toString();
    }

    /** The newest turn always stays: shrink its assistant text, then its user text, until it fits. */
    private static String fitNewest(Turn t, int budget) {
        int assistantMax = t.assistant() == null ? 0 : t.assistant().length();
        int userMax = t.user() == null ? 0 : t.user().length();
        var text = renderTurn(t, 0, Math.max(assistantMax, 1), Math.max(userMax, 1));
        while (estimateTokens(text) > budget && assistantMax > 0) {
            assistantMax = assistantMax < 200 ? 0 : assistantMax / 2;
            text = renderTurn(t, 0, assistantMax, Math.max(userMax, 1));
        }
        while (estimateTokens(text) > budget && userMax > 200) {
            userMax /= 2;
            text = renderTurn(t, 0, 0, userMax);
        }
        return text;
    }

    private static void appendToolLines(StringBuilder sb, List<ToolCall> tools) {
        tools.stream().limit(TOOL_LINES_CAP).forEach(tc -> sb.append(tc.line()).append('\n'));
        if (tools.size() > TOOL_LINES_CAP) sb.append("→ … +").append(tools.size() - TOOL_LINES_CAP).append(" more tool calls\n");
    }

    private static String activityLine(List<ToolCall> tools) {
        var parts = new ArrayList<String>();
        addActivity(parts, "edited", tools, Kind.EDITED, 5);
        addActivity(parts, "read", tools, Kind.READ, 5);
        addActivity(parts, "ran", tools, Kind.COMMAND, 3);
        return parts.isEmpty() ? null : "→ " + String.join(" · ", parts);
    }

    private static void addActivity(List<String> parts, String label, List<ToolCall> tools, Kind kind, int cap) {
        var targets = targets(tools, kind);
        if (!targets.isEmpty()) parts.add(label + ": " + capped(targets, cap));
    }

    static String omittedMarker(String displayId, int from, int to) {
        var range = from == to ? "T" + from : "T" + from + "–T" + to;
        return "[" + range + " omitted — readChatSession(\"" + displayId + "\", fromTurn=" + from
                + ", toTurn=" + to + ")]\n\n";
    }

    // ---- headers ----

    private static String rangeHeader(Header h, int from, int to, int total) {
        return "# " + h.displayId() + " — \"" + h.title() + "\" · turns " + from + "–" + to + " of " + total
                + " (read-only view)\n\n## Conversation\n";
    }

    /** Bounded to {@value #HEADER_SHARE} of the budget: the summary is clipped first, then the activity lists. */
    private static String fullHeader(Header h, Parsed p, List<TranscriptEvent> unresolved,
            Predicate<String> isEditTool, int turnCount, int budget) {
        int summaryMax = p.lastSummary() == null ? 0 : p.lastSummary().length();
        int activityCap = ACTIVITY_CAP;
        var header = fullHeader(h, p, unresolved, isEditTool, turnCount, summaryMax, activityCap);
        while (estimateTokens(header) > budget * HEADER_SHARE) {
            if (summaryMax > 400) summaryMax /= 2;
            else if (activityCap > 3) activityCap = Math.max(3, activityCap / 2);
            else break;
            header = fullHeader(h, p, unresolved, isEditTool, turnCount, summaryMax, activityCap);
        }
        return header;
    }

    private static String fullHeader(Header h, Parsed p, List<TranscriptEvent> unresolved,
            Predicate<String> isEditTool, int turnCount, int summaryMax, int activityCap) {
        var sb = new StringBuilder("# ").append(h.displayId()).append(" — \"").append(h.title()).append("\" (")
                .append(h.mode() == null ? "?" : h.mode()).append(" · ")
                .append(TIME.format(Instant.ofEpochMilli(h.updatedAt()).atZone(ZoneId.systemDefault())));
        if (h.interrupted()) sb.append(" · ⚠ interrupted");
        sb.append(")\nRead-only view of an earlier conversation with ").append(turnCount)
          .append(" turns. Continue the work in THIS conversation; the files on disk are the source of truth"
                  + " — re-read them before editing.\n\n");

        sb.append("## Goal\n").append(p.goal() == null ? "(unknown)" : clip(p.goal(), GOAL_MAX_CHARS)).append("\n\n");

        var tools = p.turns().stream().flatMap(t -> t.tools().stream()).toList();
        var edited = targets(tools, Kind.EDITED);
        var read = targets(tools, Kind.READ);
        var commands = targets(tools, Kind.COMMAND);
        if (!edited.isEmpty() || !read.isEmpty() || !commands.isEmpty()) {
            sb.append("## Activity\n");
            if (!edited.isEmpty()) sb.append("Edited: ").append(capped(edited, activityCap)).append('\n');
            if (!read.isEmpty()) sb.append("Read: ").append(capped(read, activityCap)).append('\n');
            if (!commands.isEmpty()) sb.append("Commands: ").append(capped(commands, activityCap)).append('\n');
            sb.append('\n');
        }

        if (h.interrupted()) {
            sb.append("## ⚠ Interrupted\n");
            if (unresolved == null || unresolved.isEmpty()) {
                sb.append("The last turn did not finish; no tool was left mid-execution.\n\n");
            } else {
                sb.append("The last turn did not finish. Tools with UNKNOWN outcome — verify on disk before redoing them:\n");
                unresolved.stream().limit(TOOL_LINES_CAP).forEach(e ->
                        sb.append("- ").append(classify(e.toolName(), e.text(), isEditTool).line()).append('\n'));
                sb.append('\n');
            }
        }

        if (p.lastSummary() != null && summaryMax > 0) {
            sb.append("## Earlier summary\n").append(clip(p.lastSummary(), summaryMax)).append("\n\n");
        }
        return sb.append("## Conversation\n").toString();
    }

    private static Set<String> targets(List<ToolCall> tools, Kind kind) {
        var result = new LinkedHashSet<String>();
        tools.stream().filter(t -> t.kind() == kind && t.target() != null).forEach(t -> result.add(t.target()));
        return result;
    }

    private static String capped(Set<String> values, int cap) {
        var shown = values.stream().limit(cap).collect(Collectors.joining(", "));
        return values.size() > cap ? shown + " (+" + (values.size() - cap) + " more)" : shown;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
