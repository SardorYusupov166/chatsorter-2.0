package dev.chatsorter.mixin;

import dev.chatsorter.ChatSorter;
import dev.chatsorter.ChatHistoryEntry;
import dev.chatsorter.KeywordAlerts;
import dev.chatsorter.ChatPanelManager;
import dev.chatsorter.ChatBackgrounds;
import dev.chatsorter.ChatRpgRenderer;
import dev.chatsorter.ChatPosition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;

@Mixin(ChatComponent.class)
public abstract class ChatHudMixin {
    @org.spongepowered.asm.mixin.Unique private boolean chatsorter$matrixPushed;
    @org.spongepowered.asm.mixin.Unique private boolean chatsorter$panelsRendered;
    // Minecraft's vanilla chat keeps only 100 messages and wrapped visual lines.
    // Raise both caps locally so other players do not need an additional mod.
    @ModifyConstant(method = "addMessageToQueue(Lnet/minecraft/client/multiplayer/chat/GuiMessage;)V", constant = @Constant(intValue = 100))
    private int chatsorter$expandMessageHistory(int original) {
        return ChatSorter.maxChatHistory();
    }

    @ModifyConstant(method = "addMessageToDisplayQueue(Lnet/minecraft/client/multiplayer/chat/GuiMessage;)V", constant = @Constant(intValue = 100))
    private int chatsorter$expandVisibleHistory(int original) {
        return ChatSorter.maxChatHistory();
    }

    @ModifyConstant(method = "addRecentChat(Ljava/lang/String;)V", constant = @Constant(intValue = 100))
    private int chatsorter$expandInputHistory(int original) {
        return ChatSorter.maxChatHistory();
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V", at = @At("HEAD"), cancellable = true)
    private void chatsorter$translate(GuiGraphicsExtractor context, Font textRenderer, int currentTick, int mouseX, int mouseY, ChatComponent.DisplayMode displayMode, boolean changeCursor, CallbackInfo ci) {
        boolean focused = displayMode != ChatComponent.DisplayMode.BACKGROUND;
        boolean chatHidden = false;
        ChatComponent hud = (ChatComponent)(Object)this;
        chatsorter$panelsRendered = false;
        if (dev.chatsorter.ChatWorldRenderer.shouldHideScreenChat(hud)) {
            chatsorter$matrixPushed = false;
            // HEAD cancellation skips the normal RETURN hook. Extra chats must
            // still get their own visibility check and screen fallback.
            chatsorter$renderPanels(context, textRenderer, currentTick, mouseX, mouseY, focused, chatHidden);
            ci.cancel();
            return;
        }
        context.pose().pushMatrix();
        chatsorter$matrixPushed = true;
        context.pose().translate(ChatPanelManager.offsetX(hud), ChatPanelManager.offsetY(hud));
        // A themed panel belongs to the open chat screen. The vanilla HUD keeps
        // rendering fading chat lines after ChatScreen closes, so drawing this
        // full-size background there leaves an opaque panel over the world.
        if (!chatHidden && focused) {
            Minecraft mc = Minecraft.getInstance();
            int width = ChatPanelManager.width(hud);
            if (width < 0) width = ChatPosition.width > 0
                ? ChatPosition.width : ChatComponent.getWidth(mc.options.chatWidth().get());
            int height = ChatPanelManager.height(hud);
            if (height < 0) height = ChatPosition.height > 0
                ? ChatPosition.height : ChatComponent.getHeight(focused
                    ? mc.options.chatHeightFocused().get()
                    : mc.options.chatHeightUnfocused().get());
            double scale = mc.options.chatScale().get();
            int scaledWidth = (int)Math.round(width * scale);
            int scaledHeight = (int)Math.round(height * scale);
            int top = mc.getWindow().getGuiScaledHeight() - 40 - scaledHeight;
            ChatBackgrounds.renderBackground(context, 0, top, scaledWidth, scaledHeight,
                ChatPanelManager.background(hud), ChatPanelManager.palette(hud), ChatPanelManager.darkening(hud));
            if (ChatPanelManager.usesRpgStyle(hud)) {
                ChatRpgRenderer.renderFrame(context, 0, top, scaledWidth, scaledHeight,
                    ChatPanelManager.palette(hud), currentTick, 0.0F);
            }
        }
    }
    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V", at = @At("RETURN"))
    private void chatsorter$restore(GuiGraphicsExtractor context, Font textRenderer, int currentTick, int mouseX, int mouseY, ChatComponent.DisplayMode displayMode, boolean changeCursor, CallbackInfo ci) {
        boolean focused = displayMode != ChatComponent.DisplayMode.BACKGROUND;
        boolean chatHidden = false;
        if (chatsorter$matrixPushed) {
            if (!chatHidden) chatsorter$renderAlertHighlights(context);
            context.pose().popMatrix();
            chatsorter$matrixPushed = false;
        }
        chatsorter$renderPanels(context, textRenderer, currentTick, mouseX, mouseY, focused, chatHidden);
    }

    @org.spongepowered.asm.mixin.Unique
    private void chatsorter$renderPanels(GuiGraphicsExtractor context, Font textRenderer, int currentTick,
                                         int mouseX, int mouseY, boolean focused, boolean chatHidden) {
        if (!chatsorter$panelsRendered && !chatHidden && ChatPanelManager.enabled()
                && (ChatComponent)(Object)this == Minecraft.getInstance().gui.hud.getChat()) {
            chatsorter$panelsRendered = true;
            ChatPanelManager.extractRenderState(context, textRenderer, currentTick, mouseX, mouseY, focused, false);
        }
    }
    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V", at = @At("HEAD"), cancellable = true)
    private void chatsorter$sort(Component message, MessageSignature signature, GuiMessageSource source, GuiMessageTag indicator, CallbackInfo ci) {
        if (!ChatSorter.capture(message, signature, indicator)) ci.cancel();
    }

    private void chatsorter$renderAlertHighlights(GuiGraphicsExtractor context) {
        ChatComponent hud = (ChatComponent)(Object)this;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        double scale = mc.options.chatScale().get();
        if (scale <= 0) return;
        ChatHudAccess access = (ChatHudAccess)hud;
        List<GuiMessage.Line> lines = access.chatsorter$getVisibleMessages();
        List<GuiMessage> messages = access.chatsorter$getMessages();
        int scroll = access.chatsorter$getScrolledLines();
        int visibleCount = hud.getLinesPerPage();
        int width = ChatPanelManager.width(hud);
        if (width < 0) width = ChatPosition.width > 0
            ? ChatPosition.width : ChatComponent.getWidth(mc.options.chatWidth().get());
        if (width <= 0) return;
        int right = (int)Math.ceil(width * scale);
        double lineHeight = 9.0 * (mc.options.chatLineSpacing().get() + 1.0) * scale;
        double bottom = mc.getWindow().getGuiScaledHeight() - 40;
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
        int panelWidth = ChatPanelManager.width((ChatComponent)(Object)this);
        if (panelWidth >= 0) cir.setReturnValue(panelWidth);
        else if (ChatPosition.width > 0) cir.setReturnValue(ChatPosition.width);
    }

    @Inject(method = "getLinesPerPage()I", at = @At("HEAD"), cancellable = true)
    private void chatsorter$panelVisibleLineCount(CallbackInfoReturnable<Integer> cir) {
        int panelCount = ChatPanelManager.visibleLineCount((ChatComponent)(Object)this);
        if (panelCount >= 0) cir.setReturnValue(panelCount);
    }

    @Inject(method = "getHeight(D)I", at = @At("RETURN"), cancellable = true)
    private static void chatsorter$customHeight(double vanillaHeight, CallbackInfoReturnable<Integer> cir) {
        if (ChatPosition.height > 0) cir.setReturnValue(ChatPosition.height);
    }
}
