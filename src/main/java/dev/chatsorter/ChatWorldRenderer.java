package dev.chatsorter;

import dev.chatsorter.mixin.ChatHudAccess;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.command.RenderCommandQueue;
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
        Vec3d cameraPos = camera.getPos();
        Vec3d playerPos = player.getLerpedPos(1.0F);
        float yaw = (float)Math.toRadians(player.getYaw());
        double forwardX = -Math.sin(yaw), forwardZ = Math.cos(yaw);
        double rightX = Math.cos(yaw), rightZ = Math.sin(yaw);
        double originX = playerPos.x + forwardX * 1.25;
        double originY = playerPos.y + 1.72;
        double originZ = playerPos.z + forwardZ * 1.25;

        RenderCommandQueue queue = renderContext.commandQueue();
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
            double side = (width * scale * 0.5 + 0.08) * (column == 0 ? -1 : 1);
            double lift = -row * (Math.min(height, 180) * scale + 0.08);
            double anchorX = originX + rightX * side - cameraPos.x;
            double anchorY = originY + lift - cameraPos.y;
            double anchorZ = originZ + rightZ * side - cameraPos.z;
            drawChat(queue, textRenderer, renderContext.matrices(), hud, i, width, height,
                anchorX, anchorY, anchorZ, facing, tick, scale);
        }
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

    private static void drawChat(RenderCommandQueue queue, TextRenderer renderer, MatrixStack matrices,
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
        int background = rpgStyle ? 0xB80B0A17 : 0xB8000000;
        int headerBackground = rpgStyle ? 0xE0181428 : 0xD322272D;

        // A thin halo and frame are built from text-background quads, so no texture or custom pipeline is needed.
        if (rpgStyle && ChatExperienceSettings.rpgGlowEnabled) {
            int alpha = ChatExperienceSettings.rpgAnimationEnabled
                ? 20 + (int)((Math.sin(tick * 0.035) + 1.0) * 8.0) : 22;
            submitBackground(queue, renderer, matrices, left - 3, top - 2, width + 6, withAlpha(accent, alpha));
        }
        submitBackground(queue, renderer, matrices, left, top, width, background);
        submitBackground(queue, renderer, matrices, left, top, width, withAlpha(accent, 220));
        submitBackground(queue, renderer, matrices, left + width - 2, top, 2, 0xFF9BE8FF);
        submitBackground(queue, renderer, matrices, left, top + height - 9, width, withAlpha(accent, 190));
        submitBackground(queue, renderer, matrices, left, top + 1, width, headerBackground);

        String title = "ЧАТ " + (index + 1) + (rpgStyle ? "  ·  RPG" : "");
        queue.submitText(matrices, left + 8, top + 2, Text.literal(title).asOrderedText(), false,
            TextRenderer.TextLayerType.SEE_THROUGH, FULL_BRIGHT, 0xFFDCEBFF, 0, 0);

        float messagesTop = top + 15;
        for (int line = 0; line < rows; line++) {
            int messageIndex = rows - line - 1;
            ChatHudLine.Visible visibleLine = visible.get(messageIndex);
            String raw = visibleLine.content().getString();
            String message = renderer.trimToWidth(raw, Math.max(1, width - 14));
            float rowY = messagesTop + line * 10;
            submitBackground(queue, renderer, matrices, left + 3, rowY - 1, width - 6,
                (line & 1) == 0 ? 0x37151A28 : 0x3020142C);
            queue.submitText(matrices, left + 7, rowY, Text.literal(message).asOrderedText(), false,
                TextRenderer.TextLayerType.SEE_THROUGH, FULL_BRIGHT, 0xFFE3E7F0, 0, 0);
        }

        if (rpgStyle && ChatExperienceSettings.rpgParticlesEnabled) {
            double phase = tick * 0.045 + index * 1.7;
            float particleX = (float)(left + 14 + (width - 28) * ((Math.sin(phase) + 1.0) * 0.5));
            submitBackground(queue, renderer, matrices, particleX, top - 1, 4, 0xB09BE8FF);
            if (index % 2 == 0)
                submitBackground(queue, renderer, matrices, left + width - 2, top + 18, 4, 0xA09D82FF);
        }
        matrices.pop();
    }

    private static void submitBackground(RenderCommandQueue queue, TextRenderer renderer, MatrixStack matrices,
                                         float x, float y, int width, int color) {
        if (width <= 0 || (color >>> 24) == 0) return;
        int spaceWidth = Math.max(1, renderer.getWidth(" "));
        int count = Math.max(1, Math.min(256, (width + spaceWidth - 1) / spaceWidth));
        String blank = " ".repeat(count);
        queue.submitText(matrices, x, y, Text.literal(blank).asOrderedText(), false,
            TextRenderer.TextLayerType.SEE_THROUGH, FULL_BRIGHT, 0x00000000, color, 0);
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }
}
