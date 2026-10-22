package dev.chatsorter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import net.minecraft.text.TextCodecs;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Per-server local chat archive. Text components are stored as Minecraft JSON. */
public final class ChatHistoryStorage {
    private static final Path DIRECTORY = FabricLoader.getInstance().getConfigDir().resolve("chatsorter").resolve("history");
    private static final ExecutorService SAVER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ChatSorter-HistoryWriter");
        thread.setDaemon(true);
        return thread;
    });
    private static long lastSaveMillis;

    public static synchronized List<ChatHistoryEntry> load(String serverKey, int limit) {
        if (!ChatExperienceSettings.restoreHistory || serverKey == null) return List.of();
        Path file = file(serverKey);
        if (!Files.isRegularFile(file)) return List.of();
        List<ChatHistoryEntry> loaded = new ArrayList<>();
        try {
            JsonArray array = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
            int start = Math.max(0, array.size() - ChatExperienceSettings.clamp(limit));
            for (int i = start; i < array.size(); i++) {
                JsonObject o = array.get(i).getAsJsonObject();
                String json = o.has("component") ? o.get("component").getAsString() : "";
                Text message = deserializeText(json);
                if (message == null) message = Text.literal(o.has("plain") ? o.get("plain").getAsString() : "");
                ChatSorter.Tab tab;
                try { tab = ChatSorter.Tab.valueOf(o.get("tab").getAsString()); }
                catch (Exception e) { tab = ChatSorter.Tab.CONSOLE; }
                boolean migrateOldConsole = tab == ChatSorter.Tab.CONSOLE;
                if (migrateOldConsole && !ChatSorter.isPlayerCommandAudit(message.getString())) {
                    tab = ChatSorter.classify(message.getString());
                }
                String time = stringValue(o, "time", "");
                String sender = stringValue(o, "sender", "");
                int repeats = intValue(o, "repeats", 1);
                List<ChatMessageRecord> sources = readSourceRecords(o, message, serverKey, tab,
                    sender, migrateOldConsole);
                Set<String> categories = readCategories(o, tab);
                if (migrateOldConsole) categories = migrateConsoleCategory(categories, tab);
                ChatHistoryEntry entry = new ChatHistoryEntry(time, tab, sender,
                    "Восстановлено из локальной истории", repeats,
                    true, sources, categories, json);
                entry.componentJson = json;
                loaded.add(entry);
            }
        } catch (Exception e) {
            System.err.println("[ChatSorter] Could not load chat history: " + e.getMessage());
        }
        return loaded;
    }

    public static synchronized void save(String serverKey, List<ChatHistoryEntry> entries, boolean force) {
        if (serverKey == null || entries == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastSaveMillis < 2000) return;
        lastSaveMillis = now;

        int limit = ChatExperienceSettings.clamp(ChatExperienceSettings.restoreLimit);
        int start = Math.max(0, entries.size() - limit);
        ArrayList<HistoryRowSnapshot> snapshot = new ArrayList<>(entries.size() - start);
        for (int i = start; i < entries.size(); i++) {
            ChatHistoryEntry entry = entries.get(i);
            ArrayList<SourceSnapshot> sources = new ArrayList<>();
            for (ChatMessageRecord source : entry.sourceRecords()) {
                sources.add(new SourceSnapshot(source.id(), source.serverId(), source.sessionId(),
                    source.receivedAt(), source.senderId(), source.senderName(), source.rawText(),
                    source.message(), source.componentJson(), source.categories(), source.conversationId(),
                    source.repeatCount(), source.restored()));
            }
            snapshot.add(new HistoryRowSnapshot(entry.id, entry.serverId, entry.sessionId, entry.receivedAt,
                entry.senderId, entry.time, entry.tab.name(), entry.sender, entry.repeats, entry.message,
                sources.isEmpty() ? entry.message.getString() : sources.get(0).rawText(),
                entry.componentJson, entry.categories, List.copyOf(sources)));
        }

        CompletableFuture<Void> saved = new CompletableFuture<>();
        try {
            SAVER.execute(() -> {
                try {
                    writeSnapshot(file(serverKey), snapshot);
                    saved.complete(null);
                } catch (Exception e) {
                    System.err.println("[ChatSorter] Could not save chat history: " + e.getMessage());
                    saved.completeExceptionally(e);
                }
            });
        } catch (Exception e) {
            System.err.println("[ChatSorter] Could not queue chat history save: " + e.getMessage());
            return;
        }
        if (force) {
            try { saved.get(5, TimeUnit.SECONDS); }
            catch (Exception e) { System.err.println("[ChatSorter] Timed out waiting for chat history save."); }
        }
    }

    private static void writeSnapshot(Path target, List<HistoryRowSnapshot> entries) throws Exception {
        Files.createDirectories(DIRECTORY);
        JsonArray array = new JsonArray();
        for (HistoryRowSnapshot entry : entries) {
            JsonObject o = new JsonObject();
            o.addProperty("schemaVersion", 2);
            o.addProperty("time", entry.time());
            o.addProperty("tab", entry.tab());
            o.addProperty("sender", entry.sender());
            o.addProperty("repeats", entry.repeats());
            o.addProperty("plain", entry.plain());
            o.addProperty("id", entry.id().toString());
            o.addProperty("serverId", entry.serverId());
            o.addProperty("sessionId", entry.sessionId());
            o.addProperty("receivedAt", entry.receivedAt().toString());
            o.addProperty("senderId", entry.senderId());
            JsonArray categories = new JsonArray();
            for (String category : entry.categories()) categories.add(category);
            o.add("categories", categories);
            String componentJson = entry.componentJson().isBlank() ? serializeText(entry.message()) : entry.componentJson();
            if (!componentJson.isBlank()) o.addProperty("component", componentJson);
            JsonArray sourceArray = new JsonArray();
            for (SourceSnapshot source : entry.sources()) {
                JsonObject sourceJson = new JsonObject();
                sourceJson.addProperty("id", source.id().toString());
                sourceJson.addProperty("serverId", source.serverId());
                sourceJson.addProperty("sessionId", source.sessionId());
                sourceJson.addProperty("receivedAt", source.receivedAt().toString());
                sourceJson.addProperty("senderId", source.senderId());
                sourceJson.addProperty("senderName", source.senderName());
                sourceJson.addProperty("rawText", source.rawText());
                sourceJson.addProperty("conversationId", source.conversationId());
                sourceJson.addProperty("repeatCount", source.repeatCount());
                sourceJson.addProperty("restored", source.restored());
                JsonArray sourceCategories = new JsonArray();
                for (String category : source.categories()) sourceCategories.add(category);
                sourceJson.add("categories", sourceCategories);
                String sourceComponent = source.componentJson().isBlank() ? serializeText(source.message()) : source.componentJson();
                if (!sourceComponent.isBlank()) sourceJson.addProperty("component", sourceComponent);
                sourceArray.add(sourceJson);
            }
            o.add("sourceRecords", sourceArray);
            array.add(o);
        }
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, array.toString(), StandardCharsets.UTF_8);
        try { Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
    }

    public static synchronized void clear(String serverKey) {
        if (serverKey == null) return;
        CompletableFuture<Void> cleared = new CompletableFuture<>();
        try {
            SAVER.execute(() -> {
                try {
                    Files.deleteIfExists(file(serverKey));
                    cleared.complete(null);
                } catch (Exception e) {
                    cleared.completeExceptionally(e);
                }
            });
            cleared.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("[ChatSorter] Could not clear chat history: " + e.getMessage());
        }
    }

    private static List<ChatMessageRecord> readSourceRecords(JsonObject row, Text fallbackMessage,
                                                              String serverKey, ChatSorter.Tab tab,
                                                              String sender, boolean migrateOldConsole) {
        ArrayList<ChatMessageRecord> result = new ArrayList<>();
        if (row.has("sourceRecords") && row.get("sourceRecords").isJsonArray()) {
            for (JsonElement element : row.getAsJsonArray("sourceRecords")) {
                if (!element.isJsonObject()) continue;
                JsonObject source = element.getAsJsonObject();
                String raw = stringValue(source, "rawText", fallbackMessage.getString());
                String component = stringValue(source, "component", "");
                Text message = deserializeText(component);
                if (message == null) message = Text.literal(raw);
                ChatSorter.Tab sourceTab = migrateOldConsole && !ChatSorter.isPlayerCommandAudit(raw)
                    ? ChatSorter.classify(raw) : tab;
                Set<String> categories = readCategories(source, sourceTab);
                if (migrateOldConsole) categories = migrateConsoleCategory(categories, sourceTab);
                String senderName = stringValue(source, "senderName", sender);
                String senderId = stringValue(source, "senderId", senderName);
                result.add(new ChatMessageRecord(uuidValue(source, "id"),
                    stringValue(source, "serverId", serverKey),
                    stringValue(source, "sessionId", stringValue(row, "sessionId", "")),
                    instantValue(source, "receivedAt", instantValue(row, "receivedAt", Instant.now())),
                    senderId, senderName, raw, message, component, categories,
                    stringValue(source, "conversationId", ""),
                    intValue(source, "repeatCount", 1), true));
            }
        }
        if (!result.isEmpty()) return List.copyOf(result);

        String raw = stringValue(row, "plain", fallbackMessage.getString());
        String component = stringValue(row, "component", "");
        UUID id = uuidValue(row, "id");
        String senderId = stringValue(row, "senderId", sender);
        result.add(new ChatMessageRecord(id, stringValue(row, "serverId", serverKey),
            stringValue(row, "sessionId", ""), instantValue(row, "receivedAt", Instant.now()),
            senderId, sender, raw, fallbackMessage, component,
            migrateOldConsole ? migrateConsoleCategory(readCategories(row, tab), tab) : readCategories(row, tab),
            tab == ChatSorter.Tab.DM ? senderId : "", 1, true));
        return List.copyOf(result);
    }

    private static Set<String> readCategories(JsonObject object, ChatSorter.Tab fallback) {
        LinkedHashSet<String> categories = new LinkedHashSet<>();
        if (object.has("categories") && object.get("categories").isJsonArray()) {
            for (JsonElement category : object.getAsJsonArray("categories")) {
                if (category.isJsonPrimitive()) categories.add(category.getAsString());
            }
        }
        if (categories.isEmpty()) categories.add((fallback == null ? ChatSorter.Tab.CONSOLE : fallback).name());
        return Set.copyOf(categories);
    }

    private static Set<String> migrateConsoleCategory(Set<String> categories, ChatSorter.Tab destination) {
        LinkedHashSet<String> migrated = new LinkedHashSet<>(categories);
        if (destination != ChatSorter.Tab.CONSOLE) {
            migrated.remove(ChatSorter.Tab.CONSOLE.name());
            migrated.add(destination.name());
        }
        return Set.copyOf(migrated);
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        try { return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        try { return object.has(key) ? object.get(key).getAsInt() : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private static UUID uuidValue(JsonObject object, String key) {
        try { return object.has(key) ? UUID.fromString(object.get(key).getAsString()) : UUID.randomUUID(); }
        catch (Exception ignored) { return UUID.randomUUID(); }
    }

    private static Instant instantValue(JsonObject object, String key, Instant fallback) {
        try { return object.has(key) ? Instant.parse(object.get(key).getAsString()) : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    public static String serializeText(Text text) {
        try { return TextCodecs.CODEC.encodeStart(JsonOps.INSTANCE, text).getOrThrow().toString(); }
        catch (Exception ignored) { return ""; }
    }

    public static Text deserializeText(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonElement element = JsonParser.parseString(json);
            return TextCodecs.CODEC.parse(JsonOps.INSTANCE, element).getOrThrow();
        } catch (Exception ignored) { return null; }
    }

    private static Path file(String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return DIRECTORY.resolve(HexFormat.of().formatHex(hash) + ".json");
        } catch (Exception e) { return DIRECTORY.resolve("unknown.json"); }
    }

    private record HistoryRowSnapshot(UUID id, String serverId, String sessionId, Instant receivedAt,
                                      String senderId, String time, String tab, String sender, int repeats,
                                      Text message, String plain, String componentJson, Set<String> categories,
                                      List<SourceSnapshot> sources) { }

    private record SourceSnapshot(UUID id, String serverId, String sessionId, Instant receivedAt,
                                  String senderId, String senderName, String rawText, Text message,
                                  String componentJson, Set<String> categories, String conversationId,
                                  int repeatCount, boolean restored) { }

    private ChatHistoryStorage() { }
}
