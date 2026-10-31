package dev.chatsorter.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.List;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
@Mixin(ChatComponent.class)
public interface ChatHudAccess {
    @Accessor("trimmedMessages") List<GuiMessage.Line> chatsorter$getVisibleMessages();
    @Accessor("allMessages") List<GuiMessage> chatsorter$getMessages();
    @Accessor("chatScrollbarPos") int chatsorter$getScrolledLines();
}
