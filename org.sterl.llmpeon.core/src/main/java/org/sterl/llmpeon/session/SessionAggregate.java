package org.sterl.llmpeon.session;

import java.util.List;

import org.sterl.llmpeon.PeonMode;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;

/**
 * The Phase 1 session unit: both DEV and PLAN memories plus the active mode, always stored
 * together in one checkpoint. Memories are kept as langchain4j-serialized JSON strings so the
 * message wire format stays fully owned by langchain4j (see messageFormatVersion in
 * {@link Checkpoint}).
 */
public record SessionAggregate(
        PeonMode activeMode,
        String devMemory,
        String planMemory) {

    public static SessionAggregate of(PeonMode activeMode, List<ChatMessage> devMessages, List<ChatMessage> planMessages) {
        return new SessionAggregate(activeMode,
                ChatMessageSerializer.messagesToJson(devMessages == null ? List.of() : devMessages),
                ChatMessageSerializer.messagesToJson(planMessages == null ? List.of() : planMessages));
    }

    public List<ChatMessage> devMessages() {
        return devMemory == null ? List.of() : ChatMessageDeserializer.messagesFromJson(devMemory);
    }

    public List<ChatMessage> planMessages() {
        return planMemory == null ? List.of() : ChatMessageDeserializer.messagesFromJson(planMemory);
    }
}
