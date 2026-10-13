package org.sterl.llmpeon.session;

/**
 * Turn-boundary hooks the chat/tool layer emits so a session implementation can write the
 * transcript WAL and commit checkpoints (chat-session design §3/§4). Core only SIGNALS — the
 * Eclipse-side session manager decides what to persist; the default is a no-op, so nothing
 * changes for callers that never set a recorder.
 *
 * <p>Contract for implementations:</p>
 * <ul>
 *   <li>{@link #turnStarted} and {@link #toolIntent} must be durable (fsynced) before they
 *       return — they are the WAL records that make a hard kill detectable.</li>
 *   <li>{@link #turnFinished}, {@link #compacted} and {@link #cleared} are the checkpoint
 *       commit points: memory is complete and the calling thread is the executing one.</li>
 * </ul>
 */
public interface TurnRecorder {

    TurnRecorder NO_OP = new TurnRecorder() {};

    /** Flushed before the first model call of the turn; also records the USER message. */
    default void turnStarted(String turnId, String userText) {}

    default void aiMessage(String turnId, String text) {}

    default void think(String turnId, String text) {}

    /** Flushed BEFORE the tool executes — the side-effect audit record. */
    default void toolIntent(String turnId, String toolCallId, String toolName, String arguments) {}

    default void toolResult(String turnId, String toolCallId, String toolName, String result) {}

    default void problem(String turnId, String text) {}

    /** Either compaction path succeeded; memory now holds the compacted view — commit point. */
    default void compacted(String turnId, String summary) {}

    /** The model context was dropped, the session is kept — commit point. */
    default void cleared() {}

    /** The turn ended on the executing thread; memory is final for this turn — commit point. */
    default void turnFinished(String turnId, SessionStatus status) {}
}
