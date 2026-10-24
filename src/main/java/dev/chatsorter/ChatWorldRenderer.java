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
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** World-anchored, player-following RPG chat holograms. */
public final class ChatWorldRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("ChatSorter/RPG");
    private static final int FULL_BRIGHT = 0x00F000F0;
    private static final int MAX_WORLD_ROWS = 10;
    private static final int HEADER_HEIGHT = 30;
    private static final int ROW_HEIGHT = 14;
    private static final float WORLD_SCALE = 0.0078F;
    private static final long LOG_INTERVAL_NANOS = 5_000_000_000L;
    private static final long FRAME_TIMEOUT_NANOS = 500_000_000L;
    private static final Map<String, String> LAST_PHASES = new HashMap<>();
    private static final Map<String, Long> LAST_LOG_TIMES = new HashMap<>();
    private static final Set<ChatHud> SUBMITTED_CHATS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static Object submittedWorld;
    private static long submittedAt;
    private static long renderCallbacks;

    private ChatWorldRenderer() { }

    public static void register() {
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(ChatWorldRenderer::render);
        LOGGER.info("[ChatSorter][RPG] Registered world render callback: WorldRenderEvents.BEFORE_TRANSLUCENT");
    }

    public static boolean shouldHideScreenChat(ChatHud hud) {
        MinecraftClient client = MinecraftClient.getInstance();
        boolean requested = ChatExperienceSettings.rpgModeEnabled
            && client.player != null
            && client.world != null
            && !client.options.hudHidden
            && client.currentScreen == null;
        long age = System.nanoTime() - submittedAt;
        boolean enabled = requested && submittedWorld == client.world && SUBMITTED_CHATS.contains(hud)
            && age >= 0 && age < FRAME_TIMEOUT_NANOS;
        String reason = !ChatExperienceSettings.rpgModeEnabled ? "RPG mode is off"
            : client.player == null ? "no player"
            : client.world == null ? "no world"
            : client.options.hudHidden ? "HUD hidden"
            : client.currentScreen != null ? "screen open: " + client.currentScreen.getClass().getName()
            : enabled ? "screen chat replaced after an in-view world submission"
            : "screen chat retained: no recent in-view hologram submission";
        report("hud:" + ChatPanelManager.id(hud), enabled ? "cancelled" : "vanilla", reason,
            requested ? LOG_INTERVAL_NANOS : 0);
        return enabled;
    }

    public static void reset() {
        SUBMITTED_CHATS.clear();
        submittedWorld = null;
        submittedAt = 0;
    }

    private static void render(WorldRenderContext renderContext) {
        renderCallbacks++;
        reset();
        try {
            renderFrame(renderContext);
        } catch (Throwable error) {
            reset();
            reportError(error);
        }
    }

    private static void renderFrame(WorldRenderContext renderContext) {
        MinecraftClient client = MinecraftClient.getInstance();
        PlayerEntity player = client.player;
        if (!ChatExperienceSettings.rpgModeEnabled || player == null || client.world == null
                || client.options.hudHidden || client.currentScreen != null) {
            String reason = !ChatExperienceSettings.rpgModeEnabled ? "RPG mode is off"
                : player == null ? "no player"
                : client.world == null ? "no world"
                : client.options.hudHidden ? "HUD hidden"
                : "screen open: " + client.currentScreen.getClass().getName();
            report("world", "skipped", "world render skipped: " + reason + "; callbacks=" + renderCallbacks, 0);
            return;
        }

        ArrayList<ChatHud> chats = new ArrayList<>();
        chats.add(client.inGameHud.getChatHud());
        chats.addAll(ChatPanelManager.panelHuds());
        if (chats.isEmpty()) return;

        Camera camera = client.gameRenderer.getCamera();
        Quaternionf cameraFacing = new Quaternionf(camera.getRotation());
        Quaternionf facing = ChatHologramLayout.facing(cameraFacing, player.getBodyYaw(),
            ChatExperienceSettings.rpgFollowViewEnabled);
        Vector3f forward = ChatHologramLayout.forward(facing);
        Vector3f right = facing.transform(new Vector3f(1.0F, 0.0F, 0.0F)).normalize();
        Vector3f up = facing.transform(new Vector3f(0.0F, 1.0F, 0.0F)).normalize();
        double originX = forward.x * ChatHologramLayout.DISTANCE;
        double originY = forward.y * ChatHologramLayout.DISTANCE;
        double originZ = forward.z * ChatHologramLayout.DISTANCE;

        VertexConsumerProvider consumers = renderContext.consumers();
        MatrixStack worldMatrices = renderContext.matrices();
        if (consumers == null || worldMatrices == null) {
            report("world", "missing-context", "world render context missing: consumers="
                + className(consumers) + ", matrices=" + (worldMatrices != null), LOG_INTERVAL_NANOS);
            return;
        }
        TextRenderer textRenderer = client.textRenderer;
        int tick = player.age;
        int widest = 100;
        for (ChatHud hud : chats) widest = Math.max(widest, effectiveWidth(client, hud));
        double aspect = client.getWindow().getFramebufferWidth()
            / (double)Math.max(1, client.getWindow().getFramebufferHeight());
        double fov = client.options.getFov().getValue();
        ChatHologramLayout.Arrangement layout = ChatHologramLayout.arrange(widest,
            HEADER_HEIGHT + MAX_WORLD_ROWS * ROW_HEIGHT, chats.size(), ChatExperienceSettings.rpgPanelPosition,
            worldScale(client) * (chats.size() == 1 ? 1.0F : 0.55F), fov, aspect);
        double primaryDepth = 0;
        for (int i = 0; i < chats.size(); i++) {
            ChatHud hud = chats.get(i);
            int width = effectiveWidth(client, hud);
            float scale = layout.scale();
            double side = layout.side(i);
            double lift = layout.lift(i);
            double anchorX = originX + right.x * side + up.x * lift;
            double anchorY = originY + right.y * side + up.y * lift;
            double anchorZ = originZ + right.z * side + up.z * lift;
            Vector3f center = new Vector3f((float)anchorX, (float)anchorY, (float)anchorZ);
            if (i == 0) primaryDepth = center.dot(ChatHologramLayout.forward(cameraFacing));
            if (!ChatHologramLayout.inView(cameraFacing, facing, center, width,
                    HEADER_HEIGHT + MAX_WORLD_ROWS * ROW_HEIGHT, scale, fov, aspect)) continue;
            drawChat(consumers, textRenderer, worldMatrices, hud, i, width,
                anchorX, anchorY, anchorZ, facing, tick, scale);
            SUBMITTED_CHATS.add(hud);
        }
        if (!SUBMITTED_CHATS.isEmpty()) {
            submittedWorld = client.world;
            submittedAt = System.nanoTime();
        }
        // BEFORE_TRANSLUCENT leaves the shared consumers under the world renderer's normal flush.
        List<ChatHudLine.Visible> mainMessages = ((ChatHudAccess)client.inGameHud.getChatHud()).chatsorter$getVisibleMessages();
        report("world", SUBMITTED_CHATS.isEmpty() ? "outside-view" : "submitted",
            "world hologram vertices submitted (not a GPU visibility confirmation): callbacks=" + renderCallbacks
            + ", version=" + ChatSorter.VERSION + ", chats=" + chats.size()
            + ", inView=" + SUBMITTED_CHATS.size() + ", primaryDepth=" + primaryDepth
            + ", followView=" + ChatExperienceSettings.rpgFollowViewEnabled
            + ", position=" + ChatExperienceSettings.rpgPanelPosition + ", scale=" + layout.scale()
            + ", fov=" + fov + ", aspect=" + aspect + ", mainVisibleRows=" + mainMessages.size()
            + ", consumers=" + className(consumers) + ", event=BEFORE_TRANSLUCENT"
            + ", matrices=" + className(worldMatrices) + ", screen=" + client.currentScreen,
            LOG_INTERVAL_NANOS);
    }

    private static String className(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static void reportError(Throwable error) {
        long now = System.nanoTime();
        Long last = LAST_LOG_TIMES.get("error");
        if (last == null || now - last >= LOG_INTERVAL_NANOS) {
            LAST_LOG_TIMES.put("error", now);
            LOGGER.error("[ChatSorter][RPG] World hologram renderer failed at callback " + renderCallbacks, error);
        }
    }

    private static void report(String key, String phase, String detail, long intervalNanos) {
        long now = System.nanoTime();
        String previousPhase = LAST_PHASES.get(key);
        Long previousTime = LAST_LOG_TIMES.get(key);
        boolean phaseChanged = !phase.equals(previousPhase);
        boolean intervalElapsed = intervalNanos > 0
            && (previousTime == null || now - previousTime >= intervalNanos);
        if (!phaseChanged && !intervalElapsed) return;
        LAST_PHASES.put(key, phase);
        LAST_LOG_TIMES.put(key, now);
        LOGGER.info("[ChatSorter][RPG] {}", detail);
    }

    private static int effectiveWidth(MinecraftClient client, ChatHud hud) {
        int width = ChatPanelManager.width(hud);
        if (width < 0) width = ChatPosition.width > 0
            ? ChatPosition.width : ChatHud.getWidth(client.options.getChatWidth().getValue());
        return Math.max(100, Math.min(1200, width));
    }

    private static float worldScale(MinecraftClient client) {
        return WORLD_SCALE * (float)Math.max(0.65, Math.min(1.35, client.options.getChatScale().getValue()));
    }

    private static void drawChat(VertexConsumerProvider consumers, TextRenderer renderer, MatrixStack matrices,
                                 ChatHud hud, int index, int width,
                                 double x, double y, double z, Quaternionf facing, int tick, float scale) {
        List<ChatHudLine.Visible> visible = ((ChatHudAccess)hud).chatsorter$getVisibleMessages();
        int rows = MAX_WORLD_ROWS;
        int palette = ChatPanelManager.palette(hud);
        boolean rpgStyle = ChatPanelManager.usesRpgStyle(hud);
        int accent = rpgStyle ? ChatRpgRenderer.accent(palette) : 0xFFB9C2CC;
        int height = HEADER_HEIGHT + rows * ROW_HEIGHT;

        matrices.push();
        try {
            matrices.translate(x, y, z);
            matrices.multiply(facing);
            matrices.scale(scale, -scale, scale);

            float left = -width / 2.0F;
            float top = -height / 2.0F;
            int background = rpgStyle ? 0xC9080B18 : 0xB8000000;
            int headerBackground = rpgStyle ? 0xD4101930 : 0xC322272D;

            // Dark, translucent system glass: the panel stays fixed in front of the
            // camera, while its angular light frame and message rows keep the RPG HUD style.
            if (rpgStyle && ChatExperienceSettings.rpgGlowEnabled) {
                int pulse = ChatExperienceSettings.rpgAnimationEnabled
                    ? 16 + (int)((Math.sin(tick * 0.035) + 1.0) * 8.0) : 20;
                submitBackground(consumers, renderer, matrices, left - 5, top - 5, width + 10, withAlpha(accent, pulse));
                submitBackground(consumers, renderer, matrices, left - 2, top - 2, width + 4, withAlpha(0xFF8BDFFF, pulse / 2));
            }
            submitBackground(consumers, renderer, matrices, left, top, width, background);
            submitBackground(consumers, renderer, matrices, left + 2, top + 2, width - 4, withAlpha(accent, 200));
            submitBackground(consumers, renderer, matrices, left + 3, top + 3, width - 6, background);
            submitBackground(consumers, renderer, matrices, left + 7, top + 5, width - 14, headerBackground);
            submitBackground(consumers, renderer, matrices, left + 7, top + HEADER_HEIGHT - 2, width - 14, 2, withAlpha(accent, 205));
            submitBackground(consumers, renderer, matrices, left + 1, top + 7, 2, 15, withAlpha(0xFF9BE8FF, 235));
            submitBackground(consumers, renderer, matrices, left + width - 3, top + 7, 2, 15, withAlpha(0xFF9BE8FF, 235));
            submitBackground(consumers, renderer, matrices, left + 7, top + height - 3, width - 14, 1, withAlpha(accent, 210));
            drawCorner(consumers, renderer, matrices, left + 2, top + 2, 1, 1, accent);
            drawCorner(consumers, renderer, matrices, left + width - 2, top + 2, -1, 1, accent);
            drawCorner(consumers, renderer, matrices, left + 2, top + height - 2, 1, -1, accent);
            drawCorner(consumers, renderer, matrices, left + width - 2, top + height - 2, -1, -1, accent);

            String title = rpgStyle ? "◈ СИСТЕМА  /  ЧАТ " + (index + 1) : "ЧАТ " + (index + 1);
            drawText(consumers, renderer, matrices, left + 10, top + 6, Text.literal(title).asOrderedText(), 0xFFDCEBFF, 0);
            drawText(consumers, renderer, matrices, left + 10, top + 17,
                Text.literal("КАНАЛ СООБЩЕНИЙ").asOrderedText(), withAlpha(accent, 230), 0);

            float messagesTop = top + HEADER_HEIGHT + 2;
            for (int line = 0; line < rows; line++) {
                int messageIndex = rows - line - 1;
                float rowY = messagesTop + line * ROW_HEIGHT;
                int rowColor = (line & 1) == 0 ? 0x40151A28 : 0x3020142C;
                submitBackground(consumers, renderer, matrices, left + 5, rowY, width - 10, ROW_HEIGHT - 1, rowColor);
                if (messageIndex < visible.size()) {
                    ChatHudLine.Visible visibleLine = visible.get(messageIndex);
                    String raw = orderedTextToString(visibleLine.content());
                    String message = renderer.trimToWidth(raw, Math.max(1, width - 24));
                    drawText(consumers, renderer, matrices, left + 12, rowY + 3, Text.literal(message).asOrderedText(), 0xFFE3E7F0, 0);
                } else if (visible.isEmpty() && line == rows - 1) {
                    drawText(consumers, renderer, matrices, left + 12, rowY + 3,
                        Text.literal("Нет новых сообщений").asOrderedText(), 0xFFAAB6C8, 0);
                }
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
        } finally {
            matrices.pop();
        }
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
