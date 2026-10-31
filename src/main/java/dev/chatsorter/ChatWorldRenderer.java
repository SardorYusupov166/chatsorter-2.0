package dev.chatsorter;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.chatsorter.mixin.ChatHudAccess;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
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
    // Third-person is a world object, not a large overlaid screen.
    private static final float THIRD_PERSON_SCALE = 0.80F;
    private static final double THIRD_PERSON_FORWARD = 2.25;
    private static final double THIRD_PERSON_SIDE = 0.95;
    private static final double THIRD_PERSON_LIFT = 0.22;
    // Rendering happens exclusively on Minecraft's client render thread.
    private static boolean renderWithDepth;
    // 26.2 features must be submitted before the feature rendering pass.
    private static final long LOG_INTERVAL_NANOS = 5_000_000_000L;
    private static final Map<String, String> LAST_PHASES = new HashMap<>();
    private static final Map<String, Long> LAST_LOG_TIMES = new HashMap<>();
    private static final Set<ChatComponent> SUBMITTED_CHATS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static long renderCallbacks;

    private ChatWorldRenderer() { }

    public static void register() {
        // Late pass: stained glass and water have already drawn to the framebuffer.
        LevelRenderEvents.COLLECT_SUBMITS.register(ChatWorldRenderer::render);
        LOGGER.info("[ChatSorter][RPG] Registered world render callback: LevelRenderEvents.COLLECT_SUBMITS");
    }

    public static boolean shouldHideScreenChat(ChatComponent hud) {
        Minecraft client = Minecraft.getInstance();
        // Important: a world-anchored panel must NOT cause vanilla HUD chat to
        // reappear when the player looks up/down or turns away from the panel.
        // World frustum visibility is purely a rendering concern.
        boolean active = ChatHologramLayout.hideVanillaChat(
            ChatExperienceSettings.rpgModeEnabled, client.player != null,
            client.level != null, !client.gui.hud.isHidden(), client.gui.screen() == null);
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
        renderWithDepth = false;
    }

    private static void render(LevelRenderContext renderContext) {
        renderCallbacks++;
        reset();
        try {
            renderFrame(renderContext);
        } catch (Throwable error) {
            reset();
            reportError(error);
        }
    }

    private static void renderFrame(LevelRenderContext renderContext) {
        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        if (!ChatExperienceSettings.rpgModeEnabled || player == null || client.level == null
                || client.gui.hud.isHidden() || client.gui.screen() != null) {
            String reason = !ChatExperienceSettings.rpgModeEnabled ? "RPG mode is off"
                : player == null ? "no player"
                : client.level == null ? "no world"
                : client.gui.hud.isHidden() ? "HUD hidden"
                : "screen open: " + client.gui.screen().getClass().getName();
            report("world", "skipped", "world render skipped: " + reason + "; callbacks=" + renderCallbacks, 0);
            return;
        }

        ArrayList<ChatComponent> chats = new ArrayList<>();
        chats.add(client.gui.hud.getChat());
        chats.addAll(ChatPanelManager.panelHuds());
        if (chats.isEmpty()) return;

        Camera camera = client.gameRenderer.mainCamera();
        Quaternionf cameraFacing = new Quaternionf(camera.rotation());
        // Anchor the hologram to the PLAYER, never to the third-person camera.
        // Follow-view tracks the player's interpolated look yaw, not camera position.
        float tickDelta = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        boolean firstPerson = client.options.getCameraType().isFirstPerson();
        // The third-person player BODY determines the window orientation.
        // Orbiting the camera, including F5 front view, must not move the UI.
        float playerYaw = !firstPerson ? player.getVisualRotationYInDegrees()
            : ChatExperienceSettings.rpgFollowViewEnabled
                ? player.getYRot(tickDelta) : player.getVisualRotationYInDegrees();
        Quaternionf playerFacing = ChatHologramLayout.playerFacing(playerYaw);
        Vec3 playerPos = player.getPosition(tickDelta);
        Vec3 cameraPos = camera.position();
        Vector3f origin = ChatHologramLayout.playerAnchor(
            playerPos.x, playerPos.y, playerPos.z,
            cameraPos.x, cameraPos.y, cameraPos.z,
            playerYaw, player.getEyeY() - player.getY() + 0.25,
            firstPerson ? ChatHologramLayout.PLAYER_DISTANCE : THIRD_PERSON_FORWARD,
            firstPerson ? 0.0 : THIRD_PERSON_SIDE,
            firstPerson ? 0.0 : THIRD_PERSON_LIFT);
        // In third-person the avatar and world are allowed to occlude the
        // hologram; this keeps it from painting on top of the player's body.
        renderWithDepth = !firstPerson;
        // Use the same player-relative yaw for both the anchor and the panel face.
        // Camera rotation is used solely for frustum culling.
        Quaternionf facing = new Quaternionf(playerFacing);
        Vector3f right = playerFacing.transform(new Vector3f(1.0F, 0.0F, 0.0F)).normalize();
        Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F);

        // Vanilla world buffers are no longer available at END_MAIN.
        SubmitNodeCollector consumers = renderContext.submitNodeCollector();
        PoseStack worldMatrices = renderContext.poseStack();
        if (worldMatrices == null) {
            report("world", "missing-context", "world render matrix stack unavailable", LOG_INTERVAL_NANOS);
            return;
        }
        Font textRenderer = client.font;
        int tick = player.tickCount;
        int widest = 100;
        for (ChatComponent hud : chats) widest = Math.max(widest, effectiveWidth(client, hud));
        double aspect = client.getWindow().getWidth()
            / (double)Math.max(1, client.getWindow().getHeight());
        double fov = client.options.fov().get();
        ChatHologramLayout.Arrangement layout = ChatHologramLayout.arrange(widest,
            HEADER_HEIGHT + MAX_WORLD_ROWS * ROW_HEIGHT, chats.size(), ChatExperienceSettings.rpgPanelPosition,
            worldScale(client) * (firstPerson ? 1.0F : THIRD_PERSON_SCALE)
                * (chats.size() == 1 ? 1.0F : 0.55F), fov, aspect);
        double primaryDepth = 0;
        for (int i = 0; i < chats.size(); i++) {
            ChatComponent hud = chats.get(i);
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
        List<GuiMessage.Line> mainMessages = ((ChatHudAccess)client.gui.hud.getChat()).chatsorter$getVisibleMessages();
        report("world", SUBMITTED_CHATS.isEmpty() ? "outside-view" : "submitted",
            "world hologram vertices submitted (not a GPU visibility confirmation): callbacks=" + renderCallbacks
            + ", version=" + ChatSorter.VERSION + ", chats=" + chats.size()
            + ", inView=" + SUBMITTED_CHATS.size() + ", primaryDepth=" + primaryDepth
            + ", followView=" + ChatExperienceSettings.rpgFollowViewEnabled
            + ", anchor=player, orientation=player-yaw, playerYaw=" + playerYaw
            + ", perspective=" + (firstPerson ? "first" : "third")
            + ", depthTest=" + renderWithDepth
            + ", side=" + (firstPerson ? 0.0 : THIRD_PERSON_SIDE)
            + ", cameraOffset=" + cameraPos.distanceTo(playerPos)
            + ", position=" + ChatExperienceSettings.rpgPanelPosition + ", scale=" + layout.scale()
            + ", fov=" + fov + ", aspect=" + aspect + ", mainVisibleRows=" + mainMessages.size()
            + ", consumers=" + className(consumers) + ", event=COLLECT_SUBMITS"
            + ", matrices=" + className(worldMatrices) + ", screen=" + client.gui.screen(),
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

    private static int effectiveWidth(Minecraft client, ChatComponent hud) {
        int width = ChatPanelManager.width(hud);
        if (width < 0) width = ChatPosition.width > 0
            ? ChatPosition.width : ChatComponent.getWidth(client.options.chatWidth().get());
        return Math.max(100, Math.min(1200, width));
    }

    private static float worldScale(Minecraft client) {
        return WORLD_SCALE * (float)Math.max(0.65, Math.min(1.35, client.options.chatScale().get()));
    }

    private static void drawChat(SubmitNodeCollector consumers, Font renderer, PoseStack matrices,
                                 ChatComponent hud, int index, int width,
                                 double x, double y, double z, Quaternionf facing, int tick, float scale) {
        List<GuiMessage.Line> visible = ((ChatHudAccess)hud).chatsorter$getVisibleMessages();
        int rows = MAX_WORLD_ROWS;
        int palette = ChatPanelManager.palette(hud);
        boolean rpgStyle = ChatPanelManager.usesRpgStyle(hud);
        int accent = rpgStyle ? ChatRpgRenderer.accent(palette) : 0xFFB9C2CC;
        int height = HEADER_HEIGHT + rows * ROW_HEIGHT;

        matrices.pushPose();
        try {
            matrices.translate(x, y, z);
            matrices.mulPose(facing);
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
                Component.literal(renderer.plainSubstrByWidth(title, width - 20)).getVisualOrderText(), 0xFFDCEBFF);
            drawText(consumers, renderer, matrices, left + 10, top + 17,
                Component.literal(renderer.plainSubstrByWidth("КАНАЛ СООБЩЕНИЙ", width - 20)).getVisualOrderText(), withAlpha(accent, 230));

            float messagesTop = top + HEADER_HEIGHT;
            for (int line = 0; line < rows; line++) {
                int messageIndex = rows - line - 1;
                float rowY = messagesTop + line * ROW_HEIGHT;
                if (messageIndex < visible.size()) {
                    GuiMessage.Line visibleLine = visible.get(messageIndex);
                    // ChatHud already provides wrapped, style-preserving lines.
                    // Keep original colors, font glyphs, ranks and player head icons.
                    FormattedCharSequence styledLine = visibleLine.content();
                    drawText(consumers, renderer, matrices, left + 12, rowY + 3,
                        styledLine, 0xFFFFFFFF);
                } else if (visible.isEmpty() && line == rows - 1) {
                    drawText(consumers, renderer, matrices, left + 12, rowY + 3,
                        Component.literal(renderer.plainSubstrByWidth("Нет новых сообщений", width - 24)).getVisualOrderText(), 0xFFAAB6C8);
                }
            }
        } finally {
            matrices.popPose();
        }
    }

    private static void submitBackground(SubmitNodeCollector consumers, PoseStack matrices,
                                         float x, float y, int width, int height, int color) {
        if (width <= 0 || height <= 0 || (color >>> 24) == 0) return;
        consumers.submitCustomGeometry(matrices,
            renderWithDepth ? RenderTypes.textBackground() : RenderTypes.textBackgroundSeeThrough(),
            (pose, vertices) -> ChatHologramQuad.emit(vertices, pose.pose(), x, y, width, height, color));
    }

    private static void drawBorder(SubmitNodeCollector consumers, PoseStack matrices,
                                   float x, float y, int width, int height, int thickness, int color) {
        submitBackground(consumers, matrices, x, y, width, thickness, color);
        submitBackground(consumers, matrices, x, y + height - thickness, width, thickness, color);
        submitBackground(consumers, matrices, x, y + thickness, thickness, height - 2 * thickness, color);
        submitBackground(consumers, matrices, x + width - thickness, y + thickness, thickness, height - 2 * thickness, color);
    }

    private static void drawCorner(SubmitNodeCollector consumers, PoseStack matrices,
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

    private static void drawText(SubmitNodeCollector consumers, Font renderer, PoseStack matrices,
                                 float x, float y, net.minecraft.util.FormattedCharSequence text, int color) {
        consumers.submitText(matrices, x, y, text, false,
            renderWithDepth ? Font.DisplayMode.NORMAL : Font.DisplayMode.SEE_THROUGH,
            FULL_BRIGHT, color, 0, 0);
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }
}
