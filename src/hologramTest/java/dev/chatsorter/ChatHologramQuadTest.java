package dev.chatsorter;

import net.minecraft.client.render.VertexConsumer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

/** Checks submitted vertices without a font atlas or graphics device. */
public final class ChatHologramQuadTest {
    private static int checks;

    public static void main(String[] args) {
        Matrix4f transform = new Matrix4f().translation(2, 4, -3).rotateY(0.7F).scale(0.0078F, -0.0078F, 0.0078F);
        Matrix4f inverse = new Matrix4f(transform).invert();
        for (int width : new int[]{1, 2, 100, 320, 1200}) {
            for (int height : new int[]{1, 3, 13, 170}) {
                Recorder recorder = new Recorder();
                ChatHologramQuad.emit(recorder.consumer, transform, -50, -85, width, height, 0xC9080B18);
                require(recorder.vertices.size() == 4, "A rectangle must use one quad at every size");
                float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
                float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
                for (Vertex vertex : recorder.vertices) {
                    Vector3f point = inverse.transformPosition(new Vector3f(vertex.point));
                    minX = Math.min(minX, point.x); maxX = Math.max(maxX, point.x);
                    minY = Math.min(minY, point.y); maxY = Math.max(maxY, point.y);
                    require(Math.abs(point.z) < 0.001, "Quad must stay on the panel plane");
                    require(vertex.color == 0xC9080B18, "Glass alpha must reach the buffer unchanged");
                    require(vertex.light == 0x00F000F0, "Vertices must include full-bright light coordinates");
                }
                require(Math.abs(minX + 50) < 0.001 && Math.abs(minY + 85) < 0.001, "Wrong rectangle origin");
                require(Math.abs(maxX - minX - width) < 0.001, "Wide panels must not be truncated to a string of spaces");
                require(Math.abs(maxY - minY - height) < 0.001, "One-pixel borders must not become font-height backgrounds");
            }
        }
        for (float size : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
            Recorder recorder = new Recorder();
            ChatHologramQuad.emit(recorder.consumer, transform, 0, 0, size, 10, -1);
            ChatHologramQuad.emit(recorder.consumer, transform, 0, 0, 10, size, -1);
            require(recorder.vertices.isEmpty(), "Invalid dimensions must not submit geometry");
        }
        Recorder recorder = new Recorder();
        ChatHologramQuad.emit(recorder.consumer, transform, 0, 0, 10, 10, 0x00FFFFFF);
        ChatHologramQuad.emit(recorder.consumer, transform, Float.NaN, 0, 10, 10, -1);
        require(recorder.vertices.isEmpty(), "Invisible or invalid rectangles must be skipped");
        System.out.println("Hologram vertex submission: " + checks + " checks passed");
    }

    private static final class Vertex {
        final Vector3f point;
        int color, light;
        Vertex(float x, float y, float z) { point = new Vector3f(x, y, z); }
    }

    private static final class Recorder implements InvocationHandler {
        final ArrayList<Vertex> vertices = new ArrayList<>();
        final VertexConsumer consumer = (VertexConsumer)Proxy.newProxyInstance(
            VertexConsumer.class.getClassLoader(), new Class<?>[]{VertexConsumer.class}, this);

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // Exercise Minecraft's real matrix and packed-light default methods.
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
            switch (method.getName()) {
                case "vertex" -> vertices.add(new Vertex((float)args[0], (float)args[1], (float)args[2]));
                case "color" -> vertices.getLast().color = (int)args[0];
                case "light" -> vertices.getLast().light = (int)args[0] | ((int)args[1] << 16);
                default -> throw new AssertionError("Unexpected vertex operation: " + method);
            }
            return proxy;
        }
    }

    private static void require(boolean result, String message) {
        checks++;
        if (!result) throw new AssertionError(message);
    }
}
