package dev.chatsorter;

import net.minecraft.client.MinecraftClient;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Local keyword allowlist. Matching happens on every incoming message, independent of the selected tab. */
public final class KeywordAlerts {
    private static final List<String> words = new ArrayList<>();
    private static final Path PATH = Paths.get("config", "chatsorter-keywords.txt");
    private static final Path SETTINGS = Paths.get("config", "chatsorter-alerts.properties");
    private static float volume = 1.0F;
    private static final String[] HIGHLIGHT_NAMES = {"Голубая", "Золотая", "Красная", "Зелёная", "Фиолетовая", "Бирюзовая", "Розовая", "Белая"};
    private static final int[] HIGHLIGHT_COLORS = {
        0x584F9EFF, 0x58FFC247, 0x58F24F5C, 0x584BCB78,
        0x588C5FF5, 0x583BC9BA, 0x58F075B1, 0x58FFFFFF
    };
    private static int highlightIndex = 1;
    private static final String[] LEGACY_SOUND_IDS = {"minecraft:entity.experience_orb.pickup", "minecraft:block.note_block.bell", "minecraft:block.note_block.pling", "minecraft:entity.player.levelup", "minecraft:ui.button.click", "minecraft:block.amethyst_block.chime", "minecraft:item.totem.use", "minecraft:block.portal.trigger"};
    private static String soundId = LEGACY_SOUND_IDS[0];
    private static List<String> soundIds = List.of();
    public static float volume() { return volume; }
    public static int highlightColor() { return HIGHLIGHT_COLORS[highlightIndex]; }
    public static int highlightColorAt(int index) { return HIGHLIGHT_COLORS[Math.floorMod(index, HIGHLIGHT_COLORS.length)]; }
    public static int highlightPreviewColor() { return 0xFF000000 | (highlightColor() & 0x00FFFFFF); }
    public static int highlightColorCount() { return HIGHLIGHT_COLORS.length; }
    public static String highlightColorName(int index) { return HIGHLIGHT_NAMES[Math.floorMod(index, HIGHLIGHT_NAMES.length)]; }
    public static int highlightColorIndex() { return highlightIndex; }
    public static void setHighlightColorIndex(int index) {
        highlightIndex = Math.floorMod(index, HIGHLIGHT_COLORS.length);
        saveSettings();
    }
    public static String soundId() { return soundId; }
    public static List<String> sounds() {
        if (soundIds.isEmpty()) {
            List<String> ids = new ArrayList<>();
            for (net.minecraft.util.Identifier id : net.minecraft.registry.Registries.SOUND_EVENT.getIds()) ids.add(id.toString());
            ids.sort(String.CASE_INSENSITIVE_ORDER);
            soundIds = List.copyOf(ids);
        }
        return soundIds;
    }
    public static void setVolume(float value) { volume = Math.max(0, Math.min(1, value)); saveSettings(); }
    public static void setSoundId(String id) {
        if (!sounds().contains(id)) return;
        soundId = id;
        saveSettings();
    }
    public static void preview() {
        if (volume <= 0) {
            ChatSorterDiagnostics.soundPreviewSkipped("громкость уведомлений установлена на 0%", volume, soundId);
            return;
        }
        IndependentNotificationAudio.play(soundId, volume, "предпрослушивание из настроек");
    }
    private static void saveSettings() {
        try {
            Files.createDirectories(SETTINGS.getParent());
            Properties props = new Properties();
            props.setProperty("volume", Float.toString(volume));
            props.setProperty("sound_id", soundId);
            props.setProperty("highlight_color", Integer.toString(highlightIndex));
            try (java.io.OutputStream out = Files.newOutputStream(SETTINGS)) { props.store(out, "ChatSorter notification sound"); }
        } catch (Exception e) { System.err.println("[ChatSorter] Cannot save alert settings: " + e); }
    }
    private KeywordAlerts() {}
    public static List<String> words() { return List.copyOf(words); }
    public static void load() {
        words.clear();
        try {
            if (Files.exists(SETTINGS)) {
                Properties props = new Properties();
                try (java.io.InputStream in = Files.newInputStream(SETTINGS)) { props.load(in); }
                volume = Math.max(0, Math.min(1, Float.parseFloat(props.getProperty("volume", "1.0"))));
                String savedId = props.getProperty("sound_id", "");
                if (savedId.isBlank()) {
                    int oldIndex = Math.floorMod(Integer.parseInt(props.getProperty("sound", "0")), LEGACY_SOUND_IDS.length);
                    savedId = LEGACY_SOUND_IDS[oldIndex];
                }
                if (sounds().contains(savedId)) soundId = savedId;
                highlightIndex = Math.floorMod(Integer.parseInt(props.getProperty("highlight_color", "1")), HIGHLIGHT_COLORS.length);
            }
        } catch (Exception e) { System.err.println("[ChatSorter] Cannot load alert settings: " + e); }
        try {
            if (Files.exists(PATH)) for (String line : Files.readAllLines(PATH, StandardCharsets.UTF_8)) {
                String word = line.strip();
                if (!word.isEmpty() && words.stream().noneMatch(w -> w.equalsIgnoreCase(word))) words.add(word);
            }
        } catch (Exception e) { System.err.println("[ChatSorter] Cannot load keywords: " + e); }
    }
    private static void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Files.write(PATH, words, StandardCharsets.UTF_8);
        } catch (Exception e) { System.err.println("[ChatSorter] Cannot save keywords: " + e); }
    }
    public static boolean add(String raw) {
        String word = raw == null ? "" : raw.strip();
        if (word.isEmpty() || word.length() > 80 || words.stream().anyMatch(w -> w.equalsIgnoreCase(word))) return false;
        words.add(word); save(); return true;
    }
    public static void remove(String word) { if (words.remove(word)) save(); }
    private static final java.util.regex.Pattern HEAD = java.util.regex.Pattern.compile("\\[[^\\]\\r\\n]{1,32}\\s+head\\]", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern CHAT_AUTHOR = java.util.regex.Pattern.compile(
        "^\\[[ʟɢGLgl]\\]\\s+.*?~\\s*([A-Za-z0-9_]{3,16})\\b.*?▶.*$",
        java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
    private static String normalize(String message) {
        return HEAD.matcher(message.replaceAll("§[0-9a-fk-orA-FK-OR]", "")
            .replace('\u00a0', ' ').replace('\u200b', ' ')).replaceAll("").strip();
    }
    private static String ownReason(String message, MinecraftClient mc) {
        if (mc.player == null) return "NO_PLAYER";
        String nick = mc.player.getName().getString();
        String s = normalize(message);
        if (s.startsWith("[ChatSorter]") || s.startsWith("Автор: ") || s.startsWith("ChatSorter ")) return "SUPPRESSED_INTERNAL";
        if ((s.startsWith("[⚡] На сервер зашёл ") || s.startsWith("Установлен режим игры "))
                && s.matches("(?s).*\\b" + java.util.regex.Pattern.quote(nick) + "\\b.*")) return "SUPPRESSED_SYSTEM";
        java.util.regex.Matcher chat = CHAT_AUTHOR.matcher(s);
        if (chat.matches() && chat.group(1).equalsIgnoreCase(nick)) return "SUPPRESSED_OWN";
        java.util.regex.Matcher dm = java.util.regex.Pattern.compile("^\\[\\s*~?\\s*([A-Za-z0-9_]{3,16})\\s*(?:->|→)\\s*[^\\]]+\\].*$").matcher(s);
        if (dm.matches() && dm.group(1).equalsIgnoreCase(nick)) return "SUPPRESSED_OWN";
        return "OTHER_OR_UNKNOWN";
    }
    public static boolean notifyIfMatched(String message) {
        if (words.isEmpty()) {
            ChatSorterDiagnostics.alertNoMatch(message, List.of());
            return false;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        String reason = ownReason(message, mc);
        String normalized = normalize(message);
        java.util.regex.Matcher chatLine = CHAT_AUTHOR.matcher(normalized);
        boolean isChat = chatLine.matches();
        String searchable = isChat && normalized.contains("▶") ? normalized.substring(normalized.indexOf("▶") + 1) : normalized;
        String lower = searchable.toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (lower.contains(word.toLowerCase(Locale.ROOT))) {
                boolean suppressed = reason.startsWith("SUPPRESSED_");
                String status = suppressed ? reason : (volume <= 0 || mc.player == null ? "MATCHED_SILENT" : "PLAYBACK_QUEUED");
                ChatSorterDiagnostics.alert(word, message, status, volume, soundId);
                ChatSorterDiagnostics.alertDecision(word, message, reason, status);
                boolean played = !suppressed && "PLAYBACK_QUEUED".equals(status);
                if (played) IndependentNotificationAudio.play(soundId, volume, "ключевое слово: " + word);
                return played;
            }
        }
        ChatSorterDiagnostics.alertNoMatch(message, List.copyOf(words));
        return false;
    }
}
