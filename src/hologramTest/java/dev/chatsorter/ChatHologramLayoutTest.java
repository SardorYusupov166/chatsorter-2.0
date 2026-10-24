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
