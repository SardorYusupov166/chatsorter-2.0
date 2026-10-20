package dev.chatsorter;

import net.fabricmc.loader.api.FabricLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Read-only history of successfully played alerts from human-readable session logs. */
public final class NotificationHistory {
    public record Entry(String time, String keyword, String message, String sound, String volume) {}
    private static final Pattern ENTRY = Pattern.compile("(?m)^\\[([^]\\r\\n]+)] Звуковое уведомление отправлено\\s*\\RСлово или фраза: ([^\\r\\n]*)\\RСообщение: ([^\\r\\n]*)\\RЗвук: ([^\\r\\n]*)\\RГромкость: ([^\\r\\n]*)");
    private NotificationHistory() {}
    public static List<Entry> load() {
        List<Entry> out = new ArrayList<>();
        Path dir = FabricLoader.getInstance().getGameDir().resolve("config/chatsorter/notifications");
        if (!Files.isDirectory(dir)) return out;
        try (var paths = Files.list(dir)) {
            List<Path> files = paths.filter(p -> p.getFileName().toString().endsWith(".txt"))
                .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
            for (Path path : files) {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                Matcher m = ENTRY.matcher(content);
                List<Entry> perFile = new ArrayList<>();
                while (m.find()) perFile.add(new Entry(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5)));
                Collections.reverse(perFile);
                out.addAll(perFile);
                if (out.size() >= 5000) break;
            }
        } catch (Exception e) { System.err.println("[ChatSorter] Notification history: " + e); }
        return out.size() > 5000 ? List.copyOf(out.subList(0, 5000)) : List.copyOf(out);
    }
}
