package dev.chatsorter.mixin;

import dev.chatsorter.ChatSorter;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;
import dev.chatsorter.ChatPosition;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.font.TextRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatHud.class)
public abstract class ChatHudMixin {
    // Minecraft's vanilla chat keeps only 100 messages and wrapped visual lines.
    // Raise both caps locally so other players do not need an additional mod.
    @ModifyConstant(method = "addMessage(Lnet/minecraft/client/gui/hud/ChatHudLine;)V", constant = @Constant(intValue = 100))
    private int chatsorter$expandMessageHistory(int original) {
        return ChatSorter.maxChatHistory();
    }

    @ModifyConstant(method = "addVisibleMessage(Lnet/minecraft/client/gui/hud/ChatHudLine;)V", constant = @Constant(intValue = 100))
    private int chatsorter$expandVisibleHistory(int original) {
        return ChatSorter.maxChatHistory();
    }

    @ModifyConstant(method = "addToMessageHistory(Ljava/lang/String;)V", constant = @Constant(intValue = 100))
    private int chatsorter$expandInputHistory(int original) {
        return ChatSorter.maxChatHistory();
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/font/TextRenderer;IIIZZ)V", at = @At("HEAD"))
    private void chatsorter$translate(DrawContext context, TextRenderer textRenderer, int currentTick, int mouseX, int mouseY, boolean focused, boolean chatHidden, CallbackInfo ci) {
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(ChatPosition.x, ChatPosition.y);
    }
    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/font/TextRenderer;IIIZZ)V", at = @At("RETURN"))
    private void chatsorter$restore(DrawContext context, TextRenderer textRenderer, int currentTick, int mouseX, int mouseY, boolean focused, boolean chatHidden, CallbackInfo ci) {
        context.getMatrices().popMatrix();
    }
    @Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V", at = @At("HEAD"), cancellable = true)
    private void chatsorter$sort(Text message, MessageSignatureData signature, MessageIndicator indicator, CallbackInfo ci) {
        if (!ChatSorter.capture(message, signature, indicator)) ci.cancel();
    }
}
