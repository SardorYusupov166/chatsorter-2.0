package dev.chatsorter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Canonical in-memory message timeline. Categories and future panels reference these rows. */
public final class ChatMessageStore {
    private final ArrayList<ChatHistoryEntry> timeline = new ArrayList<>();
    private final Map<UUID, ChatHistoryEntry> rowsByMessageId = new HashMap<>();
    private final Map<UUID, ChatMessageRecord> sourceMessagesById = new HashMap<>();
    private final LinkedHashMap<UUID, ChatHistoryEntry> rowsByEntryId = new LinkedHashMap<>();

    public List<ChatHistoryEntry> all() {
        return List.copyOf(timeline);
    }

    public List<ChatHistoryEntry> inCategory(ChatSorter.Tab tab) {
        if (tab == ChatSorter.Tab.ALL) return all();
        return entriesInCategory(tab.name());
    }

    public List<ChatHistoryEntry> entriesInCategory(String category) {
        if (category == null || category.isBlank()) return List.of();
        return timeline.stream().filter(entry -> entry.categories.contains(category)).toList();
    }

    /** Adds one display row and returns rows evicted to enforce the history limit. */
    public List<ChatHistoryEntry> add(ChatHistoryEntry entry, int limit) {
        if (entry == null) return List.of();
        timeline.add(entry);
        rowsByEntryId.put(entry.id, entry);
        index(entry);
        ArrayList<ChatHistoryEntry> evicted = new ArrayList<>();
        int safeLimit = Math.max(1, limit);
        while (timeline.size() > safeLimit) {
            ChatHistoryEntry removed = timeline.remove(0);
            rowsByEntryId.remove(removed.id);
            for (ChatMessageRecord source : removed.sourceRecords()) {
                rowsByMessageId.remove(source.id());
                sourceMessagesById.remove(source.id());
            }
            evicted.add(removed);
        }
        return List.copyOf(evicted);
    }

    public ChatHistoryEntry find(UUID messageId) {
        ChatHistoryEntry entry = rowsByMessageId.get(messageId);
        return entry != null ? entry : rowsByEntryId.get(messageId);
    }

    public ChatMessageRecord findSource(UUID messageId) {
        return sourceMessagesById.get(messageId);
    }

    public void addSource(ChatHistoryEntry row, ChatMessageRecord source) {
        if (row != null && source != null && rowsByEntryId.get(row.id) == row) {
            rowsByMessageId.put(source.id(), row);
            sourceMessagesById.put(source.id(), source);
        }
    }

    public boolean contains(ChatHistoryEntry entry) {
        return entry != null && rowsByEntryId.get(entry.id) == entry;
    }

    public void clear() {
        timeline.clear();
        rowsByMessageId.clear();
        sourceMessagesById.clear();
        rowsByEntryId.clear();
    }

    private void index(ChatHistoryEntry entry) {
        for (ChatMessageRecord source : entry.sourceRecords()) {
            rowsByMessageId.put(source.id(), entry);
            sourceMessagesById.put(source.id(), source);
        }
    }
}
