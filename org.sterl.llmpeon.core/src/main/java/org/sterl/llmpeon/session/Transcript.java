package org.sterl.llmpeon.session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * Append-only JSONL transcript of one session: user-facing history and write-ahead log in one
 * file. Events whose type {@link TranscriptEvent.Type#requiresSync() requiresSync} are fsynced
 * before {@code append} returns; everything else is written through but not forced, losing at
 * most cosmetic tail records on a crash — never a WAL record.
 *
 * <p>Reading is tolerant: a torn last line (no trailing newline from a crash mid-append) is
 * dropped, and unparseable or unknown-type lines are skipped instead of failing the load.</p>
 */
public class Transcript implements AutoCloseable {

    private final Path file;
    private final FileChannel channel;
    private long nextSeq;

    private Transcript(Path file, FileChannel channel, long nextSeq) {
        this.file = file;
        this.channel = channel;
        this.nextSeq = nextSeq;
    }

    public static Transcript open(Path file) {
        try {
            Files.createDirectories(file.getParent());
            long nextSeq = lastSeq(file) + 1;
            var channel = FileChannel.open(file,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            return new Transcript(file, channel, nextSeq);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open transcript " + file, e);
        }
    }

    public Path file() {
        return file;
    }

    public synchronized TranscriptEvent append(TranscriptEvent.Type type, String turnId, String mode,
            String text, String toolCallId, String toolName) {
        var event = new TranscriptEvent(nextSeq, turnId, mode, System.currentTimeMillis(),
                type, text, toolCallId, toolName);
        try {
            var line = SessionFiles.mapper().writeValueAsString(event) + "\n";
            channel.write(ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
            if (type.requiresSync()) channel.force(false);
            nextSeq++;
            return event;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append to transcript " + file, e);
        }
    }

    public TranscriptEvent append(TranscriptEvent.Type type, String turnId, String mode, String text) {
        return append(type, turnId, mode, text, null, null);
    }

    /** Last written sequence number, {@code nextSeq - 1}. */
    public synchronized long lastSeq() {
        return nextSeq - 1;
    }

    public synchronized void flush() {
        try {
            channel.force(false);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to flush transcript " + file, e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            channel.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- reading ----

    public static List<TranscriptEvent> readAll(Path file) {
        if (!Files.exists(file)) return List.of();
        final byte[] raw;
        try {
            raw = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read transcript " + file, e);
        }
        var content = new String(raw, StandardCharsets.UTF_8);
        var result = new ArrayList<TranscriptEvent>();
        int lineStart = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) != '\n') continue;
            parseLine(content.substring(lineStart, i), result);
            lineStart = i + 1;
        }
        // anything after the last newline is a torn append from a crash — dropped by design
        return result;
    }

    private static void parseLine(String line, List<TranscriptEvent> result) {
        if (line.isBlank()) return;
        try {
            var event = SessionFiles.mapper().readValue(line, TranscriptEvent.class);
            if (event.type() != null) result.add(event); // unknown future types read as null → skipped
        } catch (JsonProcessingException e) {
            // defensive: a damaged middle line must not make the whole history unreadable
        }
    }

    static long lastSeq(Path file) {
        var events = readAll(file);
        return events.isEmpty() ? 0 : events.getLast().seq();
    }

    // ---- WAL recovery analysis ----

    /**
     * Turns that never reached {@code TURN_COMMITTED}: the session was interrupted (hard kill or
     * exception after the WAL record). {@code unresolvedToolIntents} lists tools that were about
     * to run (or ran) without a recorded result — their side effects are unknown and must be
     * surfaced to the user; nothing may auto-rerun.
     */
    public record RecoveryState(
            boolean interrupted,
            List<String> pendingTurnIds,
            List<TranscriptEvent> unresolvedToolIntents) {
    }

    /**
     * @param lastCommittedSeq the checkpoint's {@code lastTranscriptSeq}; only events after it
     *                         are considered — earlier turns are covered by the checkpoint.
     */
    public static RecoveryState analyze(List<TranscriptEvent> events, long lastCommittedSeq) {
        var pendingTurns = new LinkedHashMap<String, Boolean>();
        var intents = new LinkedHashMap<String, TranscriptEvent>();
        for (var e : events) {
            if (e.seq() <= lastCommittedSeq || e.turnId() == null) continue;
            switch (e.type()) {
                case TURN_STARTED -> pendingTurns.put(e.turnId(), Boolean.TRUE);
                case TURN_COMMITTED -> pendingTurns.remove(e.turnId());
                case TOOL_INTENT -> {
                    if (e.toolCallId() != null) intents.put(e.toolCallId(), e);
                }
                case TOOL_RESULT -> {
                    if (e.toolCallId() != null) intents.remove(e.toolCallId());
                }
                default -> { /* history only */ }
            }
        }
        var unresolved = new ArrayList<TranscriptEvent>();
        for (Map.Entry<String, TranscriptEvent> entry : intents.entrySet()) {
            if (pendingTurns.containsKey(entry.getValue().turnId())) unresolved.add(entry.getValue());
        }
        return new RecoveryState(!pendingTurns.isEmpty(), List.copyOf(pendingTurns.keySet()), unresolved);
    }
}
