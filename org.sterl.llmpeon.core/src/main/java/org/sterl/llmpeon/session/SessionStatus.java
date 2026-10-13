package org.sterl.llmpeon.session;

/**
 * Outcome of a session's last persisted turn. INTERRUPTED covers both an in-process exception
 * and a hard kill detected after the fact through the transcript WAL (TURN_STARTED without
 * TURN_COMMITTED) — the cause differs, the recovery handling is the same: tool outcomes may be
 * unknown and nothing auto-reruns.
 */
public enum SessionStatus {
    COMPLETED,
    CANCELED,
    INTERRUPTED
}
