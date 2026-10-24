package dev.chatsorter;

import net.minecraft.client.render.VertexConsumer;
import org.joml.Matrix4f;

/** Exact, font-independent rectangles for the world panel's glass and frame. */
final class ChatHologramQuad {
    private static final int FULL_BRIGHT = 0x00F000F0;

    private ChatHologramQuad() { }

    static void emit(VertexConsumer vertices, Matrix4f matrix, float x, float y,
                     float width, float height, int color) {
        if (!(width > 0) || !(height > 0) || !Float.isFinite(width) || !Float.isFinite(height)
                || !Float.isFinite(x) || !Float.isFinite(y) || (color >>> 24) == 0) return;
        float right = x + width, bottom = y + height;
        if (!Float.isFinite(right) || !Float.isFinite(bottom)) return;
        vertices.vertex(matrix, x, y, 0).color(color).light(FULL_BRIGHT);
        vertices.vertex(matrix, x, bottom, 0).color(color).light(FULL_BRIGHT);
        vertices.vertex(matrix, right, bottom, 0).color(color).light(FULL_BRIGHT);
        vertices.vertex(matrix, right, y, 0).color(color).light(FULL_BRIGHT);
    }
}
