package dev.chatsorter;

import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.LinkedHashSet;
import java.util.Set;

public final class ChatExperienceSettings {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("chatsorter").resolve("experience.properties");
    public static boolean mergeRepeats = true;
    public static boolean restoreHistory = true;
    public static int restoreLimit = 2000;
    /** Number of most recent records to reprocess and display when chat filters change. */
    public static int chatDisplayLimit = 500;
    public static boolean customFiltersEnabled = true;
    public static boolean rpgModeEnabled;
    public static boolean rpgGlowEnabled = true;
    public static boolean rpgAnimationEnabled = true;
    public static boolean rpgParticlesEnabled = true;
    /** Follow the player's look yaw; when disabled, follow body yaw. Position is always avatar-anchored. */
    public static boolean rpgFollowViewEnabled = true;
    /** 0=center, 1=left, 2=right, 3=above, 4=below. */
    public static int rpgPanelPosition = 0;
    public static final Set<String> hiddenStandardFilters = new LinkedHashSet<>();

    public static void load() {
        if (!Files.isRegularFile(FILE)) return;
        try (InputStream in = Files.newInputStream(FILE)) {
            Properties p = new Properties(); p.load(in);
            mergeRepeats = Boolean.parseBoolean(p.getProperty("mergeRepeats", "true"));
            restoreHistory = Boolean.parseBoolean(p.getProperty("restoreHistory", "true"));
            restoreLimit = clamp(Integer.parseInt(p.getProperty("restoreLimit", "2000")));
            chatDisplayLimit = clampDisplayLimit(Integer.parseInt(p.getProperty("chatDisplayLimit", "500")));
            customFiltersEnabled = Boolean.parseBoolean(p.getProperty("customFiltersEnabled", "true"));
            rpgModeEnabled = Boolean.parseBoolean(p.getProperty("rpgModeEnabled", "false"));
            rpgGlowEnabled = Boolean.parseBoolean(p.getProperty("rpgGlowEnabled", "true"));
            rpgAnimationEnabled = Boolean.parseBoolean(p.getProperty("rpgAnimationEnabled", "true"));
            rpgParticlesEnabled = Boolean.parseBoolean(p.getProperty("rpgParticlesEnabled", "true"));
            rpgFollowViewEnabled = Boolean.parseBoolean(p.getProperty("rpgFollowViewEnabled", "true"));
            rpgPanelPosition = clampRpgPanelPosition(Integer.parseInt(p.getProperty("rpgPanelPosition", "0")));
            hiddenStandardFilters.clear();
            for (String key : p.getProperty("hiddenStandardFilters", "").split(",")) {
                if (!key.isBlank()) hiddenStandardFilters.add(key.strip());
            }
        } catch (Exception ignored) { }
    }
    public static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Properties p = new Properties();
            p.setProperty("mergeRepeats", Boolean.toString(mergeRepeats));
            p.setProperty("restoreHistory", Boolean.toString(restoreHistory));
            p.setProperty("restoreLimit", Integer.toString(clamp(restoreLimit)));
            p.setProperty("chatDisplayLimit", Integer.toString(clampDisplayLimit(chatDisplayLimit)));
            p.setProperty("customFiltersEnabled", Boolean.toString(customFiltersEnabled));
            p.setProperty("rpgModeEnabled", Boolean.toString(rpgModeEnabled));
            p.setProperty("rpgGlowEnabled", Boolean.toString(rpgGlowEnabled));
            p.setProperty("rpgAnimationEnabled", Boolean.toString(rpgAnimationEnabled));
            p.setProperty("rpgParticlesEnabled", Boolean.toString(rpgParticlesEnabled));
            p.setProperty("rpgFollowViewEnabled", Boolean.toString(rpgFollowViewEnabled));
            p.setProperty("rpgPanelPosition", Integer.toString(clampRpgPanelPosition(rpgPanelPosition)));
            p.setProperty("hiddenStandardFilters", String.join(",", hiddenStandardFilters));
            try (var out = Files.newOutputStream(FILE)) { p.store(out, "ChatSorter chat experience"); }
        } catch (Exception e) { System.err.println("[ChatSorter] Could not save experience settings: " + e.getMessage()); }
    }
    public static int clamp(int value) { return Math.max(50, Math.min(16_384, value)); }
    public static int clampDisplayLimit(int value) { return Math.max(100, Math.min(5_000, value)); }
    public static int clampRpgPanelPosition(int value) { return Math.max(0, Math.min(4, value)); }
    public static boolean standardFilterVisible(String key) { return !hiddenStandardFilters.contains(key); }
    public static void setStandardFilterVisible(String key, boolean visible) {
        if (key == null || key.isBlank()) return;
        if (visible) hiddenStandardFilters.remove(key); else hiddenStandardFilters.add(key);
        save();
    }
    public static void restoreStandardFilters() { hiddenStandardFilters.clear(); save(); }
    private ChatExperienceSettings() { }
}
