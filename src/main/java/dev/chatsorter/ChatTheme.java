package dev.chatsorter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Shared accent palette used by chat windows and their controls. */
public final class ChatTheme {
    private static final Path PATH = Path.of("config", "chatsorter", "theme.properties");
    private static final String[] NAMES = {
        "Изумрудная", "Гранатовая", "Синяя", "Фиолетовая", "Графитовая",
        "Янтарная", "Бирюзовая", "Розовая",
        "КРД — дыхание солнца", "SAO — Айнкрад", "Магическая битва",
        "Паразит", "Соло левелинг"
    };
    private static final int[] ACCENTS = {
        0xE048604C, 0xE06B283C, 0xE02B537C, 0xE0593979,
        0xE044484E, 0xE0785527, 0xE02D6661, 0xE0783E62,
        0xE0446B42, 0xE0274266, 0xE04C346F, 0xE02E493A, 0xE0342D60
    };
    private static final int[] BRIGHTS = {
        0xFF91C79B, 0xFFCE7892, 0xFF82B7E9, 0xFFBE9AE8,
        0xFFB5BCC3, 0xFFFFC66D, 0xFF79D6C3, 0xFFFFA1C2,
        0xFFFFA45A, 0xFF83D7F5, 0xFFBA76EF, 0xFFA4E268, 0xFF9C8CFF
    };
    private static int index;

    public static void load() {
        try {
            if (!Files.exists(PATH)) return;
            Properties p = new Properties();
            try (var in = Files.newInputStream(PATH)) { p.load(in); }
            index = Math.floorMod(Integer.parseInt(p.getProperty("theme", "0")), NAMES.length);
        } catch (Exception ignored) { index = 0; }
    }

    public static int accent() { return ACCENTS[index]; }
    public static int bright() { return BRIGHTS[index]; }
    public static int text() { return BRIGHTS[index]; }
    public static String name() { return NAMES[index]; }
    public static int index() { return index; }
    public static int paletteCount() { return NAMES.length; }
    public static String paletteName(int palette) { return NAMES[Math.floorMod(palette, NAMES.length)]; }

    public static void setIndex(int palette) {
        index = Math.floorMod(palette, NAMES.length);
        save();
    }

    public static void next() { setIndex(index + 1); }

    private static void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Properties p = new Properties();
            p.setProperty("theme", Integer.toString(index));
            try (var out = Files.newOutputStream(PATH)) { p.store(out, "ChatSorter theme"); }
        } catch (Exception ignored) { }
    }

    private ChatTheme() { }
}
