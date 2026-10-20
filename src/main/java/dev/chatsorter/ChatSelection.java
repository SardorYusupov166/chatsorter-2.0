package dev.chatsorter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** Selection state and export helpers for the active chat timeline. */
public final class ChatSelection {
    private static final LinkedHashSet<UUID> SELECTED = new LinkedHashSet<>();
    private static UUID anchor;
    private static boolean expandRepeats;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private ChatSelection() { }

    public static boolean contains(ChatHistoryEntry entry) { return entry != null && SELECTED.contains(entry.id); }
    public static int size() { return SELECTED.size(); }
    public static boolean isEmpty() { return SELECTED.isEmpty(); }
    public static void clear() { SELECTED.clear(); anchor = null; }
    public static boolean expandsRepeats() { return expandRepeats; }
    public static void toggleRepeatExpansion() { expandRepeats = !expandRepeats; }

    public static void click(ChatHistoryEntry entry, boolean control, boolean shift, List<ChatHistoryEntry> visible) {
        if (entry == null) return;
        int clicked = indexOf(visible, entry.id);
        if (shift && anchor != null) {
            int start = indexOf(visible, anchor);
            if (start >= 0 && clicked >= 0) {
                if (!control) SELECTED.clear();
                for (int i = Math.min(start, clicked); i <= Math.max(start, clicked); i++) SELECTED.add(visible.get(i).id);
                return;
            }
        }
        if (control) {
            if (!SELECTED.remove(entry.id)) SELECTED.add(entry.id);
        } else {
            SELECTED.clear();
            SELECTED.add(entry.id);
        }
        anchor = entry.id;
    }

    public static void selectAll(List<ChatHistoryEntry> visible) {
        SELECTED.clear();
        for (ChatHistoryEntry entry : visible) SELECTED.add(entry.id);
        anchor = visible.isEmpty() ? null : visible.get(visible.size() - 1).id;
    }

    public static List<ChatHistoryEntry> selected(List<ChatHistoryEntry> visible) {
        ArrayList<ChatHistoryEntry> result = new ArrayList<>();
        for (ChatHistoryEntry entry : visible) if (SELECTED.contains(entry.id)) result.add(entry);
        return List.copyOf(result);
    }

    public static String plainText(List<ChatHistoryEntry> visible, boolean withTime, boolean withSender) {
        return plainTextEntries(selected(visible), withTime, withSender);
    }

    private static String plainTextEntries(List<ChatHistoryEntry> entries, boolean withTime, boolean withSender) {
        StringBuilder out = new StringBuilder();
        for (ChatHistoryEntry entry : entries) {
            if (expandRepeats && entry.sourceRecords().size() > 1) {
                for (ChatMessageRecord source : entry.sourceRecords()) {
                    if (out.length() > 0) out.append('\n');
                    appendText(out, source.rawText(), source.senderName(), source.receivedAt(), withTime, withSender);
                }
            } else {
                if (out.length() > 0) out.append('\n');
                appendText(out, entry.message.getString() + (entry.repeats > 1 ? " ×" + entry.repeats : ""),
                    entry.sender, entry.receivedAt, withTime, withSender);
            }
        }
        return out.toString();
    }

    public static Path export(List<ChatHistoryEntry> visible, boolean json) throws IOException {
        List<ChatHistoryEntry> entries = List.copyOf(visible);
        if (entries.isEmpty()) throw new IOException("Нет выбранных сообщений");
        Path directory = FabricLoader.getInstance().getConfigDir().resolve("chatsorter").resolve("exports");
        Files.createDirectories(directory);
        String suffix = FILE_TIME.format(LocalDateTime.now());
        Path path = directory.resolve("chat-selection-" + suffix + (json ? ".json" : ".txt"));
        String contents = json ? toJson(entries) : plainTextEntries(entries, true, true);
        Files.writeString(path, contents, StandardCharsets.UTF_8);
        return path;
    }

    private static String toJson(List<ChatHistoryEntry> entries) {
        JsonArray array = new JsonArray();
        for (ChatHistoryEntry entry : entries) {
            JsonObject row = new JsonObject();
            row.addProperty("id", entry.id.toString());
            row.addProperty("server", entry.serverId);
            row.addProperty("session", entry.sessionId);
            row.addProperty("receivedAt", entry.receivedAt.toString());
            row.addProperty("sender", entry.sender);
            row.addProperty("text", entry.message.getString());
            row.addProperty("formattedText", ChatSorter.copyFormatted(entry));
            row.addProperty("category", entry.tab.name());
            row.addProperty("repeatCount", entry.repeats);
            row.addProperty("restored", entry.restored);
            JsonArray sources = new JsonArray();
            entry.sourceRecords().forEach(source -> {
                JsonObject sourceRow = new JsonObject();
                sourceRow.addProperty("id", source.id().toString());
                sourceRow.addProperty("receivedAt", source.receivedAt().toString());
                sourceRow.addProperty("sender", source.senderName());
                sourceRow.addProperty("text", source.rawText());
                sources.add(sourceRow);
            });
            row.add("sourceMessages", sources);
            if (expandRepeats && entry.sourceRecords().size() > 1) {
                for (ChatMessageRecord source : entry.sourceRecords()) {
                    JsonObject original = new JsonObject();
                    original.addProperty("id", source.id().toString());
                    original.addProperty("server", source.serverId());
                    original.addProperty("session", source.sessionId());
                    original.addProperty("receivedAt", source.receivedAt().toString());
                    original.addProperty("sender", source.senderName());
                    original.addProperty("text", source.rawText());
                    original.addProperty("category", entry.tab.name());
                    original.addProperty("restored", source.restored());
                    array.add(original);
                }
            } else array.add(row);
        }
        return new GsonBuilder().setPrettyPrinting().create().toJson(array);
    }

    private static void appendText(StringBuilder out, String text, String sender, java.time.Instant instant,
                                   boolean withTime, boolean withSender) {
        if (withTime) out.append('[').append(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault()).format(instant)).append("] ");
        if (withSender && sender != null && !sender.isBlank()) out.append(sender).append(": ");
        out.append(text);
    }

    private static int indexOf(List<ChatHistoryEntry> entries, UUID id) {
        if (id == null) return -1;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).id.equals(id)) return i;
        return -1;
    }
}
