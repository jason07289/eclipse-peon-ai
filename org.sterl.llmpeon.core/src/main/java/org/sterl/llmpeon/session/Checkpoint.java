package org.sterl.llmpeon.session;

import lombok.Builder;

/**
 * One immutable, self-contained revision of a session. A commit writes a new checkpoint file
 * and then switches the CURRENT pointer — checkpoint files are never modified in place, so a
 * crash at any step leaves the previous revision intact.
 *
 * <p>{@code schemaVersion} versions THIS structure; {@code messageFormatVersion} versions the
 * langchain4j message serialization inside {@link SessionAggregate} — the two evolve
 * independently (a langchain4j upgrade must not require a store schema bump).</p>
 */
@Builder(toBuilder = true)
public record Checkpoint(
        int schemaVersion,
        String messageFormatVersion,
        long revision,
        SessionMeta meta,
        SessionAggregate aggregate,
        long lastTranscriptSeq) {

    public static final int SCHEMA_VERSION = 1;
    public static final String MESSAGE_FORMAT_VERSION = "lc4j-1";

    public boolean isCompatible() {
        return schemaVersion == SCHEMA_VERSION && MESSAGE_FORMAT_VERSION.equals(messageFormatVersion);
    }
}
