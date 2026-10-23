package dev.chatsorter;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** Picker for stock photo backgrounds, local images, and readable chat palettes. */
public final class ChatBackgroundSettingsScreen extends Screen {
    private final Screen parent;
    private int scroll;
    private static final int TOP = 72;
    private static final int ROW_HEIGHT = 82;
    private static final int GAP = 8;

    public ChatBackgroundSettingsScreen(Screen parent) {
        super(Text.literal("ChatSorter — Фоны чата"));
        this.parent = parent;
    }

    @Override protected void init() {
        ChatBackgrounds.requestAllThumbnails();
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    private List<Card> cards() {
        ArrayList<Card> cards = new ArrayList<>();
        cards.add(new Card("none", "Без фона", "Стандартный вид", null));
        for (ChatBackgrounds.Template template : ChatBackgrounds.templates())
            cards.add(new Card(template.id(), template.title(), "Unsplash · " + template.author(), template.pageUrl()));
        for (ChatBackgrounds.LocalImage image : ChatBackgrounds.customImages())
            cards.add(new Card(image.id(), image.title(), "Свой файл", null));
        return cards;
    }

    private record Card(String id, String title, String subtitle, String source) { }

    private int cardWidth() { return Math.max(230, (width - 36) / 2); }
    private int columns() { return width < 540 ? 1 : 2; }
    private int cardX(int column) { return columns() == 1 ? (width - cardWidth()) / 2 : 12 + column * (cardWidth() + GAP); }
    private int maxScroll() {
        int rows = (cards().size() + columns() - 1) / columns();
        int contentHeight = rows * ROW_HEIGHT;
        return Math.max(0, contentHeight - Math.max(100, height - TOP - 104));
    }
    private int visibleBottom() { return height - 80; }

    private void drawButton(DrawContext context, String label, int x, int y, int w, int h,
                            int mouseX, int mouseY, boolean active) {
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        context.fill(x, y, x + w, y + h,
            active ? ChatTheme.accent() : hovered ? 0xE03C444D : 0xD9252A30);
        context.fill(x, y + h - 2, x + w, y + h, ChatTheme.bright());
        context.drawCenteredTextWithShadow(textRenderer, label, x + w / 2, y + (h - 8) / 2, 0xFFFFFFFF);
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        ChatBackgrounds.renderBackground(context, 0, 0, width, height);
        context.fill(0, 0, width, height, 0xA914181D);
        context.fill(0, 0, width, 54, 0xE51B2026);
        context.fill(0, 52, width, 54, ChatTheme.bright());
        context.drawCenteredTextWithShadow(textRenderer, "Фоны и оформление чата", width / 2, 12, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
            "Выберите фотографию и подходящую палитру. Фоны Unsplash сохраняются в кэш.", width / 2, 32, 0xFFD1D6DC);

        int left = 12;
        int colW = cardWidth();
        int columns = columns();
        int row = 0;
        List<Card> cards = cards();
        context.enableScissor(0, TOP - 3, width, visibleBottom());
        for (int i = 0; i < cards.size(); i++) {
            Card card = cards.get(i);
            int col = i % columns;
            if (i > 0 && col == 0) row++;
            int x = cardX(col), y = TOP + row * ROW_HEIGHT - scroll;
            if (y + ROW_HEIGHT < TOP || y > visibleBottom()) continue;
            boolean selected = ChatBackgrounds.selectedId().equals(card.id());
            context.fill(x, y, x + colW, y + ROW_HEIGHT - 5, selected ? 0xE032414B : 0xD922282E);
            ChatBackgrounds.renderPreview(context, card.id(), x + 5, y + 5, 124, ROW_HEIGHT - 15);
            context.fill(x + 4, y + ROW_HEIGHT - 11, x + 130, y + ROW_HEIGHT - 6, selected ? ChatTheme.bright() : 0x804C555E);
            context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(card.title(), colW - 148),
                x + 138, y + 11, 0xFFFFFFFF);
            context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(card.subtitle(), colW - 148),
                x + 138, y + 28, 0xFFB6BEC7);
            if (selected) context.drawTextWithShadow(textRenderer, "✓ выбрано", x + 138, y + 48, ChatTheme.bright());
            if (card.source() != null) {
                context.drawTextWithShadow(textRenderer, "Unsplash License", x + 138, y + 62, 0xFF929CA6);
            }
        }
        context.disableScissor();

        int controlsY = height - 68;
        context.fill(0, controlsY - 6, width, height, 0xE51B2026);
        context.fill(0, controlsY - 7, width, controlsY - 6, ChatTheme.accent());
        drawButton(context, "Импорт фото", 12, controlsY, 126, 24, mouseX, mouseY, false);

        drawButton(context, "−", 152, controlsY, 24, 24, mouseX, mouseY, false);
        context.drawCenteredTextWithShadow(textRenderer, "Затемнение: " + ChatBackgrounds.darkening() + "%",
            242, controlsY + 8, 0xFFE5E8EB);
        drawButton(context, "+", 282, controlsY, 24, 24, mouseX, mouseY, false);

        int themeX = 322;
        drawButton(context, "‹", themeX, controlsY, 24, 24, mouseX, mouseY, false);
        context.drawCenteredTextWithShadow(textRenderer, "Тема: " + ChatTheme.name(),
            themeX + 128, controlsY + 8, 0xFFE5E8EB);
        drawButton(context, "›", themeX + 232, controlsY, 24, 24, mouseX, mouseY, false);

        drawButton(context, "Готово", width - 122, controlsY, 110, 24, mouseX, mouseY, false);
        context.drawTextWithShadow(textRenderer,
            "Свой фон можно выбрать на компьютере. Поддерживаются PNG, JPG, WEBP и BMP (до 20 МБ).",
            12, height - 28, 0xFFADB5BE);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == 0) {
            int mouseX = (int)click.x(), mouseY = (int)click.y();
            if (mouseY >= TOP && mouseY < visibleBottom()) {
                List<Card> cards = cards();
                int colW = cardWidth();
                for (int i = 0; i < cards.size(); i++) {
                    int col = i % columns();
                    int row = i / columns();
                    int x = cardX(col), y = TOP + row * ROW_HEIGHT - scroll;
                    if (mouseX >= x && mouseX < x + colW && mouseY >= y && mouseY < y + ROW_HEIGHT - 5) {
                        ChatBackgrounds.select(cards.get(i).id());
                        return true;
                    }
                }
            }
            int controlsY = height - 68;
            if (mouseY >= controlsY && mouseY < controlsY + 24) {
                if (mouseX >= 12 && mouseX < 138) {
                    ChatBackgrounds.importImage();
                    return true;
                }
                if (mouseX >= 152 && mouseX < 176) {
                    ChatBackgrounds.setDarkening(ChatBackgrounds.darkening() - 5);
                    return true;
                }
                if (mouseX >= 282 && mouseX < 306) {
                    ChatBackgrounds.setDarkening(ChatBackgrounds.darkening() + 5);
                    return true;
                }
                int themeX = 322;
                if (mouseX >= themeX && mouseX < themeX + 24) {
                    ChatTheme.setIndex(ChatTheme.index() - 1);
                    return true;
                }
                if (mouseX >= themeX + 232 && mouseX < themeX + 256) {
                    ChatTheme.setIndex(ChatTheme.index() + 1);
                    return true;
                }
                if (mouseX >= width - 122 && mouseX < width - 12) {
                    close();
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int)Math.signum(verticalAmount) * ROW_HEIGHT));
        return true;
    }

    @Override public void close() { client.setScreen(parent); }
}
