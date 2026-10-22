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

/** Two independent views over the shared ChatSorter message store. */
public final class ChatPanelManager {
    private static final int PANEL_GAP = 8;
    private static final int HEADER_HEIGHT = 19;
    private static final int MIN_WIDTH = 90;
    private static final int MAX_WIDTH = 400;
    private static final Panel[] PANELS = {new Panel(0), new Panel(1)};
    private static final IdentityHashMap<Text, ChatHistoryEntry> ENTRIES = new IdentityHashMap<>();
    private static boolean enabled;
    private static boolean rebuilding;
    private static boolean draggingDivider;
    private static String serverKey = "unknown";

    private static final class Panel {
        final int index;
        ChatHud hud;
        String filter = "ALL";
        int width = 230;
        int unread;
        boolean pinned;
        List<ChatHistoryEntry> entries = List.of();
        Panel(int index) { this.index = index; }
    }

    private ChatPanelManager() { }

    public static boolean enabled() { return enabled; }
    public static boolean rebuilding() { return rebuilding; }
    public static boolean isPanelHud(ChatHud hud) { return panelFor(hud) != null; }
    public static ChatHistoryEntry entryForHudMessage(Text text) { return ENTRIES.get(text); }
    public static Panel panelFor(ChatHud hud) {
        if (hud == null) return null;
        for (Panel panel : PANELS) if (panel.hud == hud) return panel;
        return null;
    }

    public static void loadServer(String key) {
        serverKey = key == null || key.isBlank() ? "unknown" : key;
        enabled = false;
        for (Panel panel : PANELS) {
            panel.filter = "ALL"; panel.width = 230; panel.unread = 0; panel.pinned = false; panel.entries = List.of();
            if (panel.hud != null) panel.hud.clear(false);
        }
        Path file = configFile();
        if (!Files.isRegularFile(file)) return;
        try (InputStream in = Files.newInputStream(file)) {
            Properties p = new Properties(); p.load(in);
            enabled = Boolean.parseBoolean(p.getProperty("enabled", "false"));
            ChatPosition.x = Integer.parseInt(p.getProperty("x", Integer.toString(ChatPosition.x)));
            ChatPosition.y = Integer.parseInt(p.getProperty("y", Integer.toString(ChatPosition.y)));
            for (Panel panel : PANELS) {
                String prefix = "panel" + panel.index + ".";
                panel.filter = p.getProperty(prefix + "filter", "ALL");
                panel.width = clampWidth(Integer.parseInt(p.getProperty(prefix + "width", "230")));
                panel.pinned = Boolean.parseBoolean(p.getProperty(prefix + "pinned", "false"));
            }
        } catch (Exception e) {
            System.err.println("[ChatSorter] Could not load panel layout: " + e.getMessage());
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
            Path file = configFile(); Files.createDirectories(file.getParent());
            Properties p = new Properties(); p.setProperty("enabled", Boolean.toString(enabled));
            p.setProperty("x", Integer.toString(ChatPosition.x));
            p.setProperty("y", Integer.toString(ChatPosition.y));
            for (Panel panel : PANELS) {
                String prefix = "panel" + panel.index + ".";
                p.setProperty(prefix + "filter", panel.filter);
                p.setProperty(prefix + "width", Integer.toString(panel.width));
                p.setProperty(prefix + "pinned", Boolean.toString(panel.pinned));
            }
            try (var out = Files.newOutputStream(file)) { p.store(out, "ChatSorter two-panel layout for " + serverKey); }
        } catch (Exception e) { System.err.println("[ChatSorter] Could not save panel layout: " + e.getMessage()); }
    }

    public static void toggleMode() {
        enabled = !enabled; save(); refresh();
    }

    public static void refresh() {
        if (!enabled || MinecraftClient.getInstance().player == null) return;
        rebuilding = true;
        try {
            ENTRIES.clear();
            for (Panel panel : PANELS) rebuild(panel);
        } finally { rebuilding = false; }
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
            Text text = entry.displayText(); ENTRIES.put(text, entry); panel.hud.addMessage(text);
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
        } catch (IllegalArgumentException ignored) { return false; }
    }

    public static void append(ChatHistoryEntry entry) {
        if (!enabled || entry == null) return;
        rebuilding = true;
        try {
        for (Panel panel : PANELS) {
            if (matches(panel, entry)) {
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
            }
            else panel.unread = Math.min(9999, panel.unread + 1);
        }
        } finally { rebuilding = false; }
    }

    public static int width(ChatHud hud) {
        Panel panel = panelFor(hud);
        return panel == null ? -1 : panel.width;
    }

    public static int offsetX(ChatHud hud) {
        Panel panel = panelFor(hud);
        if (panel == null || panel.index == 0) return ChatPosition.x;
        double scale = MinecraftClient.getInstance().options.getChatScale().getValue();
        return ChatPosition.x + (int)Math.round((PANELS[0].width + PANEL_GAP) * scale);
    }

    public static ChatHud hudAt(double mouseX, double mouseY, int screenHeight) {
        if (!enabled) return null;
        double scale = MinecraftClient.getInstance().options.getChatScale().getValue();
        if (scale <= 0) return null;
        int baseY = screenHeight - 40 + ChatPosition.y;
        int panelHeight = (int)Math.round(ChatHud.getHeight(MinecraftClient.getInstance().options.getChatHeightFocused().getValue()) * scale);
        for (Panel panel : PANELS) {
            double left = ChatPosition.x + (panel.index == 0 ? 0 : (PANELS[0].width + PANEL_GAP) * scale);
            double right = left + panel.width * scale;
            if (mouseX >= left && mouseX < right && mouseY >= baseY - panelHeight && mouseY <= baseY && panel.hud != null)
                return panel.hud;
        }
        return null;
    }

    public static double localX(ChatHud hud, double mouseX) {
        Panel panel = panelFor(hud);
        if (panel == null) return (mouseX - ChatPosition.x) / MinecraftClient.getInstance().options.getChatScale().getValue();
        double scale = MinecraftClient.getInstance().options.getChatScale().getValue();
        double x = ChatPosition.x + (panel.index == 0 ? 0 : (PANELS[0].width + PANEL_GAP) * scale);
        return (mouseX - x) / scale;
    }

    private static double headerY(int screenHeight) {
        MinecraftClient mc = MinecraftClient.getInstance();
        double scale = mc.options.getChatScale().getValue();
        return screenHeight - 40 + ChatPosition.y
            - ChatHud.getHeight(mc.options.getChatHeightFocused().getValue()) * scale - HEADER_HEIGHT - 2;
    }

    public static void render(DrawContext context, TextRenderer renderer, int currentTick, int mouseX, int mouseY,
                              boolean focused, boolean chatHidden) {
        if (!enabled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        double scale = mc.options.getChatScale().getValue();
        int totalWidth = (int)Math.round((PANELS[0].width + PANELS[1].width + PANEL_GAP) * scale);
        ChatPosition.x = Math.max(-totalWidth + 60, Math.min(mc.getWindow().getScaledWidth() - totalWidth - 4, ChatPosition.x));
        ChatPosition.y = Math.max(-mc.getWindow().getScaledHeight() + 60, Math.min(mc.getWindow().getScaledHeight() - 50, ChatPosition.y));
        for (Panel panel : PANELS) {
            if (panel.hud == null) panel.hud = new ChatHud(mc);
            int x = ChatPosition.x + (panel.index == 0 ? 0 : (int)Math.round((PANELS[0].width + PANEL_GAP) * scale));
            panel.hud.render(context, renderer, currentTick, mouseX - x, mouseY - ChatPosition.y, focused, chatHidden);
            int w = (int)Math.round(panel.width * scale);
            int y = (int)Math.round(headerY(mc.getWindow().getScaledHeight()));
            context.fill(x, y, x + w, y + HEADER_HEIGHT, 0xE622272D);
            context.fill(x, y, x + w, y + 2, ChatTheme.accent());
            String title = (panel.index == 0 ? "Окно 1 · " : "Окно 2 · ") + filterLabel(panel.filter);
            String pin = panel.pinned ? "◆" : "◇";
            String unread = panel.unread > 0 ? "  " + (panel.unread > 999 ? "999+" : panel.unread) : "";
            context.drawTextWithShadow(renderer, renderer.trimToWidth(title, Math.max(10, w - 50)), x + 5, y + 6, 0xFFFFFFFF);
            context.drawTextWithShadow(renderer, pin + unread, x + w - renderer.getWidth(pin + unread) - 5, y + 6,
                panel.unread > 0 ? 0xFFFF7777 : 0xFFD4D9DD);
            if (focused) panel.unread = 0;
        }
        int dividerX = ChatPosition.x + (int)Math.round((PANELS[0].width + PANEL_GAP / 2.0) * scale);
        int bottom = mc.getWindow().getScaledHeight() - 40 + ChatPosition.y;
        int top = bottom - (int)Math.round(ChatHud.getHeight(mc.options.getChatHeightFocused().getValue()) * scale) - HEADER_HEIGHT;
        context.fill(dividerX - 1, top, dividerX + 1, bottom, mouseX >= dividerX - 4 && mouseX <= dividerX + 4 ? ChatTheme.bright() : 0xAAB8C0C8);
    }

    private static String filterLabel(String key) {
        if ("favorites".equals(key)) return "Избранное";
        if (key.startsWith("custom:")) return key.substring(7);
        try { return ChatSorter.Tab.valueOf(key).title; }
        catch (IllegalArgumentException ignored) { return key; }
    }

    /** Called from ChatScreen for the mode button, panel headers, and divider. */
    public static boolean click(double mouseX, double mouseY, int screenWidth, int screenHeight) {
        if (mouseX >= 5 && mouseX < 99 && mouseY >= 47 && mouseY < 68) { toggleMode(); return true; }
        if (!enabled) return false;
        double scale = MinecraftClient.getInstance().options.getChatScale().getValue();
        int dividerX = ChatPosition.x + (int)Math.round((PANELS[0].width + PANEL_GAP / 2.0) * scale);
        int bottom = screenHeight - 40 + ChatPosition.y;
        int top = bottom - (int)Math.round(ChatHud.getHeight(MinecraftClient.getInstance().options.getChatHeightFocused().getValue()) * scale) - HEADER_HEIGHT;
        if (mouseX >= dividerX - 5 && mouseX <= dividerX + 5 && mouseY >= top && mouseY <= bottom) {
            draggingDivider = true;
            return true;
        }
        int y = (int)Math.round(headerY(screenHeight));
        for (Panel panel : PANELS) {
            int x = ChatPosition.x + (panel.index == 0 ? 0 : (int)Math.round((PANELS[0].width + PANEL_GAP) * scale));
            int w = (int)Math.round(panel.width * scale);
            if (mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + HEADER_HEIGHT) continue;
            if (mouseX >= x + w - 44) {
                panel.pinned = !panel.pinned; save(); return true;
            }
            if (!panel.pinned) {
                List<ChatSorter.FilterToggle> options = ChatSorter.filterToggles(screenWidth);
                if (!options.isEmpty()) {
                    int index = 0;
                    for (int i = 0; i < options.size(); i++) if (options.get(i).key().equals(panel.filter)) { index = i; break; }
                    int direction = mouseX < x + w / 2 ? -1 : 1;
                    panel.filter = options.get(Math.floorMod(index + direction, options.size())).key();
                    panel.unread = 0; refresh(); save();
                }
            }
            return true;
        }
        return false;
    }

    public static boolean pollDividerDrag(int screenWidth) {
        if (!draggingDivider) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        long window = mc.getWindow().getHandle();
        if (org.lwjgl.glfw.GLFW.glfwGetMouseButton(window, org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT)
                != org.lwjgl.glfw.GLFW.GLFW_PRESS) {
            draggingDivider = false; save(); return false;
        }
        double mouseX = mc.mouse.getX() * mc.getWindow().getScaledWidth() / mc.getWindow().getWidth();
        double scale = mc.options.getChatScale().getValue();
        int total = PANELS[0].width + PANELS[1].width;
        int left = (int)Math.round((mouseX - ChatPosition.x) / scale - PANEL_GAP / 2.0);
        left = Math.max(MIN_WIDTH, Math.min(total - MIN_WIDTH, left));
        PANELS[0].width = clampWidth(left);
        PANELS[1].width = clampWidth(total - left);
        return true;
    }

    public static boolean scroll(double mouseX, double mouseY, double amount, int screenHeight) {
        ChatHud hud = hudAt(mouseX, mouseY, screenHeight);
        if (hud == null) return false;
        hud.scroll((int)Math.signum(amount) * 3);
        return true;
    }

    private static int clampWidth(int value) { return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, value)); }
}
