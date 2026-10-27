package org.sterl.llmpeon.session;

import java.util.Locale;
import java.util.Optional;

/**
 * The short, user-facing session ID (chat-session design §9.2): {@code ses_} + the first 8 hex
 * characters of the session UUID. Display only — directories stay full UUIDs. The prefix keeps it
 * apart from git short hashes, and {@code _} keeps a double-click selection whole.
 */
public final class SessionIds {

    public static final String PREFIX = "ses_";
    static final int DISPLAY_LENGTH = 8;
    static final int MIN_QUERY_LENGTH = 6;

    private SessionIds() {}

    public static String displayId(String sessionId) {
        if (sessionId == null) return PREFIX;
        var compact = compact(sessionId);
        return PREFIX + compact.substring(0, Math.min(DISPLAY_LENGTH, compact.length()));
    }

    /**
     * Normalizes what a user or model typed: optional {@code ses_} prefix (any case), dashes
     * ignored. Empty when fewer than {@value #MIN_QUERY_LENGTH} hex characters remain.
     */
    public static Optional<String> normalizeQuery(String query) {
        if (query == null) return Optional.empty();
        var q = query.strip().toLowerCase(Locale.ROOT);
        if (q.startsWith(PREFIX)) q = q.substring(PREFIX.length());
        q = q.replace("-", "");
        if (q.length() < MIN_QUERY_LENGTH || !q.chars().allMatch(SessionIds::isHex)) return Optional.empty();
        return Optional.of(q);
    }

    /** True when the session UUID starts with the normalized query. */
    public static boolean matches(String sessionId, String normalizedQuery) {
        return sessionId != null && compact(sessionId).startsWith(normalizedQuery);
    }

    private static String compact(String sessionId) {
        return sessionId.replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static boolean isHex(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }
}
