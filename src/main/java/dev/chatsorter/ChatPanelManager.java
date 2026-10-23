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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Independent floating chat windows backed by the shared ChatSorter history. */
public final class ChatPanelManager {
    public static final int BASE_CHAT_ID = -2;
    private static final int MAX_WINDOWS = 5;
    private static final int HEADER_HEIGHT = 19;
    private static final int MIN_WIDTH = 100;
    private static final int MIN_HEIGHT = 54;
    private static final ArrayList<Panel> PANELS = new ArrayList<>();
    private static final IdentityHashMap<Text, ChatHistoryEntry> ENTRIES = new IdentityHashMap<>();
    private static boolean rebuilding;
    private static String serverKey = "unknown";
    private static String baseBackground = "none";
    private static int basePalette;
    private static int baseDarkening = 52;
    private static int nextId;
    private static Panel dragging;
    private static boolean resizing, resizeLeft, resizeRight, resizeTop, resizeBottom;
    private static double lastX, lastY;

    private static final class Panel {
        final int id;
        ChatHud hud;
        final LinkedHashSet<String> filters = new LinkedHashSet<>();
        boolean filterPopup;
        int filterPopupScroll;
        String background = "none";
        int palette;
        int darkening = 52;
        int x = 24;
        int y;
        int width = 260;
        int height = 180;
        int unread;
        List<ChatHistoryEntry> entries = List.of();
        Panel(int id) {
            this.id = id;
            filters.add("ALL");
            background = baseBackground;
            palette = basePalette;
            darkening = baseDarkening;
        }
    }

    private ChatPanelManager() { }

    public static boolean enabled() { return !PANELS.isEmpty(); }
    public static boolean rebuilding() { return rebuilding; }
    /** Includes the built-in Minecraft chat, which is always the first chat. */
    public static int count() { return 1 + PANELS.size(); }
    public static boolean canAdd() { return count() < MAX_WINDOWS; }
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
    public static String background(ChatHud hud) {
        Panel panel = panelFor(hud);
        return panel == null ? baseBackground : panel.background;
    }
    public static int palette(ChatHud hud) {
        Panel panel = panelFor(hud);
        return panel == null ? basePalette : panel.palette;
    }
    public static int darkening(ChatHud hud) {
        Panel panel = panelFor(hud);
        return panel == null ? baseDarkening : panel.darkening;
    }
    public static int id(ChatHud hud) {
        Panel panel = panelFor(hud);
        return panel == null ? -1 : panel.id;
    }
    private static Panel panelById(int id) {
        for (Panel panel : PANELS) if (panel.id == id) return panel;
        return null;
    }
    public static String background(int id) { Panel p = panelById(id); return id == BASE_CHAT_ID ? baseBackground : p == null ? "none" : p.background; }
    public static int palette(int id) { Panel p = panelById(id); return id == BASE_CHAT_ID ? basePalette : p == null ? ChatTheme.index() : p.palette; }
    public static int darkening(int id) { Panel p = panelById(id); return id == BASE_CHAT_ID ? baseDarkening : p == null ? 52 : p.darkening; }
    public static void setBackground(int id, String value) {
        Panel p = panelById(id);
        if (value == null || (p == null && id != BASE_CHAT_ID)) return;
        if (id == BASE_CHAT_ID) {
            baseBackground = value;
            ChatBackgrounds.requestTextureForPanel(value);
            ChatBackgrounds.Template template = ChatBackgrounds.templateForPanel(value);
            if (template != null) basePalette = template.palette();
            save();
            return;
        }
        p.background = value;
        ChatBackgrounds.requestTextureForPanel(value);
        ChatBackgrounds.Template template = ChatBackgrounds.templateForPanel(value);
        if (template != null) p.palette = template.palette();
        save();
    }
    public static void setPalette(int id, int value) {
        Panel p = panelById(id);
        int palette = Math.floorMod(value, ChatTheme.paletteCount());
        if (id == BASE_CHAT_ID) { basePalette = palette; save(); }
        else if (p != null) { p.palette = palette; save(); }
    }
    public static void setDarkening(int id, int value) {
        Panel p = panelById(id);
        int darkness = clamp(value, 0, 85);
        if (id == BASE_CHAT_ID) { baseDarkening = darkness; save(); }
        else if (p != null) { p.darkening = darkness; save(); }
    }
    private static boolean selected(Panel panel, String key) {
        return panel.filters.contains(key);
    }
    private static String filterSummary(Panel panel) {
        if (panel.filters.contains("ALL")) return "Все";
        return "Фильтры: " + panel.filters.size();
    }
    private static void togglePanelFilter(Panel panel, String key) {
        if ("ALL".equals(key)) {
            panel.filters.clear();
            panel.filters.add("ALL");
        } else {
            panel.filters.remove("ALL");
            if (!panel.filters.add(key)) panel.filters.remove(key);
            if (panel.filters.isEmpty()) panel.filters.add("ALL");
        }
        panel.unread = 0;
        refresh();
        save();
    }

    public static void loadServer(String key) {
        serverKey = key == null || key.isBlank() ? "unknown" : key;
        for (Panel panel : PANELS) if (panel.hud != null) panel.hud.clear(false);
        PANELS.clear();
        ENTRIES.clear();
        dragging = null;
        nextId = 0;
        baseBackground = ChatBackgrounds.selectedId();
        basePalette = ChatTheme.index();
        baseDarkening = ChatBackgrounds.darkening();
        Path file = configFile();
        if (!Files.isRegularFile(file)) return;
        try (InputStream in = Files.newInputStream(file)) {
            Properties p = new Properties();
            p.load(in);
            int count;
            if (p.containsKey("window.count")) {
                count = clamp(Integer.parseInt(p.getProperty("window.count", "0")), 0, MAX_WINDOWS - 1);
            } else {
                // The vanilla Minecraft chat is always the first chat.
                count = Boolean.parseBoolean(p.getProperty("enabled", "false")) ? 1 : 0;
            }
            ChatPosition.x = Integer.parseInt(p.getProperty("x", Integer.toString(ChatPosition.x)));
            ChatPosition.y = Integer.parseInt(p.getProperty("y", Integer.toString(ChatPosition.y)));
            baseBackground = p.getProperty("main.background", baseBackground);
            if (!"none".equals(baseBackground) && ChatBackgrounds.displayName(baseBackground).equals(baseBackground)) baseBackground = "none";
            basePalette = clamp(Integer.parseInt(p.getProperty("main.palette", Integer.toString(basePalette))), 0, ChatTheme.paletteCount() - 1);
            baseDarkening = clamp(Integer.parseInt(p.getProperty("main.darkening", Integer.toString(baseDarkening))), 0, 85);
            for (int i = 0; i < count; i++) {
                Panel panel = new Panel(nextId++);
                String prefix = "window." + i + ".";
                String legacy = "panel" + i + ".";
                String savedFilters = p.getProperty(prefix + "filters", p.getProperty(prefix + "filter", p.getProperty(legacy + "filter", "ALL")));
                panel.filters.clear();
                for (String filter : savedFilters.split(";")) if (!filter.isBlank()) panel.filters.add(filter);
                if (panel.filters.isEmpty()) panel.filters.add("ALL");
                if (panel.filters.contains("ALL")) { panel.filters.clear(); panel.filters.add("ALL"); }
                panel.width = clamp(Integer.parseInt(p.getProperty(prefix + "width", p.getProperty(legacy + "width", "260"))), MIN_WIDTH, 1200);
                panel.height = clamp(Integer.parseInt(p.getProperty(prefix + "height", "180")), MIN_HEIGHT, 1000);
                panel.background = p.getProperty(prefix + "background", ChatBackgrounds.selectedId());
                if (!"none".equals(panel.background) && ChatBackgrounds.displayName(panel.background).equals(panel.background)) panel.background = "none";
                panel.palette = clamp(Integer.parseInt(p.getProperty(prefix + "palette", Integer.toString(ChatTheme.index()))), 0, ChatTheme.paletteCount() - 1);
                panel.darkening = clamp(Integer.parseInt(p.getProperty(prefix + "darkening", Integer.toString(ChatBackgrounds.darkening()))), 0, 85);
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
            p.setProperty("main.background", baseBackground);
            p.setProperty("main.palette", Integer.toString(basePalette));
            p.setProperty("main.darkening", Integer.toString(baseDarkening));
            for (int i = 0; i < PANELS.size(); i++) {
                Panel panel = PANELS.get(i);
                String prefix = "window." + i + ".";
                p.setProperty(prefix + "filters", String.join(";", panel.filters));
                p.setProperty(prefix + "width", Integer.toString(panel.width));
                p.setProperty(prefix + "height", Integer.toString(panel.height));
                p.setProperty(prefix + "x", Integer.toString(panel.x));
                p.setProperty(prefix + "y", Integer.toString(panel.y));
                p.setProperty(prefix + "background", panel.background);
                p.setProperty(prefix + "palette", Integer.toString(panel.palette));
                p.setProperty(prefix + "darkening", Integer.toString(panel.darkening));
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
        if (PANELS.isEmpty()) return false;
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
        if (panel.filters.contains("ALL")) return true;
        for (String filter : panel.filters) {
            if ("favorites".equals(filter) && ChatSorter.isBookmarked(entry)) return true;
            if (filter.startsWith("custom:") && entry.categories.contains(filter.substring(7))) return true;
            try {
                ChatSorter.Tab tab = ChatSorter.Tab.valueOf(filter);
                if (tab == ChatSorter.Tab.ALL) return true;
                boolean moved = ChatExperienceSettings.customFiltersEnabled && entry.categories.contains(ChatUserFilters.MOVED_MARKER);
                if ((!moved && entry.categories.contains(tab.name()))
                    || (ChatExperienceSettings.customFiltersEnabled && entry.categories.contains(filter))) return true;
            } catch (IllegalArgumentException ignored) { }
        }
        return false;
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
            context.fill(x, top, x + width, top + 2, ChatTheme.accent(panel.palette));
            String title = "Чат " + (PANELS.indexOf(panel) + 2) + " · " + filterSummary(panel);
            String close = "×";
            context.fill(x + 3, top + 3, x + 24, top + HEADER_HEIGHT - 2, 0x90383E44);
            context.drawCenteredTextWithShadow(renderer, "▾", x + 13, top + 6, 0xFFE2E5E8);
            context.drawTextWithShadow(renderer, renderer.trimToWidth(title, Math.max(10, width - 72)), x + 28, top + 6, 0xFFFFFFFF);
            int settingsX = x + width - 39;
            context.fill(settingsX, top + 3, settingsX + 16, top + HEADER_HEIGHT - 2, 0x90383E44);
            context.drawCenteredTextWithShadow(renderer, "⚙", settingsX + 8, top + 6, ChatTheme.bright(panel.palette));
            int closeX = x + width - 19;
            int closeColor = 0xFFFF8D8D;
            context.fill(closeX, top + 3, x + width - 3, top + HEADER_HEIGHT - 2, 0x90383E44);
            context.drawCenteredTextWithShadow(renderer, close, closeX + 8, top + 6, closeColor);
            if (panel.filterPopup) renderFilterPopup(context, renderer, panel, x, top, mc.getWindow().getScaledHeight(), mouseX, mouseY);
            if (panel.unread > 0) {
                String unread = panel.unread > 999 ? "999+" : Integer.toString(panel.unread);
                context.drawTextWithShadow(renderer, unread, x + width - 43 - renderer.getWidth(unread), top + 6, 0xFFFF7777);
            }
            panel.unread = 0;
            double bodyTop = baseline - panel.height * scale;
            context.fill(x, (int)bodyTop, x + 2, (int)baseline, ChatTheme.bright(panel.palette));
        }
    }

    private static int popupX(Panel panel) { return panel.x + 4; }
    private static int popupY(Panel panel, int screenHeight) { return headerY(panel, screenHeight) + HEADER_HEIGHT; }
    private static int popupRowHeight() { return 15; }
    private static int popupVisibleRows(Panel panel, int screenHeight) {
        return Math.max(1, Math.min(ChatSorter.panelFilterToggles().size(), (screenHeight - popupY(panel, screenHeight) - 8) / popupRowHeight()));
    }
    private static void renderFilterPopup(DrawContext context, TextRenderer renderer, Panel panel, int x, int top,
                                          int screenHeight, int mouseX, int mouseY) {
        List<ChatSorter.FilterToggle> options = ChatSorter.panelFilterToggles();
        int rows = popupVisibleRows(panel, screenHeight);
        panel.filterPopupScroll = clamp(panel.filterPopupScroll, 0, Math.max(0, options.size() - rows));
        int px = popupX(panel), py = top + HEADER_HEIGHT, pw = Math.max(150, Math.min(210, panel.width - 8));
        context.fill(px, py, px + pw, py + rows * popupRowHeight() + 3, 0xF0181C21);
        context.fill(px, py, px + pw, py + 1, ChatTheme.bright(panel.palette));
        context.enableScissor(px, py + 1, px + pw, py + rows * popupRowHeight() + 2);
        for (int i = 0; i < rows; i++) {
            int index = panel.filterPopupScroll + i;
            ChatSorter.FilterToggle option = options.get(index);
            int ry = py + 2 + i * popupRowHeight();
            boolean checked = selected(panel, option.key());
            if (mouseX >= px && mouseX < px + pw && mouseY >= ry && mouseY < ry + popupRowHeight())
                context.fill(px + 1, ry, px + pw - 1, ry + popupRowHeight(), 0x803E4852);
            context.fill(px + 6, ry + 3, px + 15, ry + 12, checked ? ChatTheme.bright(panel.palette) : 0xFF68717A);
            if (checked) context.drawTextWithShadow(renderer, "✓", px + 7, ry + 2, ChatTheme.text(panel.palette));
            context.drawTextWithShadow(renderer, renderer.trimToWidth(option.label(), pw - 30), px + 21, ry + 3, 0xFFE3E7EB);
        }
        context.disableScissor();
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
            int px = popupX(panel), py = header + HEADER_HEIGHT;
            int pw = Math.max(150, Math.min(210, panel.width - 8));
            int rows = popupVisibleRows(panel, screenHeight);
            int popupHeight = rows * popupRowHeight() + 3;
            if (panel.filterPopup && mouseX >= px && mouseX < px + pw && mouseY >= py && mouseY < py + popupHeight) {
                List<ChatSorter.FilterToggle> options = ChatSorter.panelFilterToggles();
                int row = (int)((mouseY - py - 2) / popupRowHeight());
                int index = panel.filterPopupScroll + row;
                if (row >= 0 && row < rows && index >= 0 && index < options.size())
                    togglePanelFilter(panel, options.get(index).key());
                return true;
            }
            if (panel.filterPopup) panel.filterPopup = false;
            boolean inHeader = mouseX >= x && mouseX <= x + width
                && mouseY >= header && mouseY < header + HEADER_HEIGHT;
            boolean inBody = mouseX >= x - edge && mouseX <= x + width + edge
                && mouseY >= top - edge && mouseY <= bottom + edge;
            if (inHeader || inBody) {
                if (mouseX >= x + width - 22 && mouseY >= header && mouseY < header + HEADER_HEIGHT) {
                    closeWindow(panel);
                    return true;
                }
                if (mouseX >= x + width - 39 && mouseX < x + width - 22 && inHeader) {
                    MinecraftClient.getInstance().setScreen(new ChatBackgroundSettingsScreen(
                        MinecraftClient.getInstance().currentScreen, panel.id));
                    return true;
                }
                if (mouseX >= x + 2 && mouseX < x + 26 && inHeader) {
                    panel.filterPopup = !panel.filterPopup;
                    return true;
                }
                if (panel.filterPopup) return true;
                if (inHeader) {
                    dragging = panel;
                    resizing = false;
                    lastX = mouseX;
                    lastY = mouseY;
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
                return false;
            }
        }
        return false;
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
            double scale = Math.max(.01, mc.options.getChatScale().getValue());
            int oldWidth = dragging.width;
            int oldHeight = dragging.height;
            double newWidth = oldWidth;
            double newHeight = oldHeight;
            if (resizeRight) newWidth += dx / scale;
            if (resizeLeft) newWidth -= dx / scale;
            if (resizeTop) newHeight -= dy / scale;
            if (resizeBottom) dragging.y += (int)Math.round(dy);
            dragging.width = clamp((int)Math.round(newWidth), MIN_WIDTH,
                Math.max(MIN_WIDTH, (int)Math.floor((screenWidth - 8) / scale)));
            dragging.height = clamp((int)Math.round(newHeight), MIN_HEIGHT,
                Math.max(MIN_HEIGHT, (int)Math.floor((screenHeight - 48) / scale)));
            if (resizeLeft) dragging.x -= (int)Math.round((dragging.width - oldWidth) * scale);
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
        for (Panel panel : PANELS) {
            if (!panel.filterPopup) continue;
            int px = popupX(panel), py = popupY(panel, screenHeight);
            int pw = Math.max(150, Math.min(210, panel.width - 8));
            int rows = popupVisibleRows(panel, screenHeight);
            if (mouseX >= px && mouseX < px + pw && mouseY >= py && mouseY < py + rows * popupRowHeight() + 3) {
                int max = Math.max(0, ChatSorter.panelFilterToggles().size() - rows);
                panel.filterPopupScroll = clamp(panel.filterPopupScroll - (int)Math.signum(amount) * 3, 0, max);
                return true;
            }
        }
        ChatHud hud = hudAt(mouseX, mouseY, screenHeight);
        if (hud == null) return false;
        hud.scroll((int)Math.signum(amount) * 3);
        return true;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
