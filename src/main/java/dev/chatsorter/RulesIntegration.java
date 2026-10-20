package dev.chatsorter;

import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.Screen;

/** Optional entrypoint; ChatSorter does not depend on the rules addon. */
public final class RulesIntegration {
    public static boolean available() {
        return !FabricLoader.getInstance().getEntrypointContainers("chatsorter:rules", Consumer.class).isEmpty();
    }
    @SuppressWarnings("unchecked")
    public static void open(Screen parent) {
        for (Consumer<Screen> opener : FabricLoader.getInstance().getEntrypoints("chatsorter:rules", Consumer.class)) {
            opener.accept(parent);
            return;
        }
    }
    private RulesIntegration() {}
}
