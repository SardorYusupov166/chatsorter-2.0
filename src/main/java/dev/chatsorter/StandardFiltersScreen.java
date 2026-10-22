package dev.chatsorter;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Lets players hide built-in filter chips and restore them later. */
public final class StandardFiltersScreen extends Screen {
    private final Screen parent;
    private static final String[] KEYS = {"NEARBY", "DM", "SPY", "CONSOLE", "ALL"};
    private static final String[] LABELS = {"Общий чат", "ЛС", "Spy", "Консоль", "Все"};

    public StandardFiltersScreen(Screen parent) {
        super(Text.literal("ChatSorter — Стандартные фильтры"));
        this.parent = parent;
    }

    private void button(DrawContext context, String label, int x, int y, int w, int h, int mouseX, int mouseY, int color) {
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        context.fill(x, y, x + w, y + h, hovered ? ChatTheme.accent() : color);
        context.drawCenteredTextWithShadow(textRenderer, label, x + w / 2, y + (h - 8) / 2, 0xFFFFFFFF);
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xEE14181D);
        context.fill(0, 0, width, 38, ChatTheme.accent());
        context.drawCenteredTextWithShadow(textRenderer, "Стандартные фильтры чата", width / 2, 14, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
            "Скрытие убирает кнопку с панели, но не удаляет сообщения или историю.", width / 2, 52, 0xFFCCD2D8);

        int cellW = Math.min(340, Math.max(220, (width - 48) / 2));
        int gap = 12;
        int left = (width - (cellW * 2 + gap)) / 2;
        int top = 92;
        for (int i = 0; i < KEYS.length; i++) {
            int col = i % 2, row = i / 2;
            int x = left + col * (cellW + gap), y = top + row * 46;
            boolean visible = ChatExperienceSettings.standardFilterVisible(KEYS[i]);
            context.fill(x, y, x + cellW, y + 38, 0xB52A2E33);
            context.drawTextWithShadow(textRenderer, LABELS[i], x + 10, y + 7, 0xFFFFFFFF);
            String action = visible ? "Убрать" : "Вернуть";
            button(context, action, x + cellW - 96, y + 5, 86, 27, mouseX, mouseY,
                visible ? 0xFF733D3D : 0xFF315D46);
        }

        button(context, "Восстановить все стандартные фильтры", width / 2 - 160, height - 62,
            320, 24, mouseX, mouseY, 0xFF315D46);
        button(context, "Готово", width / 2 - 55, height - 31, 110, 22, mouseX, mouseY, 0xFF30363E);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == 0) {
            double mouseX = click.x(), mouseY = click.y();
            if (mouseX >= width / 2.0 - 55 && mouseX < width / 2.0 + 55
                    && mouseY >= height - 31 && mouseY < height - 9) {
                close();
                return true;
            }
            if (mouseX >= width / 2.0 - 160 && mouseX < width / 2.0 + 160
                    && mouseY >= height - 62 && mouseY < height - 38) {
                ChatExperienceSettings.restoreStandardFilters();
                ChatSorter.refreshChatFromFilters();
                return true;
            }
            int cellW = Math.min(340, Math.max(220, (width - 48) / 2));
            int gap = 12;
            int left = (width - (cellW * 2 + gap)) / 2;
            int top = 92;
            for (int i = 0; i < KEYS.length; i++) {
                int col = i % 2, row = i / 2;
                int x = left + col * (cellW + gap), y = top + row * 46;
                if (mouseX >= x + cellW - 96 && mouseX < x + cellW - 10
                        && mouseY >= y + 5 && mouseY < y + 32) {
                    ChatExperienceSettings.setStandardFilterVisible(KEYS[i],
                        !ChatExperienceSettings.standardFilterVisible(KEYS[i]));
                    ChatSorter.refreshChatFromFilters();
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override public void close() { client.setScreen(parent); }
}
