package dev.chatsorter;

import net.minecraft.text.Text;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Immutable source message. Display rows may aggregate several source records as repeats. */
public record ChatMessageRecord(
    UUID id,
    String serverId,
    String sessionId,
    Instant receivedAt,
    String senderId,
    String senderName,
    String rawText,
    Text message,
    String componentJson,
    Set<String> categories,
    String conversationId,
    int repeatCount,
    boolean restored
) {
    public ChatMessageRecord {
        id = id == null ? UUID.randomUUID() : id;
        serverId = serverId == null ? "" : serverId;
        sessionId = sessionId == null ? "" : sessionId;
        receivedAt = receivedAt == null ? Instant.now() : receivedAt;
        senderId = senderId == null ? "" : senderId;
        senderName = senderName == null ? "" : senderName;
        rawText = rawText == null ? "" : rawText;
        message = message == null ? Text.literal(rawText) : message;
        componentJson = componentJson == null ? "" : componentJson;
        categories = categories == null ? Set.of() : Set.copyOf(categories);
        conversationId = conversationId == null ? "" : conversationId;
        repeatCount = Math.max(1, repeatCount);
    }
}
