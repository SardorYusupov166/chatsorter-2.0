package dev.chatsorter;

import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Camera-relative geometry, kept independent of Minecraft for regression tests. */
final class ChatHologramLayout {
    static final double PLAYER_DISTANCE = 1.85; // World blocks in front of the avatar.
    static final double DISTANCE = PLAYER_DISTANCE; // Match layout fitting to the world anchor.
    private static final double VIEW_MARGIN = 0.88;
    private static final int CELL_PADDING = 24;

    private ChatHologramLayout() { }

    /** Off-screen holograms must never enable the ordinary HUD chat. */
    static boolean hideVanillaChat(boolean rpgEnabled, boolean playerPresent,
                                   boolean worldPresent, boolean hudVisible, boolean noScreen) {
        return rpgEnabled && playerPresent && worldPresent && hudVisible && noScreen;
    }

    static Quaternionf facing(Quaternionfc cameraRotation, float bodyYaw, boolean followView) {
        if (followView || !Float.isFinite(bodyYaw)) return new Quaternionf(cameraRotation);
        // Minecraft's camera looks down local -Z, including in yaw-only mode.
        return new Quaternionf().rotationY((float)(Math.PI - Math.toRadians(bodyYaw)));
    }

    static Vector3f forward(Quaternionfc rotation) {
        return rotation.transform(new Vector3f(0.0F, 0.0F, -1.0F)).normalize();
    }

    /**
     * Position the panel in front of the avatar in camera-relative world coordinates.
     * Subtracting camera XYZ is essential: a third-person camera must not move the
     * panel in world space. Calculate in double precision before casting to float.
     */
    static Vector3f playerAnchor(double playerX, double playerY, double playerZ,
                                 double cameraX, double cameraY, double cameraZ,
                                 float playerYaw, double eyeOffset) {
        Quaternionf playerFacing = new Quaternionf()
            .rotationY((float)(Math.PI - Math.toRadians(playerYaw)));
        Vector3f ahead = forward(playerFacing);
        return new Vector3f(
            (float)(playerX - cameraX + ahead.x * PLAYER_DISTANCE),
            (float)(playerY - cameraY + eyeOffset),
            (float)(playerZ - cameraZ + ahead.z * PLAYER_DISTANCE));
    }

    static Arrangement arrange(int width, int height, int count, int position,
                               float preferredScale, double verticalFov, double aspect) {
        int safeWidth = Math.max(100, Math.min(1200, width));
        int safeHeight = Math.max(54, Math.min(1000, height));
        int safeCount = Math.max(1, Math.min(5, count));
        int columns = safeCount == 1 ? 1 : 2;
        int rows = (safeCount + columns - 1) / columns;
        double halfHeight = DISTANCE * Math.tan(Math.toRadians(safeFov(verticalFov)) * 0.5) * VIEW_MARGIN;
        double halfWidth = halfHeight * safeAspect(aspect);
        double cellWidth = safeWidth + CELL_PADDING;
        double cellHeight = safeHeight + CELL_PADDING;
        double requested = Float.isFinite(preferredScale) && preferredScale > 0 ? preferredScale : 0.0078;
        double scale = Math.min(requested, Math.min(2 * halfWidth / (columns * cellWidth),
            2 * halfHeight / (rows * cellHeight)));
        double freeX = Math.max(0, halfWidth - columns * cellWidth * scale * 0.5);
        double freeY = Math.max(0, halfHeight - rows * cellHeight * scale * 0.5);
        double centerX = position == 1 ? -freeX : position == 2 ? freeX : 0;
        double centerY = position == 3 ? freeY : position == 4 ? -freeY : 0;
        return new Arrangement((float)scale, safeCount, columns, rows,
            cellWidth * scale, cellHeight * scale, centerX, centerY);
    }

    /** Conservative four-corner frustum test; never treat a panel behind the camera as visible. */
    static boolean inView(Quaternionfc cameraRotation, Quaternionfc panelRotation, Vector3fc center,
                          int width, int height, float scale, double verticalFov, double aspect) {
        Quaternionf inverseView = new Quaternionf(cameraRotation).conjugate();
        double tanY = Math.tan(Math.toRadians(safeFov(verticalFov)) * 0.5);
        double tanX = tanY * safeAspect(aspect);
        boolean near = false, left = false, right = false, top = false, bottom = false;
        for (int sx : new int[]{-1, 1}) {
            for (int sy : new int[]{-1, 1}) {
                Vector3f point = new Vector3f(sx * (width + 12) * scale * 0.5F,
                    sy * (height + 12) * scale * 0.5F, 0);
                panelRotation.transform(point).add(center);
                inverseView.transform(point);
                double depth = -point.z;
                near |= depth >= 0.05;
                left |= point.x >= -depth * tanX;
                right |= point.x <= depth * tanX;
                bottom |= point.y >= -depth * tanY;
                top |= point.y <= depth * tanY;
            }
        }
        return near && left && right && top && bottom;
    }

    private static double safeFov(double value) {
        return Double.isFinite(value) ? Math.max(1, Math.min(175, value)) : 70;
    }

    private static double safeAspect(double value) {
        return Double.isFinite(value) && value > 0 ? value : 16.0 / 9.0;
    }

    record Arrangement(float scale, int count, int columns, int rows,
                       double cellWidth, double cellHeight, double centerX, double centerY) {
        double side(int index) {
            int row = index / columns;
            int columnsInRow = Math.min(columns, count - row * columns);
            return centerX + (index % columns - (columnsInRow - 1) * 0.5) * cellWidth;
        }

        double lift(int index) {
            return centerY + ((rows - 1) * 0.5 - index / columns) * cellHeight;
        }
    }
}
