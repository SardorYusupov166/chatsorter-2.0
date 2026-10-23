package dev.chatsorter;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Photo backgrounds, local imports, cached textures, and display preferences. */
public final class ChatBackgrounds {
    public record Template(String id, String title, String author, String pageUrl, String imageUrl, int palette) { }
    public record LocalImage(String id, String title, Path path) { }
    private record TextureInfo(Identifier identifier, int width, int height) { }

    private static final Path ROOT = FabricLoader.getInstance().getConfigDir().resolve("chatsorter").resolve("backgrounds");
    private static final Path STOCK = ROOT.resolve("stock");
    private static final Path CUSTOM = ROOT.resolve("custom");
    private static final Path SETTINGS = ROOT.resolve("settings.properties");
    private static final int MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024;
    private static final List<Template> TEMPLATES = List.of(
        new Template("forest_night", "Ночной лес", "Liana S", "https://unsplash.com/photos/a-dark-forest-filled-with-lots-of-tall-trees-shShc6q5DAc", "https://images.unsplash.com/photo-1639003309972-4e5492d6beff?fm=jpg&fit=crop&crop=entropy&w=%d&h=%d&q=78", 0),
        new Template("mountain_stars", "Звёздные горы", "Aastik Maurya", "https://unsplash.com/photos/a-night-sky-with-the-moon-and-stars-above-a-mountain-range-rX7EsMuyTn0", "https://images.unsplash.com/photo-1682508810443-0e44dd06a8ae?fm=jpg&fit=crop&crop=entropy&w=%d&h=%d&q=78", 2),
        new Template("ocean_navy", "Тёмный океан", "Engin Akyurt", "https://unsplash.com/es/fotos/agua-del-oceano-azul-oscuro-con-olas-suaves-dnZlqYs0-To", "https://images.unsplash.com/photo-1655898121598-d4240f3e1ec3?fm=jpg&fit=crop&crop=entropy&w=%d&h=%d&q=78", 6),
        new Template("blue_lake", "Голубой час", "Alex He", "https://unsplash.com/photos/a-view-of-a-body-of-water-with-mountains-in-the-background-eql0OGNkRY0", "https://images.unsplash.com/photo-1670662851409-51d09c0ab8a2?fm=jpg&fit=crop&crop=entropy&w=%d&h=%d&q=78", 2),
        new Template("neon_rain", "Неон под дождём", "Masahiro Miyagi", "https://unsplash.com/photos/a-wet-city-street-at-night-with-neon-signs-uZA3P4sA3tM", "https://images.unsplash.com/photo-1644919070369-c0d17244fd88?fm=jpg&fit=crop&crop=entropy&w=%d&h=%d&q=78", 3),
        new Template("city_fog", "Город в тумане", "Krzysztof Kotkowicz", "https://unsplash.com/es/fotos/vista-de-la-ciudad-durante-la-noche-Zv7cxnCdrt0", "https://images.unsplash.com/photo-1576344581490-fa7a671e004f?fm=jpg&fit=crop&crop=entropy&w=%d&h=%d&q=78", 5),
        new Template("violet_bloom", "Фиолетовые цветы", "Kelly Sikkema", "https://unsplash.com/photos/purple-flower-photography-UvW67hITA8A", "https://images.unsplash.com/photo-1748017078663-7dc02d75d079?fm=jpg&fit=crop&crop=entropy&w=%d&h=%d&q=78", 7),
        new Template("autumn_path", "Осенняя тропа", "Ingmar", "https://unsplash.com/photos/sunlight-streams-through-the-trees-onto-a-path-wIpZ1b8tIAQ", "https://images.unsplash.com/photo-1504788363733-507549153474?fm=jpg&fit=crop&crop=entropy&w=%d&h=%d&q=78", 5),
        new Template("soft_bokeh", "Мягкое боке", "Liana S", "https://unsplash.com/fr/photos/abstrait-colore-reflets-de-lumiere-sur-fond-sombre-anksXFKK9Lg", "https://images.unsplash.com/photo-1732270912142-1ae5498cb4d8?fm=jpg&fit=crop&crop=entropy&w=%d&h=%d&q=78", 3),
        new Template("snowy_night", "Снежная ночь", "Daniil Artemenko", "https://unsplash.com/photos/mountains-beneath-a-sparkling-starry-night-sky-Fc2bxy-nuA8", "https://images.unsplash.com/photo-1749465601547-f841808c60ff?fm=jpg&fit=crop&crop=entropy&w=%d&h=%d&q=78", 6)
    );

    private static final Map<String, TextureInfo> TEXTURES = new ConcurrentHashMap<>();
    private static final Map<String, String> LOAD_ERRORS = new ConcurrentHashMap<>();
    private static final java.util.Set<String> LOADING = ConcurrentHashMap.newKeySet();
    private static volatile boolean initialized;
    private static volatile String selected = "none";
    private static volatile int darkening = 52;
    private static volatile String importError = "";

    private ChatBackgrounds() { }

    public static synchronized void load() {
        if (initialized) return;
        initialized = true;
        try {
            Files.createDirectories(STOCK);
            Files.createDirectories(CUSTOM);
            if (Files.isRegularFile(SETTINGS)) {
                Properties p = new Properties();
                try (InputStream in = Files.newInputStream(SETTINGS)) { p.load(in); }
                selected = p.getProperty("selected", "none");
                darkening = clamp(Integer.parseInt(p.getProperty("darkening", "52")), 0, 85);
            }
        } catch (Exception e) {
            System.err.println("[ChatSorter] Could not load background settings: " + e.getMessage());
        }
        if (!"none".equals(selected) && template(selected) == null && customImage(selected) == null) selected = "none";
        if (!"none".equals(selected)) requestTexture(selected, false);
    }

    public static List<Template> templates() { return TEMPLATES; }
    public static String selectedId() { return selected; }
    public static int darkening() { return darkening; }
    public static String importError() { return importError; }
    public static String loadError(String id, boolean thumbnail) { return LOAD_ERRORS.get(textureKey(id, thumbnail)); }

    public static void setDarkening(int value) {
        darkening = clamp(value, 0, 85);
        save();
    }

    public static void select(String id) {
        load();
        if (!"none".equals(id) && template(id) == null && customImage(id) == null) return;
        selected = id;
        Template stock = template(id);
        if (stock != null) ChatTheme.setIndex(stock.palette());
        save();
        if (!"none".equals(id)) requestTexture(id, false);
    }

    public static void requestAllThumbnails() {
        load();
        for (Template template : TEMPLATES) requestTexture(template.id(), true);
        for (LocalImage image : customImages()) requestTexture(image.id(), true);
    }

    public static void renderBackground(DrawContext context, int x, int y, int width, int height) {
        load();
        if (width <= 0 || height <= 0 || "none".equals(selected)) return;
        renderImage(context, selected, false, x, y, width, height);
        if (darkening > 0) {
            int alpha = Math.round(darkening * 255.0f / 100.0f);
            context.fill(x, y, x + width, y + height, (alpha << 24));
        }
        int border = ChatTheme.accent();
        context.fill(x, y, x + width, y + 1, border);
        context.fill(x, y + height - 1, x + width, y + height, border);
        context.fill(x, y, x + 1, y + height, border);
        context.fill(x + width - 1, y, x + width, y + height, border);
    }

    public static void renderPreview(DrawContext context, String id, int x, int y, int width, int height) {
        if ("none".equals(id)) {
            context.fill(x, y, x + width, y + height, 0xFF20252B);
            context.fill(x + width / 2 - 15, y + height / 2 - 1, x + width / 2 + 15, y + height / 2 + 1, ChatTheme.bright());
            return;
        }
        renderImage(context, id, true, x, y, width, height);
    }

    private static void renderImage(DrawContext context, String id, boolean thumbnail, int x, int y, int width, int height) {
        TextureInfo info = TEXTURES.get(textureKey(id, thumbnail));
        if (info == null) {
            context.fill(x, y, x + width, y + height, 0xFF20252B);
            requestTexture(id, thumbnail);
            return;
        }
        context.drawTexture(RenderPipelines.GUI_TEXTURED, info.identifier(), x, y, 0.0f, 0.0f,
            width, height, info.width(), info.height(), 0xFFFFFFFF);
    }

    private static void requestTexture(String id, boolean thumbnail) {
        if (id == null || "none".equals(id)) return;
        String key = textureKey(id, thumbnail);
        if (TEXTURES.containsKey(key) || !LOADING.add(key)) return;
        Path file = fileFor(id, thumbnail);
        CompletableFuture.runAsync(() -> {
            try {
                if (!Files.isRegularFile(file)) {
                    if (template(id) == null) throw new IllegalArgumentException("Файл фона не найден");
                    download(template(id), thumbnail, file);
                }
                NativeImage image;
                try (InputStream in = Files.newInputStream(file)) { image = NativeImage.read(in); }
                if (image == null) throw new IllegalArgumentException("Не удалось прочитать изображение");
                int imageWidth = image.getWidth(), imageHeight = image.getHeight();
                MinecraftClient.getInstance().execute(() -> {
                    try {
                        Identifier textureId = Identifier.of("chatsorter", "background/" + safe(id) + (thumbnail ? "_thumb" : ""));
                        NativeImageBackedTexture texture = new NativeImageBackedTexture(
                            () -> "ChatSorter background " + id, image);
                        MinecraftClient.getInstance().getTextureManager().registerTexture(textureId, texture);
                        TEXTURES.put(key, new TextureInfo(textureId, imageWidth, imageHeight));
                        LOAD_ERRORS.remove(key);
                    } catch (Exception e) {
                        image.close();
                        LOAD_ERRORS.put(key, "Не удалось загрузить текстуру");
                    } finally {
                        LOADING.remove(key);
                    }
                });
            } catch (Exception e) {
                LOADING.remove(key);
                LOAD_ERRORS.put(key, e.getMessage() == null ? "Ошибка загрузки фона" : e.getMessage());
                System.err.println("[ChatSorter] Background " + id + ": " + e.getMessage());
            }
        });
    }

    private static void download(Template template, boolean thumbnail, Path destination) throws Exception {
        int imageWidth = thumbnail ? 384 : 1920;
        int imageHeight = thumbnail ? 216 : 1080;
        String url = template.imageUrl().formatted(imageWidth, imageHeight);
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "ChatSorter/2.x (Minecraft client background cache)");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("Сервер изображений ответил " + status);
            long length = connection.getContentLengthLong();
            if (length > MAX_DOWNLOAD_BYTES) throw new IllegalArgumentException("Фон превышает лимит 8 МБ");
            Files.createDirectories(destination.getParent());
            Path temporary = Files.createTempFile(destination.getParent(), "background-", ".part");
            try {
                try (InputStream in = connection.getInputStream(); var out = Files.newOutputStream(temporary)) {
                    long copied = 0;
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        copied += count;
                        if (copied > MAX_DOWNLOAD_BYTES) throw new IllegalArgumentException("Фон превышает лимит 8 МБ");
                        out.write(buffer, 0, count);
                    }
                }
                try {
                    Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static Path fileFor(String id, boolean thumbnail) {
        LocalImage local = customImage(id);
        if (local != null) return local.path();
        return STOCK.resolve(safe(id) + (thumbnail ? "_thumb" : "_full") + ".jpg");
    }

    private static String textureKey(String id, boolean thumbnail) { return id + (thumbnail ? ":thumb" : ":full"); }
    private static Template template(String id) {
        for (Template template : TEMPLATES) if (template.id().equals(id)) return template;
        return null;
    }

    public static List<LocalImage> customImages() {
        load();
        ArrayList<LocalImage> result = new ArrayList<>();
        try (var files = Files.list(CUSTOM)) {
            files.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
                .forEach(path -> {
                    String filename = path.getFileName().toString();
                    String id = filename.substring(0, filename.length() - 4);
                    result.add(new LocalImage(id, humanize(id), path));
                });
        } catch (Exception ignored) { }
        result.sort(java.util.Comparator.comparing(LocalImage::title, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private static LocalImage customImage(String id) {
        if (id == null) return null;
        for (LocalImage image : customImages()) if (image.id().equals(id)) return image;
        return null;
    }

    public static String displayName(String id) {
        if ("none".equals(id)) return "Без фона";
        Template template = template(id);
        if (template != null) return template.title();
        LocalImage local = customImage(id);
        return local == null ? id : local.title();
    }

    public static String credit(String id) {
        Template template = template(id);
        return template == null ? "" : "Unsplash · " + template.author();
    }

    public static String sourceUrl(String id) {
        Template template = template(id);
        return template == null ? "" : template.pageUrl();
    }

    public static void importImage() {
        MinecraftClient client = MinecraftClient.getInstance();
        Thread chooser = new Thread(() -> {
            try {
                FileDialog dialog = new FileDialog((Frame)null, "Выберите фон чата", FileDialog.LOAD);
                dialog.setFilenameFilter((dir, name) -> name.toLowerCase(java.util.Locale.ROOT)
                    .matches(".*\\.(png|jpe?g|bmp)$"));
                dialog.setVisible(true);
                String filename = dialog.getFile();
                if (filename == null) return;
                Path source = Path.of(dialog.getDirectory(), filename);
                if (Files.size(source) > 20L * 1024 * 1024) throw new IllegalArgumentException("Файл больше 20 МБ");
                BufferedImage image = readImage(source);
                if (image == null) throw new IllegalArgumentException("Файл не является изображением");
                int maxW = 1920, maxH = 1080;
                double factor = Math.min(1.0, Math.min(maxW / (double)image.getWidth(), maxH / (double)image.getHeight()));
                int width = Math.max(1, (int)Math.round(image.getWidth() * factor));
                int height = Math.max(1, (int)Math.round(image.getHeight() * factor));
                BufferedImage normalized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = normalized.createGraphics();
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(image, 0, 0, width, height, null);
                graphics.dispose();
                String baseName = source.getFileName().toString().replaceFirst("(?i)\\.[^.]+$", "");
                String slug = baseName.toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
                if (slug.isBlank()) slug = "image";
                slug = slug.substring(0, Math.min(32, slug.length()));
                String id = "custom_" + slug + "_"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
                Files.createDirectories(CUSTOM);
                if (!ImageIO.write(normalized, "png", CUSTOM.resolve(id + ".png").toFile()))
                    throw new IllegalStateException("Не удалось сохранить изображение");
                client.execute(() -> {
                    importError = "";
                    LOAD_ERRORS.remove(id + ":thumb");
                    select(id);
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? "Не удалось импортировать изображение" : e.getMessage();
                client.execute(() -> importError = message);
                System.err.println("[ChatSorter] Could not import chat background: " + message);
            }
        }, "ChatSorter background picker");
        chooser.setDaemon(true);
        chooser.start();
    }

    private static BufferedImage readImage(Path file) throws Exception {
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long)width * height > 30_000_000L)
                    throw new IllegalArgumentException("Слишком большое изображение (лимит 30 мегапикселей)");
                return reader.read(0);
            } finally { reader.dispose(); }
        }
    }

    private static String humanize(String id) {
        if (id.matches("custom_[0-9a-f]{12}")) return "Мой фон";
        String name = id.replaceFirst("^custom_", "").replaceFirst("_[a-f0-9]{6}$", "").replace('_', ' ');
        return name.isBlank() ? "Мой фон" : name;
    }

    private static String safe(String id) { return id.replaceAll("[^a-zA-Z0-9_/-]", "_"); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    private static void save() {
        try {
            Files.createDirectories(SETTINGS.getParent());
            Properties p = new Properties();
            p.setProperty("selected", selected);
            p.setProperty("darkening", Integer.toString(darkening));
            try (var out = Files.newOutputStream(SETTINGS)) { p.store(out, "ChatSorter chat background"); }
        } catch (Exception e) {
            System.err.println("[ChatSorter] Could not save background settings: " + e.getMessage());
        }
    }
}
