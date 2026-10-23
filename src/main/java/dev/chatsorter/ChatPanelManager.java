package dev.chatsorter;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Properties;

/** Independent floating chat windows backed by the shared ChatSorter history. */
public final class ChatPanelManager {
    private static final int MAX_WINDOWS = 5;
    private static final int HEADER_HEIGHT = 19;
    private static final int MIN_WIDTH = 100;
    private static final int MIN_HEIGHT = 54;
    private static final ArrayList<Panel> PANELS = new ArrayList<>();
    private static final IdentityHashMap<Text, ChatHistoryEntry> ENTRIES = new IdentityHashMap<>();
    private static boolean rebuilding;
    private static String serverKey = "unknown";
    private static int nextId;
    private static Panel dragging;
    private static boolean resizing, resizeLeft, resizeRight, resizeTop, resizeBottom;
    private static double lastX, lastY;

    private static final class Panel {
        final int id;
        ChatHud hud;
        String filter = "ALL";
        int x = 24;
        int y;
        int width = 260;
        int height = 180;
        int unread;
        List<ChatHistoryEntry> entries = List.of();
        Panel(int id) { this.id = id; }
    }

    private ChatPanelManager() { }

    public static boolean enabled() { return !PANELS.isEmpty(); }
    public static boolean rebuilding() { return rebuilding; }
    public static int count() { return PANELS.size(); }
    public static boolean canAdd() { return PANELS.size() < MAX_WINDOWS; }
    public static boolean isPanelHud(ChatHud hud) { return panelFor(hud) != null; }
    public static ChatHistoryEntry entryForHudMessage(Text text) { return ENTRIES.get(text); }
    public static List<ChatHud> panelHuds() {
        ArrayList<ChatHud> result = new ArrayList<>();
        for (Panel panel : PANELS) if (panel.hud != null) result.add(panel.hud);
        return result;
    }
    public static ChatHud hudContaining(ChatHistoryEntry entry) {
        if (entry == null) return null;
        for (Panel panel : PANELS) if (panel.entries.contains(entry)) return panel.hud;
        return null;
    }
    public static Panel panelFor(ChatHud hud) {
        if (hud == null) return null;
        for (Panel panel : PANELS) if (panel.hud == hud) return panel;
        return null;
    }

    public static void loadServer(String key) {
        serverKey = key == null || key.isBlank() ? "unknown" : key;
        for (Panel panel : PANELS) if (panel.hud != null) panel.hud.clear(false);
        PANELS.clear();
        ENTRIES.clear();
        dragging = null;
        nextId = 0;
        Path file = configFile();
        if (!Files.isRegularFile(file)) return;
        try (InputStream in = Files.newInputStream(file)) {
            Properties p = new Properties();
            p.load(in);
            int count;
            if (p.containsKey("window.count")) {
                count = clamp(Integer.parseInt(p.getProperty("window.count", "0")), 0, MAX_WINDOWS);
            } else {
                // Migrate the old one/two-panel layout.
                count = Boolean.parseBoolean(p.getProperty("enabled", "false")) ? 2 : 0;
            }
            ChatPosition.x = Integer.parseInt(p.getProperty("x", Integer.toString(ChatPosition.x)));
            ChatPosition.y = Integer.parseInt(p.getProperty("y", Integer.toString(ChatPosition.y)));
            for (int i = 0; i < count; i++) {
                Panel panel = new Panel(nextId++);
                String prefix = "window." + i + ".";
                String legacy = "panel" + i + ".";
                panel.filter = p.getProperty(prefix + "filter", p.getProperty(legacy + "filter", "ALL"));
                panel.width = clamp(Integer.parseInt(p.getProperty(prefix + "width", p.getProperty(legacy + "width", "260"))), MIN_WIDTH, 1200);
                panel.height = clamp(Integer.parseInt(p.getProperty(prefix + "height", "180")), MIN_HEIGHT, 1000);
                panel.x = Integer.parseInt(p.getProperty(prefix + "x",
                    i == 1 ? Integer.toString(24 + panel.width + 16) : Integer.toString(24 + i * 28)));
                panel.y = Integer.parseInt(p.getProperty(prefix + "y", Integer.toString(-i * 18)));
                PANELS.add(panel);
            }
        } catch (Exception e) {
            PANELS.clear();
            System.err.println("[ChatSorter] Could not load floating chat windows: " + e.getMessage());
        }
    }

    private static Path configFile() {
        String id;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(serverKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format(java.util.Locale.ROOT, "%02x", hash[i]));
            id = hex.toString();
        } catch (Exception ignored) { id = "unknown"; }
        return MinecraftClient.getInstance().runDirectory.toPath().resolve("config").resolve("chatsorter")
            .resolve("panels").resolve(id + ".properties");
    }

    public static void save() {
        try {
            Path file = configFile();
            Files.createDirectories(file.getParent());
            Properties p = new Properties();
            p.setProperty("window.count", Integer.toString(PANELS.size()));
            p.setProperty("x", Integer.toString(ChatPosition.x));
            p.setProperty("y", Integer.toString(ChatPosition.y));
            for (int i = 0; i < PANELS.size(); i++) {
                Panel panel = PANELS.get(i);
                String prefix = "window." + i + ".";
                p.setProperty(prefix + "filter", panel.filter);
                p.setProperty(prefix + "width", Integer.toString(panel.width));
                p.setProperty(prefix + "height", Integer.toString(panel.height));
                p.setProperty(prefix + "x", Integer.toString(panel.x));
                p.setProperty(prefix + "y", Integer.toString(panel.y));
            }
            try (var out = Files.newOutputStream(file)) {
                p.store(out, "ChatSorter floating chat windows for " + serverKey);
            }
        } catch (Exception e) {
            System.err.println("[ChatSorter] Could not save floating chat windows: " + e.getMessage());
        }
    }

    public static boolean addWindow() {
        if (!canAdd()) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        int id = nextId++;
        Panel panel = new Panel(id);
        panel.x = 24 + PANELS.size() * 28;
        panel.y = -PANELS.size() * 18;
        panel.width = clamp((int)Math.round(ChatHud.getWidth(mc.options.getChatWidth().getValue())), MIN_WIDTH, 1200);
        panel.height = clamp(ChatHud.getHeight(mc.options.getChatHeightFocused().getValue()), MIN_HEIGHT, 1000);
        PANELS.add(panel);
        save();
        refresh();
        return true;
    }

    private static boolean closeWindow(Panel panel) {
        if (PANELS.size() <= 1) return false;
        if (panel.hud != null) panel.hud.clear(false);
        PANELS.remove(panel);
        if (dragging == panel) dragging = null;
        save();
        refresh();
        return true;
    }

    public static void refresh() {
        if (!enabled() || MinecraftClient.getInstance().player == null) return;
        rebuilding = true;
        try {
            ENTRIES.clear();
            for (Panel panel : PANELS) rebuild(panel);
        } finally {
            rebuilding = false;
        }
    }

    private static void rebuild(Panel panel) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (panel.hud == null) panel.hud = new ChatHud(mc);
        panel.hud.clear(false);
        panel.hud.resetScroll();
        int limit = ChatExperienceSettings.clampDisplayLimit(ChatExperienceSettings.chatDisplayLimit);
        ArrayDeque<ChatHistoryEntry> matches = new ArrayDeque<>(limit);
        for (ChatHistoryEntry entry : ChatSorter.entriesForPanels()) {
            if (!matches(panel, entry)) continue;
            if (matches.size() == limit) matches.removeFirst();
            matches.add(entry);
        }
        panel.entries = List.copyOf(matches);
        for (ChatHistoryEntry entry : matches) {
            Text text = entry.displayText();
            ENTRIES.put(text, entry);
            panel.hud.addMessage(text);
        }
    }

    private static boolean matches(Panel panel, ChatHistoryEntry entry) {
        String query = ChatSorter.searchQuery();
        if (!query.isEmpty() && !entry.message.getString().toLowerCase(java.util.Locale.ROOT).contains(query)) return false;
        if ("ALL".equals(panel.filter)) return true;
        if ("favorites".equals(panel.filter)) return ChatSorter.isBookmarked(entry);
        if (panel.filter.startsWith("custom:")) return entry.categories.contains(panel.filter.substring(7));
        try {
            ChatSorter.Tab tab = ChatSorter.Tab.valueOf(panel.filter);
            if (tab == ChatSorter.Tab.ALL) return true;
            boolean moved = ChatExperienceSettings.customFiltersEnabled && entry.categories.contains(ChatUserFilters.MOVED_MARKER);
            return (!moved && entry.categories.contains(tab.name()))
                || (ChatExperienceSettings.customFiltersEnabled && entry.categories.contains(panel.filter));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static void append(ChatHistoryEntry entry) {
        if (!enabled() || entry == null) return;
        rebuilding = true;
        try {
            for (Panel panel : PANELS) {
                if (!matches(panel, entry)) continue;
                if (panel.entries.size() >= ChatSorter.maxChatHistory() - 1) {
                    refresh();
                    return;
                }
                Text text = entry.displayText();
                ENTRIES.put(text, entry);
                panel.hud.addMessage(text);
                ArrayList<ChatHistoryEntry> next = new ArrayList<>(panel.entries);
                next.add(entry);
                panel.entries = List.copyOf(next);
                panel.unread = Math.min(9999, panel.unread + 1);
            }
        } finally {
            rebuilding = false;
        }
    }

    public static int width(ChatHud hud) {
        Panel panel = panelFor(hud);
        return panel == null ? -1 : panel.width;
    }
    public static int height(ChatHud hud) {
        Panel panel = panelFor(hud);
        return panel == null ? -1 : panel.height;
    }
    public static int visibleLineCount(ChatHud hud) {
        Panel panel = panelFor(hud);
        if (panel == null) return -1;
        double lineHeight = 9.0 * (MinecraftClient.getInstance().options.getChatLineSpacing().getValue() + 1.0);
        return Math.max(1, (int)Math.floor(panel.height / lineHeight));
    }
    public static int offsetX(ChatHud hud) {
        Panel panel = panelFor(hud);
        return panel == null ? ChatPosition.x : panel.x;
    }
    public static int offsetY(ChatHud hud) {
        Panel panel = panelFor(hud);
        return panel == null ? ChatPosition.y : panel.y;
    }

    public static ChatHud hudAt(double mouseX, double mouseY, int screenHeight) {
        if (!enabled()) return null;
        double scale = MinecraftClient.getInstance().options.getChatScale().getValue();
        if (scale <= 0) return null;
        for (int i = PANELS.size() - 1; i >= 0; i--) {
            Panel panel = PANELS.get(i);
            double bottom = screenHeight - 40 + panel.y;
            double top = bottom - panel.height * scale;
            if (mouseX >= panel.x && mouseX < panel.x + panel.width * scale
                    && mouseY >= top && mouseY <= bottom && panel.hud != null) return panel.hud;
        }
        return null;
    }

    public static double localX(ChatHud hud, double mouseX) {
        Panel panel = panelFor(hud);
        if (panel == null) return (mouseX - ChatPosition.x) / MinecraftClient.getInstance().options.getChatScale().getValue();
        return (mouseX - panel.x) / MinecraftClient.getInstance().options.getChatScale().getValue();
    }

    private static int headerY(Panel panel, int screenHeight) {
        double scale = MinecraftClient.getInstance().options.getChatScale().getValue();
        return (int)Math.round(screenHeight - 40 + panel.y - panel.height * scale - HEADER_HEIGHT - 2);
    }

    public static void render(DrawContext context, TextRenderer renderer, int currentTick, int mouseX, int mouseY,
                              boolean focused, boolean chatHidden) {
        if (!enabled()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        double scale = mc.options.getChatScale().getValue();
        for (Panel panel : PANELS) {
            if (panel.hud == null) panel.hud = new ChatHud(mc);
            panel.hud.render(context, renderer, currentTick, mouseX - panel.x, mouseY - panel.y, focused, chatHidden);
            double baseline = mc.getWindow().getScaledHeight() - 40 + panel.y;
            if (!focused) continue;
            int x = panel.x;
            int width = (int)Math.round(panel.width * scale);
            int top = headerY(panel, mc.getWindow().getScaledHeight());
            context.fill(x, top, x + width, top + HEADER_HEIGHT, 0xE622272D);
            context.fill(x, top, x + width, top + 2, ChatTheme.accent());
            String title = "Чат " + (PANELS.indexOf(panel) + 1) + " · " + filterLabel(panel.filter);
            String arrow = "‹";
            String close = "×";
            context.fill(x + 3, top + 3, x + 18, top + HEADER_HEIGHT - 2, 0x90383E44);
            context.drawCenteredTextWithShadow(renderer, arrow, x + 10, top + 6, 0xFFE2E5E8);
            context.drawTextWithShadow(renderer, renderer.trimToWidth(title, Math.max(10, width - 48)), x + 22, top + 6, 0xFFFFFFFF);
            int closeX = x + width - 19;
            int closeColor = PANELS.size() <= 1 ? 0xFF777B80 : 0xFFFF8D8D;
            context.fill(closeX, top + 3, x + width - 3, top + HEADER_HEIGHT - 2, 0x90383E44);
            context.drawCenteredTextWithShadow(renderer, close, closeX + 8, top + 6, closeColor);
            if (panel.unread > 0) {
                String unread = panel.unread > 999 ? "999+" : Integer.toString(panel.unread);
                context.drawTextWithShadow(renderer, unread, x + width - 43 - renderer.getWidth(unread), top + 6, 0xFFFF7777);
            }
            panel.unread = 0;
            double bodyTop = baseline - panel.height * scale;
            context.fill(x, (int)bodyTop, x + 2, (int)baseline, ChatTheme.bright());
        }
    }

    private static String filterLabel(String key) {
        if ("favorites".equals(key)) return "Избранное";
        if (key.startsWith("custom:")) return key.substring(7);
        try {
            return ChatSorter.Tab.valueOf(key).title;
        } catch (IllegalArgumentException ignored) {
            return key;
        }
    }

    public static boolean click(double mouseX, double mouseY, int screenWidth, int screenHeight) {
        if (mouseX >= 5 && mouseX < 132 && mouseY >= 47 && mouseY < 68) {
            if (canAdd()) addWindow();
            return true;
        }
        if (!enabled()) return false;
        double scale = MinecraftClient.getInstance().options.getChatScale().getValue();
        if (scale <= 0) return false;
        for (int i = PANELS.size() - 1; i >= 0; i--) {
            Panel panel = PANELS.get(i);
            int x = panel.x;
            int width = (int)Math.round(panel.width * scale);
            int bottom = (int)Math.round(screenHeight - 40 + panel.y);
            int top = (int)Math.round(bottom - panel.height * scale);
            int header = headerY(panel, screenHeight);
            int edge = 5;
            if (mouseX >= x - edge && mouseX <= x + width + edge
                    && mouseY >= top - edge && mouseY <= bottom + edge) {
                if (mouseX >= x + width - 22 && mouseY >= header && mouseY < header + HEADER_HEIGHT) {
                    if (PANELS.size() > 1) closeWindow(panel);
                    return true;
                }
                if (mouseX >= x + 2 && mouseX < x + 20 && mouseY >= header && mouseY < header + HEADER_HEIGHT) {
                    cycleFilter(panel, -1, screenWidth);
                    return true;
                }
                if (mouseX >= x + width - edge || mouseX < x + edge
                        || mouseY < top + edge || mouseY > bottom - edge) {
                    dragging = panel;
                    resizeLeft = Math.abs(mouseX - x) <= edge;
                    resizeRight = Math.abs(mouseX - (x + width)) <= edge;
                    resizeTop = Math.abs(mouseY - top) <= edge;
                    resizeBottom = Math.abs(mouseY - bottom) <= edge;
                    resizing = resizeLeft || resizeRight || resizeTop || resizeBottom;
                    lastX = mouseX; lastY = mouseY;
                    return true;
                }
                if (mouseY >= header && mouseY < header + HEADER_HEIGHT) {
                    cycleFilter(panel, 1, screenWidth);
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    private static void cycleFilter(Panel panel, int direction, int screenWidth) {
        List<ChatSorter.FilterToggle> options = ChatSorter.filterToggles(screenWidth);
        if (options.isEmpty()) return;
        int index = 0;
        for (int i = 0; i < options.size(); i++) if (options.get(i).key().equals(panel.filter)) { index = i; break; }
        panel.filter = options.get(Math.floorMod(index + direction, options.size())).key();
        panel.unread = 0;
        refresh();
        save();
    }

    public static boolean pollWindowDrag(int screenWidth, int screenHeight) {
        if (dragging == null) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        long window = mc.getWindow().getHandle();
        if (org.lwjgl.glfw.GLFW.glfwGetMouseButton(window, org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT)
                != org.lwjgl.glfw.GLFW.GLFW_PRESS) {
            dragging = null;
            resizing = false;
            save();
            return false;
        }
        double x = mc.mouse.getX() * mc.getWindow().getScaledWidth() / mc.getWindow().getWidth();
        double y = mc.mouse.getY() * mc.getWindow().getScaledHeight() / mc.getWindow().getHeight();
        double dx = x - lastX, dy = y - lastY;
        if (resizing) {
            double newWidth = dragging.width;
            double newHeight = dragging.height;
            if (resizeRight) newWidth += dx / mc.options.getChatScale().getValue();
            if (resizeLeft) newWidth -= dx / mc.options.getChatScale().getValue();
            if (resizeBottom) newHeight += dy / mc.options.getChatScale().getValue();
            if (resizeTop) newHeight -= dy / mc.options.getChatScale().getValue();
            dragging.width = clamp((int)Math.round(newWidth), MIN_WIDTH, Math.max(MIN_WIDTH, (screenWidth - 8) / Math.max(.01, mc.options.getChatScale().getValue())));
            dragging.height = clamp((int)Math.round(newHeight), MIN_HEIGHT, Math.max(MIN_HEIGHT, (screenHeight - 48) / Math.max(.01, mc.options.getChatScale().getValue())));
            if (resizeLeft) dragging.x += (int)Math.round((newWidth - dragging.width) * mc.options.getChatScale().getValue());
            if (resizeTop) dragging.y -= (int)Math.round((newHeight - dragging.height) * mc.options.getChatScale().getValue());
        } else {
            dragging.x += (int)Math.round(dx);
            dragging.y += (int)Math.round(dy);
        }
        double scale = mc.options.getChatScale().getValue();
        int w = (int)Math.round(dragging.width * scale);
        int h = (int)Math.round(dragging.height * scale);
        dragging.x = clamp(dragging.x, 4 - w + 48, screenWidth - 4);
        int baseline = screenHeight - 40 + dragging.y;
        baseline = clamp(baseline, 24, screenHeight - 4);
        dragging.y = baseline - (screenHeight - 40);
        lastX = x; lastY = y;
        return true;
    }

    public static boolean scroll(double mouseX, double mouseY, double amount, int screenHeight) {
        ChatHud hud = hudAt(mouseX, mouseY, screenHeight);
        if (hud == null) return false;
        hud.scroll((int)Math.signum(amount) * 3);
        return true;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
