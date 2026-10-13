package org.sterl.llmpeon.session;

/**
 * What the {@link SessionManager} needs from the chat layer to build a checkpoint. Implemented
 * by the Eclipse-side service holder (PeonAiService); called on the turn's executing thread at
 * commit points, where both memories are complete.
 */
public interface SessionSource {

    /** Immutable snapshot of {dev memory, plan memory, active mode}. */
    SessionAggregate snapshotAggregate();

    /** The project this conversation is bound to; null when none is selected. */
    default String projectName() { return null; }

    default String projectLocation() { return null; }

    default int contextTokens() { return 0; }
}
