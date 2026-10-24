package dev.chatsorter.mixin;

import dev.chatsorter.ChatSorter;
import dev.chatsorter.ChatHistoryEntry;
import dev.chatsorter.KeywordAlerts;
import dev.chatsorter.ChatPanelManager;
import dev.chatsorter.ChatBackgrounds;
import dev.chatsorter.ChatRpgRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

@Mixin(ChatHud.class)
public abstract class ChatHudMixin {
    @org.spongepowered.asm.mixin.Unique private boolean chatsorter$matrixPushed;
    @org.spongepowered.asm.mixin.Unique private boolean chatsorter$panelsRendered;
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

    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/font/TextRenderer;IIIZZ)V", at = @At("HEAD"), cancellable = true)
    private void chatsorter$translate(DrawContext context, TextRenderer textRenderer, int currentTick, int mouseX, int mouseY, boolean focused, boolean chatHidden, CallbackInfo ci) {
        ChatHud hud = (ChatHud)(Object)this;
        chatsorter$panelsRendered = false;
        if (dev.chatsorter.ChatWorldRenderer.shouldHideScreenChat(hud)) {
            chatsorter$matrixPushed = false;
            // HEAD cancellation skips the normal RETURN hook. Extra chats must
            // still get their own visibility check and screen fallback.
            chatsorter$renderPanels(context, textRenderer, currentTick, mouseX, mouseY, focused, chatHidden);
            ci.cancel();
            return;
        }
        context.getMatrices().pushMatrix();
        chatsorter$matrixPushed = true;
        context.getMatrices().translate(ChatPanelManager.offsetX(hud), ChatPanelManager.offsetY(hud));
        // A themed panel belongs to the open chat screen. The vanilla HUD keeps
        // rendering fading chat lines after ChatScreen closes, so drawing this
        // full-size background there leaves an opaque panel over the world.
        if (!chatHidden && focused) {
            MinecraftClient mc = MinecraftClient.getInstance();
            int width = ChatPanelManager.width(hud);
            if (width < 0) width = ChatPosition.width > 0
                ? ChatPosition.width : ChatHud.getWidth(mc.options.getChatWidth().getValue());
            int height = ChatPanelManager.height(hud);
            if (height < 0) height = ChatPosition.height > 0
                ? ChatPosition.height : ChatHud.getHeight(focused
                    ? mc.options.getChatHeightFocused().getValue()
                    : mc.options.getChatHeightUnfocused().getValue());
            double scale = mc.options.getChatScale().getValue();
            int scaledWidth = (int)Math.round(width * scale);
            int scaledHeight = (int)Math.round(height * scale);
            int top = mc.getWindow().getScaledHeight() - 40 - scaledHeight;
            ChatBackgrounds.renderBackground(context, 0, top, scaledWidth, scaledHeight,
                ChatPanelManager.background(hud), ChatPanelManager.palette(hud), ChatPanelManager.darkening(hud));
            if (ChatPanelManager.usesRpgStyle(hud)) {
                ChatRpgRenderer.renderFrame(context, 0, top, scaledWidth, scaledHeight,
                    ChatPanelManager.palette(hud), currentTick, 0.0F);
            }
        }
    }
    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/font/TextRenderer;IIIZZ)V", at = @At("RETURN"))
    private void chatsorter$restore(DrawContext context, TextRenderer textRenderer, int currentTick, int mouseX, int mouseY, boolean focused, boolean chatHidden, CallbackInfo ci) {
        if (chatsorter$matrixPushed) {
            if (!chatHidden) chatsorter$renderAlertHighlights(context);
            context.getMatrices().popMatrix();
            chatsorter$matrixPushed = false;
        }
        chatsorter$renderPanels(context, textRenderer, currentTick, mouseX, mouseY, focused, chatHidden);
    }

    @org.spongepowered.asm.mixin.Unique
    private void chatsorter$renderPanels(DrawContext context, TextRenderer textRenderer, int currentTick,
                                         int mouseX, int mouseY, boolean focused, boolean chatHidden) {
        if (!chatsorter$panelsRendered && !chatHidden && ChatPanelManager.enabled()
                && (ChatHud)(Object)this == MinecraftClient.getInstance().inGameHud.getChatHud()) {
            chatsorter$panelsRendered = true;
            ChatPanelManager.render(context, textRenderer, currentTick, mouseX, mouseY, focused, false);
        }
    }
    @Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V", at = @At("HEAD"), cancellable = true)
    private void chatsorter$sort(Text message, MessageSignatureData signature, MessageIndicator indicator, CallbackInfo ci) {
        if (!ChatSorter.capture(message, signature, indicator)) ci.cancel();
    }

    private void chatsorter$renderAlertHighlights(DrawContext context) {
        ChatHud hud = (ChatHud)(Object)this;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        double scale = mc.options.getChatScale().getValue();
        if (scale <= 0) return;
        ChatHudAccess access = (ChatHudAccess)hud;
        List<ChatHudLine.Visible> lines = access.chatsorter$getVisibleMessages();
        List<ChatHudLine> messages = access.chatsorter$getMessages();
        int scroll = access.chatsorter$getScrolledLines();
        int visibleCount = hud.getVisibleLineCount();
        int width = ChatPanelManager.width(hud);
        if (width < 0) width = ChatPosition.width > 0
            ? ChatPosition.width : ChatHud.getWidth(mc.options.getChatWidth().getValue());
        if (width <= 0) return;
        int right = (int)Math.ceil(width * scale);
        double lineHeight = 9.0 * (mc.options.getChatLineSpacing().getValue() + 1.0) * scale;
        double bottom = mc.getWindow().getScaledHeight() - 40;
        int row = 0, messageIndex = 0;
        while (row < lines.size()) {
            int groupStart = row;
            while (row < lines.size() - 1 && !lines.get(row).endOfEntry()) row++;
            int groupEnd = row;
            if (row < lines.size() && lines.get(row).endOfEntry()) messageIndex++;
            int sourceIndex = messageIndex - 1;
            if (sourceIndex >= 0 && sourceIndex < messages.size()) {
                ChatHistoryEntry entry = ChatSorter.entryForHudMessage(messages.get(sourceIndex).content());
                if (entry != null && entry.alertTriggered) {
                    for (int line = groupStart; line <= groupEnd; line++) {
                        int screenLine = line - scroll;
                        if (screenLine < 0 || screenLine >= visibleCount) continue;
                        int lineBottom = (int)Math.round(bottom - screenLine * lineHeight);
                        int lineTop = (int)Math.round(lineBottom - lineHeight);
                        context.fill(0, lineTop, right, lineBottom, KeywordAlerts.highlightColor());
                    }
                }
            }
            row = groupEnd + 1;
        }
    }

    @Inject(method = "getWidth()I", at = @At("HEAD"), cancellable = true)
    private void chatsorter$customWidth(CallbackInfoReturnable<Integer> cir) {
        int panelWidth = ChatPanelManager.width((ChatHud)(Object)this);
        if (panelWidth >= 0) cir.setReturnValue(panelWidth);
        else if (ChatPosition.width > 0) cir.setReturnValue(ChatPosition.width);
    }

    @Inject(method = "getVisibleLineCount()I", at = @At("HEAD"), cancellable = true)
    private void chatsorter$panelVisibleLineCount(CallbackInfoReturnable<Integer> cir) {
        int panelCount = ChatPanelManager.visibleLineCount((ChatHud)(Object)this);
        if (panelCount >= 0) cir.setReturnValue(panelCount);
    }

    @Inject(method = "getHeight(D)I", at = @At("RETURN"), cancellable = true)
    private static void chatsorter$customHeight(double vanillaHeight, CallbackInfoReturnable<Integer> cir) {
        if (ChatPosition.height > 0) cir.setReturnValue(ChatPosition.height);
    }
}
