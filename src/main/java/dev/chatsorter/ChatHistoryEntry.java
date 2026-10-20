package dev.chatsorter;

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Display row in the chat. Consecutive repeats share a row but retain every known source message. */
public final class ChatHistoryEntry {
    public final UUID id;
    public final String serverId;
    public final String sessionId;
    public final Instant receivedAt;
    public final String senderId;
    public final Text message;
    public final String time;
    public final ChatSorter.Tab tab;
    public final String sender;
    public final String technical;
    public final Set<String> categories;
    public String componentJson = "";
    public int repeats;
    public boolean restored;
    public boolean bookmarkFallback;

    private final ArrayList<ChatMessageRecord> sourceRecords = new ArrayList<>();

    public ChatHistoryEntry(ChatMessageRecord source, String time, ChatSorter.Tab tab,
                            String technical, boolean restored) {
        this(source == null ? Text.empty() : source.message(), time, tab,
            source == null ? "" : source.senderName(), technical, 1, restored,
            source == null ? "" : source.serverId(), source == null ? "" : source.sessionId(),
            source == null ? null : source.id(), source == null ? null : source.receivedAt(),
            source == null ? "" : source.senderId(),
            source == null ? Set.of(tab == null ? ChatSorter.Tab.CONSOLE.name() : tab.name()) : source.categories(),
            source == null ? "" : source.componentJson());
        sourceRecords.clear();
        if (source != null) sourceRecords.add(source);
    }

    public ChatHistoryEntry(Text message, String time, ChatSorter.Tab tab, String sender,
                            String technical, int repeats, boolean restored) {
        this(message, time, tab, sender, technical, repeats, restored,
            "", "", UUID.randomUUID(), parseTimeOrNow(time), "",
            Set.of((tab == null ? ChatSorter.Tab.CONSOLE : tab).name()), "");
    }

    public ChatHistoryEntry(Text message, String time, ChatSorter.Tab tab, String sender,
                            String technical, int repeats, boolean restored,
                            String serverId, String sessionId, UUID id, Instant receivedAt,
                            String senderId, Set<String> categories, String componentJson) {
        this.message = message == null ? Text.empty() : message;
        this.time = time == null ? "" : time;
        this.tab = tab == null ? ChatSorter.Tab.CONSOLE : tab;
        this.sender = sender == null ? "" : sender;
        this.technical = technical == null ? "" : technical;
        this.repeats = Math.max(1, repeats);
        this.restored = restored;
        this.id = id == null ? UUID.randomUUID() : id;
        this.serverId = serverId == null ? "" : serverId;
        this.sessionId = sessionId == null ? "" : sessionId;
        this.receivedAt = receivedAt == null ? Instant.now() : receivedAt;
        this.senderId = senderId == null || senderId.isBlank() ? this.sender : senderId;
        LinkedHashSet<String> normalizedCategories = new LinkedHashSet<>();
        if (categories != null) normalizedCategories.addAll(categories);
        normalizedCategories.add(this.tab.name());
        this.categories = Set.copyOf(normalizedCategories);
        this.componentJson = componentJson == null ? "" : componentJson;
        this.sourceRecords.add(new ChatMessageRecord(this.id, this.serverId, this.sessionId,
            this.receivedAt, this.senderId, this.sender, this.message.getString(), this.message,
            this.componentJson, this.categories, this.tab == ChatSorter.Tab.DM ? this.senderId : "", 1, restored));
    }

    public ChatHistoryEntry(String time, ChatSorter.Tab tab, String sender, String technical,
                            int repeats, boolean restored, List<ChatMessageRecord> sources,
                            Set<String> categories, String componentJson) {
        ChatMessageRecord first = sources == null || sources.isEmpty() ? null : sources.get(0);
        this.message = first == null ? Text.empty() : first.message();
        this.time = time == null ? "" : time;
        this.tab = tab == null ? ChatSorter.Tab.CONSOLE : tab;
        this.sender = first == null ? (sender == null ? "" : sender) : first.senderName();
        this.technical = technical == null ? "" : technical;
        this.repeats = Math.max(1, repeats);
        this.restored = restored;
        this.id = first == null ? UUID.randomUUID() : first.id();
        this.serverId = first == null ? "" : first.serverId();
        this.sessionId = first == null ? "" : first.sessionId();
        this.receivedAt = first == null ? parseTimeOrNow(time) : first.receivedAt();
        this.senderId = first == null ? this.sender : first.senderId();
        LinkedHashSet<String> normalizedCategories = new LinkedHashSet<>();
        if (categories != null) normalizedCategories.addAll(categories);
        normalizedCategories.add(this.tab.name());
        this.categories = Set.copyOf(normalizedCategories);
        this.componentJson = componentJson == null ? "" : componentJson;
        if (sources != null) this.sourceRecords.addAll(sources);
        if (this.sourceRecords.isEmpty()) {
            this.sourceRecords.add(new ChatMessageRecord(this.id, this.serverId, this.sessionId,
                this.receivedAt, this.senderId, this.sender, this.message.getString(), this.message,
                this.componentJson, this.categories, this.tab == ChatSorter.Tab.DM ? this.senderId : "", 1, restored));
        }
    }

    public List<ChatMessageRecord> sourceRecords() {
        return List.copyOf(sourceRecords);
    }

    public void appendSource(ChatMessageRecord source) {
        if (source == null || sourceRecords.stream().anyMatch(existing -> existing.id().equals(source.id()))) return;
        sourceRecords.add(source);
        repeats = Math.max(repeats + 1, sourceRecords.size());
    }

    public Text displayText() {
        MutableText shown = bookmarkFallback
            ? Text.literal("[избранное · копия] ").styled(s -> s.withColor(0xFFE0BD65)).append(message.copy())
            : restored ? Text.literal("[пред. сессия] ").styled(s -> s.withColor(0xFF8A929B)).append(message.copy()) : message.copy();
        if (repeats > 1) shown.append(Text.literal(" ×" + repeats).styled(s -> s.withColor(0xFF9EA7B0)));
        return shown;
    }

    private static Instant parseTimeOrNow(String time) {
        try { return Instant.parse(time); }
        catch (Exception ignored) { return Instant.now(); }
    }
}
