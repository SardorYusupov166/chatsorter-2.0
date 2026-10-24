package dev.chatsorter;

import dev.chatsorter.mixin.ChatHudAccess;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.text.OrderedText;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
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
    // An avatar-anchored panel is much closer than the old camera-relative panel.
    private static final float WORLD_SCALE = 0.0052F;
    // END_MAIN is after vanilla's world buffers have been flushed.
    private static final BufferAllocator HOLOGRAM_ALLOCATOR = new BufferAllocator(128 * 1024);
    private static final VertexConsumerProvider.Immediate HOLOGRAM_CONSUMERS = VertexConsumerProvider.immediate(HOLOGRAM_ALLOCATOR);
    private static final long LOG_INTERVAL_NANOS = 5_000_000_000L;
    private static final Map<String, String> LAST_PHASES = new HashMap<>();
    private static final Map<String, Long> LAST_LOG_TIMES = new HashMap<>();
    private static final Set<ChatHud> SUBMITTED_CHATS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static long renderCallbacks;

    private ChatWorldRenderer() { }

    public static void register() {
        // Late pass: stained glass and water have already drawn to the framebuffer.
        WorldRenderEvents.END_MAIN.register(ChatWorldRenderer::render);
        LOGGER.info("[ChatSorter][RPG] Registered world render callback: WorldRenderEvents.END_MAIN");
    }

    public static boolean shouldHideScreenChat(ChatHud hud) {
        MinecraftClient client = MinecraftClient.getInstance();
        // Important: a world-anchored panel must NOT cause vanilla HUD chat to
        // reappear when the player looks up/down or turns away from the panel.
        // World frustum visibility is purely a rendering concern.
        boolean active = ChatHologramLayout.hideVanillaChat(
            ChatExperienceSettings.rpgModeEnabled, client.player != null,
            client.world != null, !client.options.hudHidden, client.currentScreen == null);
        String reason = !active ? "RPG inactive, HUD disabled, or another screen open"
            : SUBMITTED_CHATS.contains(hud)
                ? "world hologram submitted; vanilla HUD suppressed"
                : "world hologram outside view/not submitted; vanilla HUD still suppressed";
        report("hud:" + ChatPanelManager.id(hud), active ? "suppressed" : "vanilla",
            reason, active ? LOG_INTERVAL_NANOS : 0);
        return active;
    }

    public static void reset() {
        SUBMITTED_CHATS.clear();
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
        // Anchor the hologram to the PLAYER, never to the third-person camera.
        // Follow-view tracks the player's interpolated look yaw, not camera position.
        float tickDelta = client.getRenderTickCounter().getTickProgress(true);
        float playerYaw = ChatExperienceSettings.rpgFollowViewEnabled
            ? player.getLerpedYaw(tickDelta) : player.getBodyYaw();
        Quaternionf playerFacing = ChatHologramLayout.facing(cameraFacing, playerYaw, false);
        Vec3d playerPos = player.getLerpedPos(tickDelta);
        Vec3d cameraPos = camera.getCameraPos();
        Vector3f origin = ChatHologramLayout.playerAnchor(
            playerPos.x, playerPos.y, playerPos.z,
            cameraPos.x, cameraPos.y, cameraPos.z,
            playerYaw, player.getEyeY() - player.getY() + 0.25);
        // Screen-facing billboard affects readability only, not world placement.
        Quaternionf facing = cameraFacing;
        Vector3f right = playerFacing.transform(new Vector3f(1.0F, 0.0F, 0.0F)).normalize();
        Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F);

        // Vanilla world buffers are no longer available at END_MAIN.
        VertexConsumerProvider.Immediate consumers = HOLOGRAM_CONSUMERS;
        MatrixStack worldMatrices = renderContext.matrices();
        if (worldMatrices == null) {
            report("world", "missing-context", "world render matrix stack unavailable", LOG_INTERVAL_NANOS);
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
            double anchorX = origin.x + right.x * side + up.x * lift;
            double anchorY = origin.y + right.y * side + up.y * lift;
            double anchorZ = origin.z + right.z * side + up.z * lift;
            Vector3f center = new Vector3f((float)anchorX, (float)anchorY, (float)anchorZ);
            if (i == 0) primaryDepth = center.dot(ChatHologramLayout.forward(cameraFacing));
            if (!ChatHologramLayout.inView(cameraFacing, facing, center, width,
                    HEADER_HEIGHT + MAX_WORLD_ROWS * ROW_HEIGHT, scale, fov, aspect)) continue;
            drawChat(consumers, textRenderer, worldMatrices, hud, i, width,
                anchorX, anchorY, anchorZ, facing, tick, scale);
            SUBMITTED_CHATS.add(hud);
        }
        // Flush before the world render pass finishes: otherwise the panel disappears.
        consumers.draw();
        List<ChatHudLine.Visible> mainMessages = ((ChatHudAccess)client.inGameHud.getChatHud()).chatsorter$getVisibleMessages();
        report("world", SUBMITTED_CHATS.isEmpty() ? "outside-view" : "submitted",
            "world hologram vertices submitted (not a GPU visibility confirmation): callbacks=" + renderCallbacks
            + ", version=" + ChatSorter.VERSION + ", chats=" + chats.size()
            + ", inView=" + SUBMITTED_CHATS.size() + ", primaryDepth=" + primaryDepth
            + ", followView=" + ChatExperienceSettings.rpgFollowViewEnabled
            + ", anchor=player, playerYaw=" + playerYaw
            + ", cameraOffset=" + cameraPos.distanceTo(playerPos)
            + ", position=" + ChatExperienceSettings.rpgPanelPosition + ", scale=" + layout.scale()
            + ", fov=" + fov + ", aspect=" + aspect + ", mainVisibleRows=" + mainMessages.size()
            + ", consumers=" + className(consumers) + ", event=END_MAIN"
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

            // Keep the glass, header and rows disjoint: the shared translucent
            // layer sorts quads by distance, not by our submission order.
            if (rpgStyle && ChatExperienceSettings.rpgGlowEnabled) {
                int pulse = ChatExperienceSettings.rpgAnimationEnabled
                    ? 32 + (int)((Math.sin(tick * 0.035) + 1.0) * 8.0) : 40;
                // Vanilla's text-background shader discards alpha below 0.1.
                drawBorder(consumers, matrices, left - 5, top - 5, width + 10, height + 10, 3,
                    withAlpha(accent, pulse));
                drawBorder(consumers, matrices, left - 2, top - 2, width + 4, height + 4, 2,
                    withAlpha(0xFF8BDFFF, 32));
            }
            drawBorder(consumers, matrices, left, top, width, height, 1, withAlpha(accent, 210));
            submitBackground(consumers, matrices, left + 1, top + 1, width - 2, HEADER_HEIGHT - 3, headerBackground);
            submitBackground(consumers, matrices, left + 1, top + HEADER_HEIGHT - 2, width - 2, 2, withAlpha(accent, 205));
            for (int line = 0; line < rows; line++) {
                float rowY = top + HEADER_HEIGHT + line * ROW_HEIGHT;
                int rowColor = !rpgStyle ? background : (line & 1) == 0 ? 0xC9101526 : 0xC9131022;
                submitBackground(consumers, matrices, left + 1, rowY, width - 2, ROW_HEIGHT - 1, rowColor);
                if (line < rows - 1) {
                    submitBackground(consumers, matrices, left + 1, rowY + ROW_HEIGHT - 1, width - 2, 1, background);
                }
            }
            drawCorner(consumers, matrices, left + 2, top + 2, 1, 1, accent);
            drawCorner(consumers, matrices, left + width - 2, top + 2, -1, 1, accent);
            drawCorner(consumers, matrices, left + 2, top + height - 2, 1, -1, accent);
            drawCorner(consumers, matrices, left + width - 2, top + height - 2, -1, -1, accent);

            if (rpgStyle && ChatExperienceSettings.rpgParticlesEnabled) {
                double phase = tick * 0.045 + index * 1.7;
                float particleX = (float)(left + 14 + (width - 28) * ((Math.sin(phase) + 1.0) * 0.5));
                submitBackground(consumers, matrices, particleX, top - 3, 3, 3, 0xD09BE8FF);
                submitBackground(consumers, matrices, left + width, top + height - 7, 2, 2, 0xC09D82FF);
            }
            if (rpgStyle && ChatExperienceSettings.rpgAnimationEnabled) {
                int scanY = Math.floorMod(tick + index * 19, rows * ROW_HEIGHT - 12);
                // Keep moving effects in the margin, clear of message glyphs.
                submitBackground(consumers, matrices, left + 2, top + HEADER_HEIGHT + scanY, 1, 10, 0x609BE8FF);
            }

            // Submit every piece of decoration before text, so a later background
            // batch cannot paint over glyphs when the font atlas changes.
            String title = rpgStyle ? "◈ СИСТЕМА  /  ЧАТ " + (index + 1) : "ЧАТ " + (index + 1);
            drawText(consumers, renderer, matrices, left + 10, top + 6,
                Text.literal(renderer.trimToWidth(title, width - 20)).asOrderedText(), 0xFFDCEBFF);
            drawText(consumers, renderer, matrices, left + 10, top + 17,
                Text.literal(renderer.trimToWidth("КАНАЛ СООБЩЕНИЙ", width - 20)).asOrderedText(), withAlpha(accent, 230));

            float messagesTop = top + HEADER_HEIGHT;
            for (int line = 0; line < rows; line++) {
                int messageIndex = rows - line - 1;
                float rowY = messagesTop + line * ROW_HEIGHT;
                if (messageIndex < visible.size()) {
                    ChatHudLine.Visible visibleLine = visible.get(messageIndex);
                    // ChatHud already provides wrapped, style-preserving lines.
                    // Keep original colors, font glyphs, ranks and player head icons.
                    OrderedText styledLine = visibleLine.content();
                    drawText(consumers, renderer, matrices, left + 12, rowY + 3,
                        styledLine, 0xFFFFFFFF);
                } else if (visible.isEmpty() && line == rows - 1) {
                    drawText(consumers, renderer, matrices, left + 12, rowY + 3,
                        Text.literal(renderer.trimToWidth("Нет новых сообщений", width - 24)).asOrderedText(), 0xFFAAB6C8);
                }
            }
        } finally {
            matrices.pop();
        }
    }

    private static void submitBackground(VertexConsumerProvider consumers, MatrixStack matrices,
                                         float x, float y, int width, int height, int color) {
        if (width <= 0 || height <= 0 || (color >>> 24) == 0) return;
        ChatHologramQuad.emit(consumers.getBuffer(RenderLayers.textBackgroundSeeThrough()),
            matrices.peek().getPositionMatrix(), x, y, width, height, color);
    }

    private static void drawBorder(VertexConsumerProvider consumers, MatrixStack matrices,
                                   float x, float y, int width, int height, int thickness, int color) {
        submitBackground(consumers, matrices, x, y, width, thickness, color);
        submitBackground(consumers, matrices, x, y + height - thickness, width, thickness, color);
        submitBackground(consumers, matrices, x, y + thickness, thickness, height - 2 * thickness, color);
        submitBackground(consumers, matrices, x + width - thickness, y + thickness, thickness, height - 2 * thickness, color);
    }

    private static void drawCorner(VertexConsumerProvider consumers, MatrixStack matrices,
                                   float x, float y, int directionX, int directionY, int accent) {
        int bright = withAlpha(accent, 245);
        float horizontalX = directionX > 0 ? x : x - 9;
        float verticalY = directionY > 0 ? y : y - 8;
        submitBackground(consumers, matrices, horizontalX, y, 9, 1, bright);
        submitBackground(consumers, matrices, x, verticalY, 1, 8, bright);
        float innerX = x + directionX * 4;
        float innerY = y + directionY * 4;
        submitBackground(consumers, matrices, Math.min(x, innerX), innerY, 5, 1, withAlpha(0xFF9BE8FF, 225));
    }

    private static void drawText(VertexConsumerProvider consumers, TextRenderer renderer, MatrixStack matrices,
                                 float x, float y, net.minecraft.text.OrderedText text, int color) {
        renderer.draw(text, x, y, color, false, matrices.peek().getPositionMatrix(), consumers,
            TextRenderer.TextLayerType.SEE_THROUGH, 0, FULL_BRIGHT);
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }
}
