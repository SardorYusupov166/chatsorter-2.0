package dev.chatsorter;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatSorter implements ClientModInitializer {
    public static final String VERSION = "2.0.0-alpha.17";
    private static final int MAX_CHAT_HISTORY = 16_384;
    private static final DateTimeFormatter MESSAGE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static int maxChatHistory() { return MAX_CHAT_HISTORY; }

    public enum Tab { NEARBY("Общий чат"), DM("ЛС"), SPY("Spy"), CONSOLE("Консоль"), ALL("Все");
        public final String title; Tab(String title) { this.title = title; }
    }
    private static final boolean[] enabledFilters = {true, true, true, true};
    private static boolean replaying = false;
    private static String searchQuery = "";
    private static String activeServerKey;
    private static String activeSessionId;
    private static ChatHistoryEntry lastReceivedEntry;
    private static int customFilterOffset;
    private static boolean favoritesEnabled;
    private static final IdentityHashMap<Text, ChatHistoryEntry> HUD_ENTRIES = new IdentityHashMap<>();
    private static final ChatMessageStore MESSAGE_STORE = new ChatMessageStore();
    public static final int[] unread = new int[Tab.values().length];

    private static final Pattern PRIVATE_MESSAGE = Pattern.compile(
        "^\\[(?:\\[~?[^\\]\\r\\n]+\\s+head\\])?~?([^\\]\\r\\n]{1,80})\\s*(->|→|<-|←)\\s*([^\\]\\r\\n]{1,80})\\]\\s*.+$");
    private static final Pattern PRIVATE_MESSAGE_OUTSIDE = Pattern.compile(
        "^\\[(?:\\[~?[^\\]\\r\\n]+\\s+head\\])?~?([^\\]\\r\\n]{1,80})\\]\\s*(->|→|<-|←)\\s*(?:я|мне|me|[A-Za-z0-9_]{3,16})\\s+.+$",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CHAT_NAME = Pattern.compile("^<([^>]{1,32})>\\s*.+$");
    private static final Pattern TAGGED_NAME = Pattern.compile("^\\[\\s*[GLSɢʟꜱ]\\s*\\]\\s*([A-Za-z0-9_]{1,32})\\s*[:»>]\\s*.+$",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ARROW_MESSAGE_SENDER = Pattern.compile(
        ".*(?:^|[^A-Za-z0-9_])([A-Za-z0-9_]{3,16})\\s*(?:->|→|<-|←)\\s*.+$");
    private static final Pattern LEADING_CHAT_TIMESTAMP = Pattern.compile("^\\s*\\[\\d{1,2}:\\d{2}:\\d{2}\\]\\s*");
    private static final Pattern URL = Pattern.compile("(?i)\\bhttps?://[^\\s<>]+\\b");
    private static final Pattern GAME_TAG = Pattern.compile("\\[\\s*([GLSɢʟꜱ])\\s*\\]", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static String searchQuery() { return searchQuery; }
    public static void setSearchQuery(String query) {
        String next = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (next.equals(searchQuery)) return;
        searchQuery = next;
        refreshChat();
    }
    public static boolean isSearching() { return !searchQuery.isEmpty(); }
    public static boolean filterEnabled(Tab tab) {
        if (tab == Tab.ALL) return allFiltersEnabled();
        return enabledFilters[tab.ordinal()];
    }
    private static boolean allFiltersEnabled() {
        for (boolean enabled : enabledFilters) if (!enabled) return false;
        return true;
    }
    private static String enabledFilterNames() {
        List<String> names = new ArrayList<>();
        for (Tab tab : Tab.values()) if (tab != Tab.ALL && filterEnabled(tab)) names.add(tab.name());
        return names.isEmpty() ? "NONE" : String.join(",", names);
    }
    public static List<ChatHistoryEntry> entries(Tab tab) { return MESSAGE_STORE.inCategory(tab); }
    /** Shared, canonical records used by independent chat panels. */
    public static List<ChatHistoryEntry> entriesForPanels() { return MESSAGE_STORE.all(); }
    /** Rows currently represented in the chat view, in chronological order. */
    public static List<ChatHistoryEntry> visibleEntriesForSelection() {
        return filteredEntriesForCurrentView();
    }

    private static List<ChatHistoryEntry> filteredEntriesForCurrentView() {
        int limit = ChatExperienceSettings.clampDisplayLimit(ChatExperienceSettings.chatDisplayLimit);
        List<ChatHistoryEntry> all = MESSAGE_STORE.all();
        int first = isSearching() ? 0 : Math.max(0, all.size() - limit);
        ArrayDeque<ChatHistoryEntry> recent = new ArrayDeque<>(limit);
        for (int index = first; index < all.size(); index++) {
            ChatHistoryEntry entry = all.get(index);
            boolean matchesSearch = !isSearching() || entry.message.getString().toLowerCase(Locale.ROOT).contains(searchQuery);
            if (matchesSearch && (isSearching() || visibleByFilters(entry))) {
                if (recent.size() == limit) recent.removeFirst();
                recent.addLast(entry);
            }
        }
        if (favoritesEnabled) {
            for (ChatMessageRecord source : ChatBookmarks.all()) {
                if (MESSAGE_STORE.findSource(source.id()) != null) continue;
                if (isSearching() && !source.rawText().toLowerCase(Locale.ROOT).contains(searchQuery)) continue;
                Tab tab = tabFrom(source.categories());
                ChatHistoryEntry fallback = new ChatHistoryEntry(source,
                    LocalDateTime.ofInstant(source.receivedAt(), java.time.ZoneId.systemDefault()).format(MESSAGE_TIME),
                    tab, "Закладка сохранена отдельно от истории", false);
                fallback.bookmarkFallback = true;
                if (recent.size() == limit) recent.removeFirst();
                recent.addLast(fallback);
            }
        }
        ArrayList<ChatHistoryEntry> result = new ArrayList<>(recent);
        result.sort(java.util.Comparator.comparing((ChatHistoryEntry entry) -> entry.receivedAt));
        return List.copyOf(result);
    }
    public static String currentServerKey() { return activeServerKey; }
    public record FilterToggle(String key, String label, boolean enabled) { }
    public static List<FilterToggle> filterToggles(int screenWidth) {
        List<FilterToggle> result = new ArrayList<>();
        for (Tab tab : Tab.values()) {
            if (ChatExperienceSettings.standardFilterVisible(tab.name()))
                result.add(new FilterToggle(tab.name(), tab.title, filterEnabled(tab)));
        }
        List<String> categories = ChatUserFilters.categories();
        int pageSize = customFilterPageSize(screenWidth);
        customFilterOffset = Math.min(customFilterOffset, Math.max(0, ((categories.size()-1)/pageSize)*pageSize));
        result.add(new FilterToggle("favorites", "Избранное", favoritesEnabled));
        for (String category : categories.stream().skip(customFilterOffset).limit(pageSize).toList())
            result.add(new FilterToggle("custom:" + category, category,
                ChatExperienceSettings.customFiltersEnabled && ChatUserFilters.categoryEnabled(category)));
        return result;
    }
    /** Full filter list for independent window pickers; its checkboxes are local to each window. */
    public static List<FilterToggle> panelFilterToggles() {
        List<FilterToggle> result = new ArrayList<>();
        result.add(new FilterToggle(Tab.ALL.name(), Tab.ALL.title, false));
        for (Tab tab : Tab.values()) {
            if (tab == Tab.ALL) continue;
            if (ChatExperienceSettings.standardFilterVisible(tab.name()))
                result.add(new FilterToggle(tab.name(), tab.title, false));
        }
        result.add(new FilterToggle("favorites", "Избранное", false));
        for (String category : ChatUserFilters.categories())
            result.add(new FilterToggle("custom:" + category, category, false));
        return result;
    }
    public static int customFilterPageSize(int screenWidth) { return Math.max(1, Math.min(12, (screenWidth - 160) / 70)); }
    public static void moveCustomFilterPage(int direction, int screenWidth) {
        int size=customFilterPageSize(screenWidth), total=ChatUserFilters.categories().size();
        int max=Math.max(0,((total-1)/size)*size);
        customFilterOffset=Math.max(0,Math.min(max,customFilterOffset+Integer.signum(direction)*size));
    }
    public static String customFilterPageLabel(int screenWidth) {
        int size=customFilterPageSize(screenWidth), total=ChatUserFilters.categories().size();
        if(total<=size) return "";
        return (customFilterOffset/size+1)+"/"+((total+size-1)/size);
    }
    public static void toggleFilterKey(String key) {
        if (key == null) return;
        if (key.equals("favorites")) { favoritesEnabled=!favoritesEnabled; refreshChat(); }
        else if (key.startsWith("custom:")) ChatUserFilters.toggleCategory(key.substring("custom:".length()));
        else try { toggleFilter(Tab.valueOf(key)); } catch (IllegalArgumentException ignored) { }
    }
    public static void refreshChatFromFilters() { refreshChat(); }

    private static void refreshChat() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        ChatHud hud = mc.inGameHud.getChatHud();
        replaying = true;
        HUD_ENTRIES.clear();
        try {
            hud.clear(false);
            for (ChatHistoryEntry entry : filteredEntriesForCurrentView()) {
                Text display = entry.displayText();
                HUD_ENTRIES.put(display, entry);
                hud.addMessage(display);
            }
            ChatPanelManager.refresh();
        } finally { replaying = false; }
    }

    @Override public void onInitializeClient() {
        ChatPosition.load();
        KeywordAlerts.load();
        ChatTheme.load();
        ChatBackgrounds.load();
        ChatExperienceSettings.load();
        ChatWorldRenderer.register();
        ChatUserFilters.load();
        AlertHotkey.load();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            AlertHotkey.tick(client);
            if (client.player != null && client.currentScreen == null && AlertHotkey.needsSetup()) AlertHotkey.prompt();
        });
        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            int unreadDm = unread[Tab.DM.ordinal()];
            if (unreadDm <= 0) return;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null || mc.options.hudHidden) return;
            String label = "ЛС: " + unreadDm + " непрочитанных";
            int textWidth = mc.textRenderer.getWidth(label);
            int right = context.getScaledWindowWidth() - 6;
            int top = mc.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen ? 29 : 7;
            context.fill(right - textWidth - 14, top, right, top + 18, 0xDD292D32);
            context.fill(right - textWidth - 14, top, right - textWidth - 11, top + 18, ChatTheme.bright());
            context.drawTextWithShadow(mc.textRenderer, label, right - textWidth - 7, top + 5, 0xFFFFFFFF);
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            String server = client.getCurrentServerEntry() == null ? "singleplayer_or_unknown" : client.getCurrentServerEntry().address;
            activeSessionId = UUID.randomUUID().toString();
            ChatSorterDiagnostics.startSession(server);
            client.execute(() -> {
                if (client.player == null) return;
                openServerHistory(server);
                ChatBookmarks.load(activeServerKey);
                refreshChat();
                client.inGameHud.getChatHud().addMessage(Text.literal("[ChatSorter] Мод успешно загружен!").styled(style -> style.withColor(0x55FF55)));
                client.inGameHud.getChatHud().addMessage(Text.literal("[ChatSorter] Режим RPG: "
                    + (ChatExperienceSettings.rpgModeEnabled ? "ВКЛ — мировые голограммы активны" : "ВЫКЛ — включите в настройках уведомлений → настройках чата"))
                    .styled(style -> style.withColor(ChatExperienceSettings.rpgModeEnabled ? 0xFF9BE8FF : 0xFFFFC56B)));
                client.inGameHud.getChatHud().addMessage(Text.literal("Автор: ").styled(style -> style.withColor(0xFFCC55))
                    .append(Text.literal("mlpgod").styled(style -> style.withColor(0xFFFFFF))));
                client.inGameHud.getChatHud().addMessage(Text.literal("Сервер: ").styled(style -> style.withColor(0xFFCC55))
                    .append(Text.literal("MigosMC").styled(style -> style.withColor(0xFFFFFF))));
                client.inGameHud.getChatHud().addMessage(Text.literal("ChatSorter " + VERSION + " | mlpgod - MigosMC").styled(style -> style.withColor(0xAAAAAA)));
            });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            saveHistory(true);
            ChatBookmarks.flush();
            ChatSorterDiagnostics.endSession();
            clearMemory();
            activeSessionId = null;
        });
    }

    public static void openServerHistory(String serverKey) {
        clearMemory();
        activeServerKey = serverKey == null ? "unknown" : serverKey.toLowerCase(Locale.ROOT);
        ChatPanelManager.loadServer(activeServerKey);
        if (ChatExperienceSettings.restoreHistory) {
            for (ChatHistoryEntry entry : ChatHistoryStorage.load(activeServerKey, ChatExperienceSettings.restoreLimit)) {
                addToMemory(entry);
            }
        }
        lastReceivedEntry = null;
        refreshChat();
    }

    public static boolean capture(Text message) { return capture(message, null, null); }
    public static boolean capture(Text message, MessageSignatureData signature, MessageIndicator indicator) {
        if (replaying || ChatPanelManager.rebuilding()) return true;
        String raw = message.getString();
        boolean internal = raw.startsWith("[ChatSorter]") || raw.startsWith("Автор: ")
            || raw.startsWith("Сервер: ") || raw.startsWith("ChatSorter " + VERSION + " |");
        if (internal) {
            String now = LocalDateTime.now().format(MESSAGE_TIME);
            ChatMessageRecord greetingRecord = new ChatMessageRecord(UUID.randomUUID(), activeServerKey,
                activeSessionId, Instant.now(), "chatsorter", "ChatSorter", raw, message, "",
                Set.of(Tab.NEARBY.name()), "", 1, false);
            ChatHistoryEntry greeting = new ChatHistoryEntry(greetingRecord, now, Tab.NEARBY,
                "internal greeting", false);
            HUD_ENTRIES.put(message, greeting);
            return filterEnabled(Tab.NEARBY);
        }
        Tab tab = classify(raw);
        String sender = findSender(raw, tab);
        ChatUserFilters.Match custom = ChatUserFilters.match(raw, sender, tab.name());
        boolean visible = isSearching()
            ? raw.toLowerCase(Locale.ROOT).contains(searchQuery)
            : (!custom.moved() && filterEnabled(tab)) || custom.categories().stream().anyMatch(ChatUserFilters::categoryEnabled);
        ChatSorterDiagnostics.record(message, tab.name(), enabledFilterNames(), visible);

        ChatHistoryEntry previous = lastReceivedEntry;
        if (ChatExperienceSettings.mergeRepeats && previous != null && !previous.restored
                && previous.tab == tab && !sender.isBlank() && previous.sender.equalsIgnoreCase(sender)
                && normalizeRepeatText(previous.message.getString()).equals(normalizeRepeatText(raw))) {
            previous.appendSource(sourceRecord(message, raw, tab, sender, custom.categories()));
            MESSAGE_STORE.addSource(previous, previous.sourceRecords().get(previous.sourceRecords().size() - 1));
            if (KeywordAlerts.notifyIfMatched(raw)) previous.alertTriggered = true;
            if (visible || isSearching()) refreshChat();
            else ChatPanelManager.refresh();
            saveHistory(false);
            return false;
        }

        boolean alertTriggered = KeywordAlerts.notifyIfMatched(raw);
        String now = LocalDateTime.now().format(MESSAGE_TIME);
        String tech = "category=" + tab.name() + "; sender=" + sender
            + "; signature=" + (signature == null ? "none" : signature)
            + "; indicator=" + (indicator == null || indicator.text() == null ? "none" : indicator.text().getString());
        ChatHistoryEntry entry = new ChatHistoryEntry(sourceRecord(message, raw, tab, sender, custom.categories()), now, tab, tech, false);
        entry.alertTriggered = alertTriggered;
        addToMemory(entry);
        lastReceivedEntry = entry;
        HUD_ENTRIES.put(message, entry);
        ChatPanelManager.append(entry);
        if (!visible) { unread[tab.ordinal()]++; unread[Tab.ALL.ordinal()]++; }
        saveHistory(false);
        if (isSearching()) return raw.toLowerCase(Locale.ROOT).contains(searchQuery);
        return visible;
    }

    private static void addToMemory(ChatHistoryEntry entry) {
        List<ChatHistoryEntry> evicted = MESSAGE_STORE.add(entry, MAX_CHAT_HISTORY);
        if (!evicted.isEmpty()) HUD_ENTRIES.values().removeIf(evicted::contains);
    }
    private static ChatMessageRecord sourceRecord(Text message, String raw, Tab tab, String sender, Set<String> customCategories) {
        String senderId = sender == null || sender.isBlank() ? "" : sender.strip().toLowerCase(Locale.ROOT);
        java.util.LinkedHashSet<String> categories = new java.util.LinkedHashSet<>();
        categories.add(tab.name()); categories.addAll(customCategories);
        return new ChatMessageRecord(UUID.randomUUID(), activeServerKey, activeSessionId, Instant.now(),
            senderId, sender, raw, message, "", categories, tab == Tab.DM ? senderId : "", 1, false);
    }
    private static boolean visibleByFilters(ChatHistoryEntry entry) {
        if (favoritesEnabled && ChatBookmarks.contains(entry)) return true;
        boolean moved = ChatExperienceSettings.customFiltersEnabled && entry.categories.contains(ChatUserFilters.MOVED_MARKER);
        if (!moved && filterEnabled(entry.tab)) return true;
        if (ChatExperienceSettings.customFiltersEnabled)
            for (String category : entry.categories) if (!category.startsWith("@") && ChatUserFilters.categoryEnabled(category)) return true;
        return false;
    }
    private static Tab tabFrom(Set<String> categories) {
        if (categories != null) for (String category : categories) try { return Tab.valueOf(category); } catch (IllegalArgumentException ignored) { }
        return Tab.CONSOLE;
    }
    public static boolean isBookmarked(ChatHistoryEntry entry) { return ChatBookmarks.contains(entry); }
    public static boolean toggleBookmark(ChatHistoryEntry entry) {
        boolean added=ChatBookmarks.toggle(entry); refreshChat(); return added;
    }
    private static void saveHistory(boolean force) { if (activeServerKey != null) ChatHistoryStorage.save(activeServerKey, MESSAGE_STORE.all(), force); }
    private static void clearMemory() {
        MESSAGE_STORE.clear();
        HUD_ENTRIES.clear();
        Arrays.fill(unread, 0);
        Arrays.fill(enabledFilters, true);
        favoritesEnabled = false;
        searchQuery = "";
        ChatSelection.clear();
        lastReceivedEntry = null;
    }
    public static void clearCurrentServerHistory() {
        ChatHistoryStorage.clear(activeServerKey);
        clearMemory();
        refreshChat();
    }

    public static ChatHistoryEntry entryForHudMessage(Text message) {
        ChatHistoryEntry entry = HUD_ENTRIES.get(message);
        return entry != null ? entry : ChatPanelManager.entryForHudMessage(message);
    }
    public static ChatHistoryEntry findMessage(UUID id) { return MESSAGE_STORE.find(id); }
    public static ChatMessageRecord findSourceMessage(UUID id) { return MESSAGE_STORE.findSource(id); }
    /** Clear the search and show the message in the complete chat timeline. */
    public static void showInConversation(ChatHistoryEntry entry) {
        if (!MESSAGE_STORE.contains(entry)) return;
        Arrays.fill(enabledFilters, true);
        searchQuery = "";
        Arrays.fill(unread, 0);
        refreshChat();
    }
    public static String copyText(Text text) {
        ChatHistoryEntry entry = HUD_ENTRIES.get(text);
        return entry == null ? text.getString() : copyText(entry);
    }
    public static String copyText(ChatHistoryEntry entry) { return "[" + entry.time + "] " + entry.message.getString(); }
    public static String copySender(ChatHistoryEntry entry) { return entry.sender; }
    public static String copyTechnical(ChatHistoryEntry entry) { return "ChatSorter " + VERSION + "\nID: " + entry.id + "\nВремя: " + entry.time + "\nКатегория: " + entry.tab.name()
        + "\nОтправитель: " + (entry.sender.isBlank() ? "не определён" : entry.sender)
        + "\nПовторы: " + entry.repeats + "\n" + entry.technical
        + "\nMinecraft Text JSON: " + componentJson(entry.message); }
    public static String copyFormatted(ChatHistoryEntry entry) {
        StringBuilder out = new StringBuilder();
        entry.message.visit((style, part) -> { out.append(legacyCodes(style)).append(part).append("§r"); return Optional.empty(); }, Style.EMPTY);
        if (entry.repeats > 1) out.append(" §7×").append(entry.repeats);
        return out.toString();
    }
    public static String copyLink(ChatHistoryEntry entry) {
        final String[] result = {""};
        entry.message.visit((style, part) -> {
            if (style.getClickEvent() instanceof ClickEvent.OpenUrl openUrl) result[0] = openUrl.uri().toString();
            return Optional.empty();
        }, Style.EMPTY);
        if (!result[0].isBlank()) return result[0];
        Matcher matcher = URL.matcher(entry.message.getString());
        return matcher.find() ? matcher.group().replaceAll("[),.!?]+$", "") : "";
    }
    private static String componentJson(Text text) {
        String json = ChatHistoryStorage.serializeText(text);
        return json.isBlank() ? text.getString() : json;
    }
    private static String legacyCodes(Style style) {
        StringBuilder b = new StringBuilder();
        if (style.getColor() != null) {
            int rgb = style.getColor().getRgb();
            String hex = String.format(Locale.ROOT, "%06x", rgb & 0xFFFFFF);
            b.append("§x"); for (char c : hex.toCharArray()) b.append('§').append(c);
        }
        if (style.isBold()) b.append("§l");
        if (style.isItalic()) b.append("§o");
        if (style.isUnderlined()) b.append("§n");
        if (style.isStrikethrough()) b.append("§m");
        if (style.isObfuscated()) b.append("§k");
        return b.toString();
    }
    private static String normalize(String text) { return text.replace('\u00a0', ' ').replaceAll("\\s+", " ").strip(); }
    private static String normalizeRepeatText(String text) {
        String withoutTimestamp = LEADING_CHAT_TIMESTAMP.matcher(text == null ? "" : text).replaceFirst("");
        return normalize(withoutTimestamp);
    }
    private static String findSender(String raw, Tab tab) {
        String s = raw.replace('\u00a0', ' ').replaceAll("§[0-9a-fk-orA-FK-OR]", "").strip();
        Matcher m = PRIVATE_MESSAGE.matcher(s);
        if (m.matches()) return (m.group(2).equals("<-") || m.group(2).equals("←") ? m.group(3) : m.group(1)).strip();
        m = PRIVATE_MESSAGE_OUTSIDE.matcher(s);
        if (m.matches()) return (m.group(2).equals("<-") || m.group(2).equals("←") ? "" : m.group(1).strip());
        m = CHAT_NAME.matcher(s); if (m.matches()) return m.group(1).strip();
        m = TAGGED_NAME.matcher(s); if (m.matches()) return m.group(1).strip();
        m = ARROW_MESSAGE_SENDER.matcher(s);
        if (m.matches()) return m.group(1).strip();
        if (tab == Tab.NEARBY) {
            int end = s.indexOf(':');
            Matcher tagMatcher = GAME_TAG.matcher(s);
            int tag = tagMatcher.find() ? tagMatcher.end() : -1;
            if (end > 0 && end < 100 && tag >= 0 && end > tag) return s.substring(tag, end).strip();
        }
        return "";
    }

    private static final Pattern GAME_TAG_CLASSIFIER = Pattern.compile("\\[\\s*([GLSɢʟꜱ])\\s*\\]", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    // MigosMC's command spy/audit lines have a stable timestamp + Cmd: prefix.
    // Server announcements and command responses are ordinary messages and must stay in General.
    private static final Pattern PLAYER_COMMAND_AUDIT = Pattern.compile("^\\[\\d{1,2}:\\d{2}:\\d{2}\\]\\s*Cmd\\s*:", Pattern.CASE_INSENSITIVE);
    static boolean isPlayerCommandAudit(String original) {
        String s = original.replace('\u00a0', ' ').replace('\u200b', ' ').replace('\u200c', ' ')
            .replace('\u200d', ' ').replaceAll("§[0-9a-fk-orA-FK-OR]", "").strip();
        return PLAYER_COMMAND_AUDIT.matcher(s).find();
    }
    static Tab classify(String original) {
        String s = original.replace('\u00a0', ' ').replace('\u200b', ' ').replace('\u200c', ' ')
            .replace('\u200d', ' ').replaceAll("§[0-9a-fk-orA-FK-OR]", "").strip();
        String lower = s.toLowerCase(Locale.ROOT);
        if (isPlayerCommandAudit(s)) return Tab.CONSOLE;
        if (!lower.startsWith("[console]") && !lower.startsWith("[консоль]")
                && (PRIVATE_MESSAGE.matcher(s).matches() || PRIVATE_MESSAGE_OUTSIDE.matcher(s).matches())) return Tab.DM;
        if (lower.matches("^\\s*\\[\\s*объявлени[ея]\\s*\\].*")) return Tab.NEARBY;
        if (lower.startsWith("[spy]") || lower.startsWith("[socialspy]") || lower.startsWith("[commandspy]")) return Tab.SPY;
        Matcher game = GAME_TAG_CLASSIFIER.matcher(s);
        if (game.find() && game.start() < 100) return Tab.NEARBY;
        if (lower.contains("[игрок]") || lower.contains("[рядом]") || lower.contains("[локальный]")) return Tab.NEARBY;
        if (lower.startsWith("<") || lower.startsWith("[чат]") || lower.startsWith("[chat]")) return Tab.NEARBY;
        // Unknown server output (announcements, plugin notices, command replies) belongs in General.
        return Tab.NEARBY;
    }
    public static void toggleFilter(Tab tab) {
        if (tab == Tab.ALL) {
            boolean enableAll = !allFiltersEnabled();
            Arrays.fill(enabledFilters, enableAll);
            if (enableAll) Arrays.fill(unread, 0);
        } else {
            enabledFilters[tab.ordinal()] = !enabledFilters[tab.ordinal()];
            if (enabledFilters[tab.ordinal()]) unread[tab.ordinal()] = 0;
            if (allFiltersEnabled()) Arrays.fill(unread, 0);
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        refreshChat();
    }
}
