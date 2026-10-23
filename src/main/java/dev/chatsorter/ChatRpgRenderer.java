package dev.chatsorter;

import net.minecraft.client.gui.DrawContext;

/** Lightweight, texture-free dark system frame for RPG-styled chat HUDs. */
public final class ChatRpgRenderer {
    private static final int VIOLET = 0xFF9D82FF;
    private static final int ICE = 0xFF9BE8FF;

    private ChatRpgRenderer() { }

    public static int accent(int palette) { return ChatTheme.bright(palette); }

    public static void renderFrame(DrawContext context, int x, int y, int width, int height,
                                   int palette, int tick, float tickDelta) {
        if (width <= 0 || height <= 0) return;
        boolean pulse = ChatExperienceSettings.rpgAnimationEnabled;
        double phase = (tick + tickDelta) * 0.045;
        double wave = pulse ? (Math.sin(phase) + 1.0) * 0.5 : 0.35;
        int primary = accent(palette);

        if (ChatExperienceSettings.rpgGlowEnabled) {
            int outerAlpha = 12 + (int)(wave * 10);
            int innerAlpha = 22 + (int)(wave * 15);
            outline(context, x - 2, y - 2, width + 4, height + 4, withAlpha(primary, outerAlpha));
            outline(context, x - 1, y - 1, width + 2, height + 2, withAlpha(VIOLET, innerAlpha));
        }

        outline(context, x, y, width, height, withAlpha(primary, pulse ? 160 + (int)(wave * 72) : 210));
        drawCorners(context, x, y, width, height, primary);
        if (ChatExperienceSettings.rpgParticlesEnabled) drawParticles(context, x, y, width, height, tick, tickDelta);
    }

    public static void renderHeader(DrawContext context, int x, int y, int width, int height, int palette) {
        if (width <= 0 || height <= 0) return;
        int primary = accent(palette);
        context.fill(x, y, x + width, y + height, 0xE3111020);
        context.fill(x, y, x + width, y + 1, withAlpha(primary, 225));
        context.fill(x, y + height - 1, x + width, y + height, withAlpha(ICE, 185));
        int cut = Math.min(9, width / 4);
        context.fill(x, y, x + cut, y + 2, ICE);
        context.fill(x + width - cut, y + height - 2, x + width, y + height, primary);
    }

    private static void drawCorners(DrawContext context, int x, int y, int width, int height, int primary) {
        int corner = Math.max(1, Math.min(12, Math.min(width, height)));
        int thickness = Math.min(2, corner);
        int color = withAlpha(ICE, 235);
        context.fill(x, y, x + corner, y + thickness, primary);
        context.fill(x, y, x + thickness, y + corner, color);
        context.fill(x + width - corner, y, x + width, y + thickness, color);
        context.fill(x + width - thickness, y, x + width, y + corner, primary);
        context.fill(x, y + height - thickness, x + corner, y + height, color);
        context.fill(x, y + height - corner, x + thickness, y + height, primary);
        context.fill(x + width - corner, y + height - thickness, x + width, y + height, primary);
        context.fill(x + width - thickness, y + height - corner, x + width, y + height, color);
    }

    private static void drawParticles(DrawContext context, int x, int y, int width, int height, int tick, float delta) {
        double time = tick + delta;
        for (int i = 0; i < 6; i++) {
            double phase = time * 0.025 + i * (Math.PI * 2.0 / 6.0);
            int side = i % 4;
            int px, py;
            if (side == 0 || side == 2) {
                px = x + 8 + (int)((width - 16) * ((Math.sin(phase) + 1.0) * 0.5));
                py = side == 0 ? y - 2 : y + height;
            } else {
                px = side == 1 ? x + width : x - 2;
                py = y + 8 + (int)((height - 16) * ((Math.cos(phase) + 1.0) * 0.5));
            }
            context.fill(px - 1, py - 1, px + 2, py + 2, withAlpha(i % 2 == 0 ? ICE : VIOLET, 110));
            if (i % 2 == 0) context.fill(px, py, px + 1, py + 1, 0xFFFFFFFF);
        }
    }

    private static void outline(DrawContext context, int x, int y, int width, int height, int color) {
        if (width <= 0 || height <= 0) return;
        context.fill(x, y, x + width, y + 1, color);
        context.fill(x, y + height - 1, x + width, y + height, color);
        context.fill(x, y, x + 1, y + height, color);
        context.fill(x + width - 1, y, x + width, y + height, color);
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }
}
