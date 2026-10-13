package org.sterl.llmpeon.session;

/**
 * Cleanup limits (chat-session design §9). Interrupted sessions survive count/size eviction —
 * the user may still need to inspect them — but even they expire by {@code maxAgeDays}, so crash
 * leftovers can never become immortal. Nothing younger than {@code minRetentionDays} is ever
 * auto-deleted, and the currently open session is always protected.
 */
public record RetentionPolicy(
        int maxAgeDays,
        int maxCount,
        int minRetentionDays,
        long maxTotalBytes) {

    public static final RetentionPolicy DEFAULT = new RetentionPolicy(30, 50, 1, 1024L * 1024 * 1024);

    public long maxAgeMillis() {
        return maxAgeDays * 24L * 60 * 60 * 1000;
    }

    public long minRetentionMillis() {
        return minRetentionDays * 24L * 60 * 60 * 1000;
    }
}
