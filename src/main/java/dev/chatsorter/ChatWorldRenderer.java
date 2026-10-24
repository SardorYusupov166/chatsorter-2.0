package dev.chatsorter;

import dev.chatsorter.mixin.ChatHudAccess;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;

/** World-anchored, player-following RPG chat holograms. */
public final class ChatWorldRenderer {
    private static final int FULL_BRIGHT = 0x00F000F0;
    private static final int MAX_WORLD_ROWS = 7;
    private static final float WORLD_SCALE = 0.0021F;

    private ChatWorldRenderer() { }

    public static void register() {
        WorldRenderEvents.END_MAIN.register(ChatWorldRenderer::render);
    }

    public static boolean shouldHideScreenChat(ChatHud hud) {
        MinecraftClient client = MinecraftClient.getInstance();
        return ChatExperienceSettings.rpgModeEnabled
            && client.player != null
            && !client.options.hudHidden
            && client.currentScreen == null
            && hud == client.inGameHud.getChatHud();
    }

    private static void render(WorldRenderContext renderContext) {
        MinecraftClient client = MinecraftClient.getInstance();
        PlayerEntity player = client.player;
        if (!ChatExperienceSettings.rpgModeEnabled || player == null || client.world == null
                || client.options.hudHidden || client.currentScreen != null) return;

        ArrayList<ChatHud> chats = new ArrayList<>();
        chats.add(client.inGameHud.getChatHud());
        chats.addAll(ChatPanelManager.panelHuds());
        if (chats.isEmpty()) return;

        Camera camera = client.gameRenderer.getCamera();
        Vec3d cameraPos = camera.getCameraPos();
        Vec3d playerPos = player.getLerpedPos(1.0F);
        float yaw = (float)Math.toRadians(player.getYaw());
        double forwardX = -Math.sin(yaw), forwardZ = Math.cos(yaw);
        double rightX = Math.cos(yaw), rightZ = Math.sin(yaw);
        double originX = playerPos.x + forwardX * 1.25;
        double originY = playerPos.y + 1.72;
        double originZ = playerPos.z + forwardZ * 1.25;

        VertexConsumerProvider consumers = renderContext.consumers();
        TextRenderer textRenderer = client.textRenderer;
        Quaternionf facing = camera.getRotation();
        int tick = player.age;
        for (int i = 0; i < chats.size(); i++) {
            ChatHud hud = chats.get(i);
            int column = i % 2;
            int row = i / 2;
            int width = effectiveWidth(client, hud);
            int height = effectiveHeight(client, hud);
            float scale = worldScale(client);
            // A single chat belongs in the centre of the player's view. With
            // multiple chats, fan them out symmetrically in the player's plane.
            double side = chats.size() == 1 ? 0.0
                : (width * scale * 0.5 + 0.08) * (column == 0 ? -1 : 1);
            double lift = -row * (Math.min(height, 180) * scale + 0.08);
            double anchorX = originX + rightX * side - cameraPos.x;
            double anchorY = originY + lift - cameraPos.y;
            double anchorZ = originZ + rightZ * side - cameraPos.z;
            drawChat(consumers, textRenderer, renderContext.matrices(), hud, i, width, height,
                anchorX, anchorY, anchorZ, facing, tick, scale);
        }
        // END_MAIN is a drawing callback: extraction command-queue entries are
        // already too late here. Flush the text layer's world vertices now.
        if (consumers instanceof VertexConsumerProvider.Immediate immediate) immediate.draw();
    }

    private static int effectiveWidth(MinecraftClient client, ChatHud hud) {
        int width = ChatPanelManager.width(hud);
        if (width < 0) width = ChatPosition.width > 0
            ? ChatPosition.width : ChatHud.getWidth(client.options.getChatWidth().getValue());
        return Math.max(100, Math.min(1200, width));
    }

    private static int effectiveHeight(MinecraftClient client, ChatHud hud) {
        int height = ChatPanelManager.height(hud);
        if (height < 0) height = ChatPosition.height > 0
            ? ChatPosition.height : ChatHud.getHeight(client.options.getChatHeightUnfocused().getValue());
        return Math.max(54, Math.min(1000, height));
    }

    private static float worldScale(MinecraftClient client) {
        return WORLD_SCALE * (float)Math.max(0.65, Math.min(1.35, client.options.getChatScale().getValue()));
    }

    private static void drawChat(VertexConsumerProvider consumers, TextRenderer renderer, MatrixStack matrices,
                                 ChatHud hud, int index, int width, int availableHeight,
                                 double x, double y, double z, Quaternionf facing, int tick, float scale) {
        List<ChatHudLine.Visible> visible = ((ChatHudAccess)hud).chatsorter$getVisibleMessages();
        int rows = Math.min(Math.min(MAX_WORLD_ROWS, Math.max(1, (availableHeight - 20) / 10)), visible.size());
        int palette = ChatPanelManager.palette(hud);
        boolean rpgStyle = ChatPanelManager.usesRpgStyle(hud);
        int accent = rpgStyle ? ChatRpgRenderer.accent(palette) : 0xFFB9C2CC;
        int height = 23 + rows * 10;

        matrices.push();
        matrices.translate(x, y, z);
        matrices.multiply(facing);
        matrices.scale(-scale, -scale, scale);

        float left = -width / 2.0F;
        float top = -height / 2.0F;
        int background = rpgStyle ? 0xA9080B18 : 0xA8000000;
        int headerBackground = rpgStyle ? 0xD4101930 : 0xC322272D;

        // Layered edge light and separated corner brackets make this read as a
        // projected system panel rather than a flat chat window. All geometry
        // uses text background quads, so it stays cheap and needs no textures.
        if (rpgStyle && ChatExperienceSettings.rpgGlowEnabled) {
            int pulse = ChatExperienceSettings.rpgAnimationEnabled
                ? 15 + (int)((Math.sin(tick * 0.035) + 1.0) * 7.0) : 18;
            submitBackground(consumers, renderer, matrices, left - 4, top - 3, width + 8, withAlpha(accent, pulse));
            submitBackground(consumers, renderer, matrices, left - 2, top - 2, width + 4, withAlpha(0xFF8BDFFF, pulse / 2));
        }
        submitBackground(consumers, renderer, matrices, left, top, width, background);
        submitBackground(consumers, renderer, matrices, left + 2, top + 2, width - 4, withAlpha(accent, 210));
        submitBackground(consumers, renderer, matrices, left + 3, top + 3, width - 6, background);
        submitBackground(consumers, renderer, matrices, left + 7, top + 4, width - 14, headerBackground);
        submitBackground(consumers, renderer, matrices, left + 7, top + 15, width - 14, withAlpha(accent, 125));
        submitBackground(consumers, renderer, matrices, left + 1, top + 6, 2, 10, withAlpha(0xFF9BE8FF, 235));
        submitBackground(consumers, renderer, matrices, left + width - 3, top + 6, 2, 10, withAlpha(0xFF9BE8FF, 235));
        submitBackground(consumers, renderer, matrices, left + 7, top + height - 3, width - 14, 1, withAlpha(accent, 210));
        drawCorner(consumers, renderer, matrices, left + 2, top + 2, 1, 1, accent);
        drawCorner(consumers, renderer, matrices, left + width - 2, top + 2, -1, 1, accent);
        drawCorner(consumers, renderer, matrices, left + 2, top + height - 2, 1, -1, accent);
        drawCorner(consumers, renderer, matrices, left + width - 2, top + height - 2, -1, -1, accent);

        String title = rpgStyle ? "◈ СИСТЕМА  /  ЧАТ " + (index + 1) : "ЧАТ " + (index + 1);
        drawText(consumers, renderer, matrices, left + 8, top + 2, Text.literal(title).asOrderedText(), 0xFFDCEBFF, 0);

        float messagesTop = top + 15;
        for (int line = 0; line < rows; line++) {
            int messageIndex = rows - line - 1;
            ChatHudLine.Visible visibleLine = visible.get(messageIndex);
            String raw = orderedTextToString(visibleLine.content());
            String message = renderer.trimToWidth(raw, Math.max(1, width - 14));
            float rowY = messagesTop + line * 10;
            submitBackground(consumers, renderer, matrices, left + 3, rowY - 1, width - 6,
                (line & 1) == 0 ? 0x37151A28 : 0x3020142C);
            drawText(consumers, renderer, matrices, left + 7, rowY, Text.literal(message).asOrderedText(), 0xFFE3E7F0, 0);
        }

        if (rpgStyle && ChatExperienceSettings.rpgParticlesEnabled) {
            double phase = tick * 0.045 + index * 1.7;
            float particleX = (float)(left + 14 + (width - 28) * ((Math.sin(phase) + 1.0) * 0.5));
            submitBackground(consumers, renderer, matrices, particleX, top - 2, 3, 3, 0xD09BE8FF);
            submitBackground(consumers, renderer, matrices, left + width - 3, top + height - 7, 2, 2, 0xC09D82FF);
        }
        if (rpgStyle && ChatExperienceSettings.rpgAnimationEnabled) {
            int scanY = (int)(Math.floorMod(tick + index * 19, Math.max(1, height - 21)));
            submitBackground(consumers, renderer, matrices, left + 9, top + 16 + scanY, width - 18, 0x129BE8FF);
        }
        matrices.pop();
    }

    private static void submitBackground(VertexConsumerProvider consumers, TextRenderer renderer, MatrixStack matrices,
                                         float x, float y, int width, int color) {
        if (width <= 0 || (color >>> 24) == 0) return;
        int spaceWidth = Math.max(1, renderer.getWidth(" "));
        int count = Math.max(1, Math.min(256, (width + spaceWidth - 1) / spaceWidth));
        String blank = " ".repeat(count);
        renderer.draw(Text.literal(blank).asOrderedText(), x, y, 0x00000000, false,
            matrices.peek().getPositionMatrix(), consumers, TextRenderer.TextLayerType.SEE_THROUGH, color, FULL_BRIGHT);
    }

    private static void submitBackground(VertexConsumerProvider consumers, TextRenderer renderer, MatrixStack matrices,
                                         float x, float y, int width, int height, int color) {
        for (int row = 0; row < height; row++) submitBackground(consumers, renderer, matrices, x, y + row, width, color);
    }

    private static void drawCorner(VertexConsumerProvider consumers, TextRenderer renderer, MatrixStack matrices,
                                   float x, float y, int directionX, int directionY, int accent) {
        int bright = withAlpha(accent, 245);
        float horizontalX = directionX > 0 ? x : x - 9;
        float verticalY = directionY > 0 ? y : y - 8;
        submitBackground(consumers, renderer, matrices, horizontalX, y, 9, bright);
        submitBackground(consumers, renderer, matrices, x, verticalY, 1, 8, bright);
        float innerX = x + directionX * 4;
        float innerY = y + directionY * 4;
        submitBackground(consumers, renderer, matrices, Math.min(x, innerX), innerY, 5, withAlpha(0xFF9BE8FF, 225));
    }

    private static void drawText(VertexConsumerProvider consumers, TextRenderer renderer, MatrixStack matrices,
                                 float x, float y, net.minecraft.text.OrderedText text, int color, int background) {
        renderer.draw(text, x, y, color, false, matrices.peek().getPositionMatrix(), consumers,
            TextRenderer.TextLayerType.SEE_THROUGH, background, FULL_BRIGHT);
    }

    private static String orderedTextToString(net.minecraft.text.OrderedText orderedText) {
        StringBuilder result = new StringBuilder();
        orderedText.accept((index, style, codePoint) -> {
            result.appendCodePoint(codePoint);
            return true;
        });
        return result.toString();
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }
}
