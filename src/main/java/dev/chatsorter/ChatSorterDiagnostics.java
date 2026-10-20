package dev.chatsorter;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/** Temporary routing diagnostics. Local-only; no network requests. */
public final class ChatSorterDiagnostics {
    private static final Path FILE = FabricLoader.getInstance().getGameDir()
        .resolve("config").resolve("chatsorter").resolve("routing-diagnostics.log");
    private static final long LIMIT = 2_000_000;
    private static final long NOTIFICATION_LIMIT = 2_000_000;
    private static final AtomicLong COUNTER = new AtomicLong();
    private static boolean failed = false;
    private static Path sessionFile;
    private static Path notificationFile;
    private static boolean notificationFailed = false;
    private static boolean notificationLimitReached = false;
    private static boolean emptyKeywordsLogged = false;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss_SSS");
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    public static synchronized void startSession(String server) {
        sessionFile = null;
        notificationFile = null;
        notificationFailed = false;
        notificationLimitReached = false;
        emptyKeywordsLogged = false;
        failed = false;
        COUNTER.set(0);
        try {
            Path folder = FILE.getParent().resolve("sessions");
            Files.createDirectories(folder);
            String safeServer = server == null ? "unknown" : server.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (safeServer.length() > 60) safeServer = safeServer.substring(0, 60);
            String prefix = FILE_TIME.format(LocalDateTime.now()) + "_" + safeServer + "_";
            sessionFile = Files.createTempFile(folder, prefix, ".log");
            Path alertsFolder = FILE.getParent().resolve("notifications");
            Files.createDirectories(alertsFolder);
            notificationFile = Files.createTempFile(alertsFolder, prefix, ".txt");
            appendNotification("Начало сессии. Сервер: " + readable(server == null ? "неизвестен" : server));
            appendSession("SESSION_START server=" + escape(server == null ? "unknown" : server));
        } catch (IOException e) {
            failed = true;
            System.err.println("[ChatSorter] Session log failed: " + e.getMessage());
        }
    }
    public static synchronized void endSession() {
        appendSession("SESSION_END");
        appendNotification("Отключение от сервера. Конец сессии.");
        sessionFile = null;
        notificationFile = null;
    }
    public static synchronized void alertDecision(String keyword, String message, String senderReason, String status) {
        appendSession("ALERT keyword=" + readable(keyword) + " senderReason=" + senderReason
            + " notificationReason=" + status + " text=" + readable(message));
        appendNotification("Причина обработки сообщения: " + readable(senderReason)
            + "; результат фильтра: " + readable(status));
    }
    public static synchronized void alert(String keyword, String message, String result, float volume, String sound) {
        String description = switch (result) {
            case "PLAYED" -> "Звуковое уведомление отправлено";
            case "PLAYBACK_QUEUED" -> "Звуковое уведомление отправлено";
            case "MATCHED_SILENT" -> "Совпадение найдено, но звук не воспроизведён";
            case "SUPPRESSED_OWN" -> "Собственное сообщение — уведомление пропущено";
            case "SUPPRESSED_INTERNAL" -> "Служебное сообщение ChatSorter — уведомление пропущено";
            case "SUPPRESSED_SYSTEM" -> "Системное сообщение о вашем персонаже — уведомление пропущено";
            default -> "Результат: " + result;
        };
        appendNotification(description + "\n"
            + "Слово или фраза: " + readable(keyword) + "\n"
            + "Сообщение: " + readable(message) + "\n"
            + "Звук: " + readable(sound) + "\n"
            + "Громкость: " + Math.round(volume * 100) + "%\n"
            + "Этап: " + ("PLAYBACK_QUEUED".equals(result) ? "запуск звука поставлен в очередь; итог см. в записи AUDIO" : result));
    }
    public static synchronized void alertNoMatch(String message, java.util.List<String> configuredWords) {
        if (configuredWords == null || configuredWords.isEmpty()) {
            if (emptyKeywordsLogged) return;
            emptyKeywordsLogged = true;
            appendNotification("Звук не запускался: список ключевых слов пуст. Добавьте слова в настройках уведомлений.");
            return;
        }
        appendNotification("NO_KEYWORD_MATCH: ни одно настроенное слово не найдено. Список: "
            + readable(String.join(", ", configuredWords)) + "; сообщение: " + readable(message));
    }
    public static synchronized void soundPlayback(String context, String sound, String result, String detail) {
        appendNotification("AUDIO " + readable(result) + "; источник: " + readable(context)
            + "; звук: " + readable(sound) + (detail == null || detail.isBlank() ? "" : "; подробности: " + readable(detail)));
    }
    public static synchronized void soundPreviewSkipped(String reason, float volume, String sound) {
        appendNotification("Предпрослушивание не запущено: " + readable(reason) + "; звук: "
            + readable(sound) + "; громкость: " + Math.round(volume * 100) + "%");
    }
    private static String readable(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ').replace('\n', ' ');
    }
    private static void appendNotification(String entry) {
        if (notificationFailed) return;
        try {
            if (notificationFile == null) {
                Path folder = FILE.getParent().resolve("notifications");
                Files.createDirectories(folder);
                String prefix = FILE_TIME.format(LocalDateTime.now()) + "_no-session_";
                notificationFile = Files.createTempFile(folder, prefix, ".txt");
            }
            String block = "[" + LOCAL_TIME.format(LocalDateTime.now()) + "] " + entry
                + System.lineSeparator() + System.lineSeparator();
            if (Files.size(notificationFile) + block.getBytes(StandardCharsets.UTF_8).length > NOTIFICATION_LIMIT) {
                if (!notificationLimitReached) {
                    notificationLimitReached = true;
                    Files.writeString(notificationFile, "[" + LOCAL_TIME.format(LocalDateTime.now())
                        + "] Лог достиг ограничения 2 МБ; остальные записи этой сессии пропущены.\n",
                        StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                }
                notificationFailed = true;
                return;
            }
            Files.writeString(notificationFile, block, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            notificationFailed = true;
            System.err.println("[ChatSorter] Notification log failed: " + e.getMessage());
        }
    }
    private static void appendSession(String event) {
        if (failed || sessionFile == null) return;
        try {
            Files.writeString(sessionFile, "time=" + LOCAL_TIME.format(LocalDateTime.now()) + " " + event + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            failed = true;
            System.err.println("[ChatSorter] Session logging disabled: " + e.getMessage());
        }
    }
    private ChatSorterDiagnostics() {}

    public static synchronized void record(Text message, String classified, String selected, boolean visible) {
        if (failed) return;
        try {
            Files.createDirectories(FILE.getParent());
            if (Files.exists(FILE) && Files.size(FILE) > LIMIT) {
                Files.move(FILE, FILE.resolveSibling("routing-diagnostics.previous.log"),
                    StandardCopyOption.REPLACE_EXISTING);
            }
            String raw = message.getString();
            StringBuilder cps = new StringBuilder();
            raw.codePoints().limit(45).forEach(cp -> cps.append(String.format("U+%04X ", cp)));
            String line = "time=" + Instant.now() + " id=" + COUNTER.incrementAndGet()
                + " route=" + classified + " selected=" + selected + " visible=" + visible
                + " text=" + escape(raw) + " prefixCodepoints=" + cps + System.lineSeparator();
            appendSession(line.stripTrailing());
            Files.writeString(FILE, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            failed = true;
            System.err.println("[ChatSorter] Diagnostics disabled: " + e.getMessage());
        }
    }
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\r", "\\r")
            .replace("\n", "\\n").replace("\t", "\\t");
    }
}
