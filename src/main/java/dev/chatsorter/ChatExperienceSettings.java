package dev.chatsorter;

import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ChatExperienceSettings {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("chatsorter").resolve("experience.properties");
    public static boolean mergeRepeats = true;
    public static boolean restoreHistory = true;
    public static int restoreLimit = 2000;
    public static boolean customFiltersEnabled = true;

    public static void load() {
        if (!Files.isRegularFile(FILE)) return;
        try (InputStream in = Files.newInputStream(FILE)) {
            Properties p = new Properties(); p.load(in);
            mergeRepeats = Boolean.parseBoolean(p.getProperty("mergeRepeats", "true"));
            restoreHistory = Boolean.parseBoolean(p.getProperty("restoreHistory", "true"));
            restoreLimit = clamp(Integer.parseInt(p.getProperty("restoreLimit", "2000")));
            customFiltersEnabled = Boolean.parseBoolean(p.getProperty("customFiltersEnabled", "true"));
        } catch (Exception ignored) { }
    }
    public static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Properties p = new Properties();
            p.setProperty("mergeRepeats", Boolean.toString(mergeRepeats));
            p.setProperty("restoreHistory", Boolean.toString(restoreHistory));
            p.setProperty("restoreLimit", Integer.toString(clamp(restoreLimit)));
            p.setProperty("customFiltersEnabled", Boolean.toString(customFiltersEnabled));
            try (var out = Files.newOutputStream(FILE)) { p.store(out, "ChatSorter chat experience"); }
        } catch (Exception e) { System.err.println("[ChatSorter] Could not save experience settings: " + e.getMessage()); }
    }
    public static int clamp(int value) { return Math.max(50, Math.min(16_384, value)); }
    private ChatExperienceSettings() { }
}
