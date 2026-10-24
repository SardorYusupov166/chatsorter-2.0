package dev.chatsorter;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Standalone regression checks for camera direction and panels leaving the frustum. */
public final class ChatHologramLayoutTest {
    private static int checks;

    public static void main(String[] args) {
        cameraDirections();
        bodyTracking();
        thirdPersonOrientationRegression();
        playerAnchoringRegression();
        thirdPersonPlacementRegression();
        hudFallbackRegression();
        behindCameraRegression();
        fittedLayouts();
        invalidInput();
        System.out.println("Hologram geometry: " + checks + " checks passed");
    }

    private static void cameraDirections() {
        for (float yaw : new float[]{0, 45, 90, 180, 270, 359}) {
            for (float pitch : new float[]{-89, -45, 0, 45, 89}) {
                Quaternionf camera = camera(yaw, pitch);
                Quaternionf facing = ChatHologramLayout.facing(camera, 0, true);
                Vector3f actual = ChatHologramLayout.forward(facing);
                double yawRad = Math.toRadians(yaw), pitchRad = Math.toRadians(pitch);
                Vector3f expected = new Vector3f((float)(-Math.sin(yawRad) * Math.cos(pitchRad)),
                    (float)-Math.sin(pitchRad), (float)(Math.cos(yawRad) * Math.cos(pitchRad)));
                require(actual.distance(expected) < 0.00001, "Wrong camera direction at " + yaw + ", " + pitch);
                Vector3f center = new Vector3f(actual).mul((float)ChatHologramLayout.DISTANCE);
                require(ChatHologramLayout.inView(camera, facing, center, 320, 170, 0.0078F, 70, 16.0 / 9),
                    "A centered panel must be visible at all camera angles");
            }
        }
    }

    private static void bodyTracking() {
        Quaternionf camera = camera(123, 73);
        for (float yaw : new float[]{0, 90, 180, 270}) {
            Vector3f forward = ChatHologramLayout.forward(ChatHologramLayout.facing(camera, yaw, false));
            Vector3f expected = new Vector3f((float)-Math.sin(Math.toRadians(yaw)), 0,
                (float)Math.cos(Math.toRadians(yaw)));
            require(forward.distance(expected) < 0.00001, "Body orientation must ignore camera pitch and yaw");
        }
    }

    private static void thirdPersonOrientationRegression() {
        // A third-person camera can orbit, tilt or look at the avatar from
        // either side without rotating the world-anchored chat hologram.
        for (float playerYaw : new float[]{0, 45, 90, 180, 270}) {
            Quaternionf avatarFacing = ChatHologramLayout.playerFacing(playerYaw);
            Vector3f expectedForward = ChatHologramLayout.forward(avatarFacing);
            for (float cameraYaw : new float[]{0, 90, 180, 270}) {
                for (float cameraPitch : new float[]{-65, 0, 65}) {
                    Quaternionf cameraFacing = camera(cameraYaw, cameraPitch);
                    Quaternionf result = ChatHologramLayout.facing(cameraFacing, playerYaw, false);
                    require(ChatHologramLayout.forward(result).distance(expectedForward) < 0.00001,
                        "Camera orbit must not rotate the avatar-facing hologram");
                    Vector3f readableNormal = result.transform(new Vector3f(0, 0, 1));
                    require(readableNormal.dot(expectedForward) < -0.99999,
                        "Readable front face must point back toward the avatar");
                }
            }
        }
    }

    private static void playerAnchoringRegression() {
        // Moving/orbiting the camera around a stationary avatar must not move
        // the hologram's WORLD location (camera-relative coordinates do change).
        Vector3f first = ChatHologramLayout.playerAnchor(
            100, 64, -20, 100, 66, -24, 0, 1.8);
        Vector3f second = ChatHologramLayout.playerAnchor(
            100, 64, -20, 104, 67, -18, 0, 1.8);
        require(first.add(100, 66, -24).distance(second.add(104, 67, -18)) < 0.0001,
            "Camera movement must not move a player-anchored hologram");

        Vector3f base = ChatHologramLayout.playerAnchor(0, 60, 0, 0, 60, 0, 0, 1.8);
        Vector3f moved = ChatHologramLayout.playerAnchor(3, 60, -2, 0, 60, 0, 0, 1.8);
        require(Math.abs((moved.x - base.x) - 3) < 0.0001
                && Math.abs((moved.z - base.z) + 2) < 0.0001,
            "Hologram must translate with the avatar");
        require(Math.abs(base.y - 1.8) < 0.0001
                && Math.abs(base.z - ChatHologramLayout.PLAYER_DISTANCE) < 0.0001,
            "Hologram should be at player eye level, in front of the avatar");

        Vector3f turned = ChatHologramLayout.playerAnchor(0, 60, 0, 0, 60, 0, 90, 1.8);
        require(Math.abs(turned.x + ChatHologramLayout.PLAYER_DISTANCE) < 0.0001
                && Math.abs(turned.z) < 0.0001,
            "Hologram must rotate around the avatar, not the camera");
    }

    private static void thirdPersonPlacementRegression() {
        // Third-person camera movement must not influence the world-space
        // position of the window; offsets are relative to the avatar.
        double forward = 2.25, side = 0.95, lift = 0.22;
        Vector3f cameraA = ChatHologramLayout.playerAnchor(
            100, 64, -20, 100, 66, -24, 0, 1.8, forward, side, lift);
        Vector3f cameraB = ChatHologramLayout.playerAnchor(
            100, 64, -20, 104, 67, -18, 0, 1.8, forward, side, lift);
        require(cameraA.add(100, 66, -24).distance(cameraB.add(104, 67, -18)) < 0.0001,
            "Third-person camera orbit must not move the floating window");
        for (float yaw : new float[]{0, 90, 180, 270}) {
            Quaternionf rotation = ChatHologramLayout.playerFacing(yaw);
            Vector3f origin = ChatHologramLayout.playerAnchor(
                0, 64, 0, 0, 64, 0, yaw, 1.8, forward, side, lift);
            Vector3f ahead = ChatHologramLayout.forward(rotation);
            Vector3f right = rotation.transform(new Vector3f(1, 0, 0));
            float along = origin.dot(ahead), lateral = origin.dot(right);
            require(Math.abs(along - forward) < 0.0001,
                "Hologram must float in front of the avatar");
            require(Math.abs(lateral - side) < 0.0001,
                "Hologram must not cover the avatar's centerline");
            require(Math.abs(origin.y - 1.8 - lift) < 0.0001,
                "Hologram height must follow player eye level");
        }
    }

    private static void hudFallbackRegression() {
        // The HUD must not come back when the hologram is outside the frustum.
        boolean active = ChatHologramLayout.hideVanillaChat(true, true, true, true, true);
        require(active, "RPG mode must always hide vanilla HUD while world gameplay is active");
        require(!ChatHologramLayout.hideVanillaChat(false, true, true, true, true),
            "Vanilla HUD must be restored when RPG mode is disabled");
        require(!ChatHologramLayout.hideVanillaChat(true, true, true, true, false),
            "Vanilla HUD must be available in chat and other screens");
        require(!ChatHologramLayout.hideVanillaChat(true, false, true, true, true),
            "No player should not enable RPG HUD suppression");
        require(!ChatHologramLayout.hideVanillaChat(true, true, false, true, true),
            "No world should not enable RPG HUD suppression");
        require(!ChatHologramLayout.hideVanillaChat(true, true, true, false, true),
            "Hidden HUD should not require suppression");
    }

    private static void behindCameraRegression() {
        for (float yaw : new float[]{0, 90, 180, 270}) {
            Quaternionf camera = camera(yaw, 0);
            // alpha.18 used camera-local +Z, which is behind the viewer.
            Vector3f oldAnchor = camera.transform(new Vector3f(0, 0, 2.2F));
            require(!ChatHologramLayout.inView(camera, camera, oldAnchor, 320, 170, 0.0078F, 70, 16.0 / 9),
                "Regression: the old +Z position must be rejected as behind the camera");
        }
    }

    private static void fittedLayouts() {
        Quaternionf camera = camera(47, -31);
        Vector3f forward = ChatHologramLayout.forward(camera);
        for (double aspect : new double[]{0.5, 1, 16.0 / 9, 21.0 / 9}) {
            for (double fov : new double[]{30, 70, 110}) {
                Matrix4f projection = new Matrix4f().perspective((float)Math.toRadians(fov), (float)aspect, 0.05F, 100);
                for (int inputWidth : new int[]{-100, 0, 100, 320, 1200}) {
                    int width = Math.max(100, Math.min(1200, inputWidth));
                    for (int count = 1; count <= 5; count++) {
                        for (int position = 0; position < 5; position++) {
                            for (float requestedScale : new float[]{0.003F, 0.0078F, 0.011F}) {
                                var layout = ChatHologramLayout.arrange(inputWidth, 170, count, position,
                                    requestedScale, fov, aspect);
                                for (int i = 0; i < count; i++) {
                                    Vector3f offset = new Vector3f((float)layout.side(i), (float)layout.lift(i), 0);
                                    Vector3f center = camera.transform(offset).fma((float)ChatHologramLayout.DISTANCE, forward);
                                    require(ChatHologramLayout.inView(camera, camera, center, width, 170,
                                        layout.scale(), fov, aspect), "Fitted panel was culled");
                                    for (int sx : new int[]{-1, 1}) {
                                        for (int sy : new int[]{-1, 1}) {
                                            // Check projected corners, including the glowing frame margin.
                                            Vector3f point = new Vector3f(sx * (width + 12) * layout.scale() * 0.5F,
                                                sy * 182 * layout.scale() * 0.5F, 0);
                                            camera.transform(point).add(center);
                                            new Quaternionf(camera).conjugate().transform(point);
                                            Vector4f clip = projection.transform(new Vector4f(point, 1));
                                            require(clip.w > 0 && Math.abs(clip.x / clip.w) <= 1.00001
                                                    && Math.abs(clip.y / clip.w) <= 1.00001,
                                                "Panel corner outside viewport: width=" + inputWidth + ", count=" + count
                                                    + ", preset=" + position + ", aspect=" + aspect + ", fov=" + fov);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void invalidInput() {
        for (float scale : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
            var layout = ChatHologramLayout.arrange(0, -1, 0, -1, scale, Double.NaN, 0);
            require(Float.isFinite(layout.scale()) && layout.scale() > 0, "Invalid settings must produce a finite positive scale");
        }
    }

    private static Quaternionf camera(float yaw, float pitch) {
        // Minecraft 1.21.11 Camera.setRotation uses these axes and angles.
        return new Quaternionf().rotationYXZ((float)(Math.PI - Math.toRadians(yaw)),
            (float)-Math.toRadians(pitch), 0);
    }

    private static void require(boolean result, String message) {
        checks++;
        if (!result) throw new AssertionError(message);
    }
}
