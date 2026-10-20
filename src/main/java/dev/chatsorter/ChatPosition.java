package dev.chatsorter;

import net.minecraft.client.MinecraftClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Position is stored in scaled GUI pixels relative to the vanilla bottom-left chat origin. */
public final class ChatPosition {
    public static int x = 0;
    public static int y = 0;
    private static final Path FILE = MinecraftClient.getInstance().runDirectory.toPath()
        .resolve("config").resolve("chatsorter-position.properties");
    private ChatPosition() {}
    public static void load() {
        try {
            if (!Files.exists(FILE)) return;
            Properties props = new Properties();
            try (var in = Files.newInputStream(FILE)) { props.load(in); }
            x = Integer.parseInt(props.getProperty("x", "0"));
            y = Integer.parseInt(props.getProperty("y", "0"));
        } catch (Exception ignored) { x = 0; y = 0; }
    }
    public static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Properties props = new Properties();
            props.setProperty("x", Integer.toString(x));
            props.setProperty("y", Integer.toString(y));
            try (var stream = Files.newOutputStream(FILE)) { props.store(stream, "ChatSorter chat position"); }
        } catch (Exception ignored) {}
    }
    public static void move(int dx, int dy, int width, int height) {
        x = Math.max(-width + 60, Math.min(width - 50, x + dx));
        y = Math.max(-height + 50, Math.min(height - 50, y + dy));
    }
    public static void reset() { x = 0; y = 0; save(); }
}
