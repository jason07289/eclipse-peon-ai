package org.sterl.llmpeon.session;

import lombok.Builder;

/**
 * Authoritative session metadata, stored inside every checkpoint (the index is only a cache
 * rebuilt from these). Times are epoch millis.
 */
@Builder(toBuilder = true)
public record SessionMeta(
        String id,
        String title,
        SessionStatus status,
        long createdAt,
        long updatedAt,
        String workspacePath,
        String projectName,
        String projectLocation,
        String planOverviewFile,
        int contextTokens) {

    public static final int TITLE_MAX_LENGTH = 60;

    /** First user message, single line, truncated to {@value #TITLE_MAX_LENGTH} chars. */
    public static String buildTitle(String firstUserMessage) {
        if (firstUserMessage == null || firstUserMessage.isBlank()) return "New session";
        var line = firstUserMessage.strip().lines().findFirst().orElse("").strip();
        return line.length() <= TITLE_MAX_LENGTH ? line : line.substring(0, TITLE_MAX_LENGTH - 1) + "…";
    }
}
