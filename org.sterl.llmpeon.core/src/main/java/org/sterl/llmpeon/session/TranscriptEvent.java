package org.sterl.llmpeon.session;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One line of {@code transcript.jsonl}. The transcript is the user-facing history AND the
 * write-ahead log: {@link Type#TURN_STARTED} and {@link Type#TOOL_INTENT} are flushed to disk
 * before the model call / tool execution they announce, so a hard kill is detectable afterwards.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TranscriptEvent(
        long seq,
        String turnId,
        String mode,
        long ts,
        Type type,
        String text,
        String toolCallId,
        String toolName) {

    public enum Type {
        /** WAL: flushed before the first model call of a turn. */
        TURN_STARTED,
        USER,
        AI,
        THINK,
        /** WAL: flushed before the tool executes — side-effect audit trail. */
        TOOL_INTENT,
        TOOL_RESULT,
        PROBLEM,
        /** Emitted by BOTH compaction paths (compressContext and CompactSessionTool). */
        COMPACTED,
        /** Clear keeps the session; only the model context was dropped. */
        CLEARED,
        /** Written after the checkpoint commit; text carries the revision. */
        TURN_COMMITTED;

        /** WAL records must reach the disk before the action they announce happens. */
        public boolean requiresSync() {
            return this == TURN_STARTED || this == TOOL_INTENT;
        }
    }
}
