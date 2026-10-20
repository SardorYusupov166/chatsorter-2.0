package dev.chatsorter.mixin;

import dev.chatsorter.ChatSorter;
import dev.chatsorter.ChatHistoryEntry;
import dev.chatsorter.ChatPosition;
import dev.chatsorter.KeywordAlerts;
import dev.chatsorter.ChatTheme;
import dev.chatsorter.RulesIntegration;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.Click;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import java.util.List;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin extends Screen {
    private TextFieldWidget chatsorter$search;
    private ChatHistoryEntry chatsorter$copyMenuEntry;
    private boolean chatsorter$copyMenuHasJump;
    private int chatsorter$copyMenuX, chatsorter$copyMenuY;
    private static final int COPY_MENU_WIDTH = 190, COPY_MENU_ROW = 18;
    @Inject(method = "init", at = @At("TAIL"))
    private void chatsorter$initSearch(CallbackInfo ci) {
        int w = Math.min(210, Math.max(100, this.width / 3));
        chatsorter$search = new TextFieldWidget(this.textRenderer, this.width - w - 5, 29, w, 17, Text.literal("Поиск во всех чатах"));
        chatsorter$search.setMaxLength(120);
        chatsorter$search.setPlaceholder(Text.literal("Поиск во всех чатах..."));
        chatsorter$search.setText(ChatSorter.searchQuery());
        chatsorter$search.setChangedListener(ChatSorter::setSearchQuery);
        this.addDrawableChild(chatsorter$search);
    }
    private boolean chatsorter$dragging = false;
    private boolean chatsorter$resizing = false;
    private boolean chatsorter$resizeLeft, chatsorter$resizeRight;
    private boolean chatsorter$resizeTop, chatsorter$resizeBottom;
    private double chatsorter$lastX, chatsorter$lastY;
    protected ChatScreenMixin(Text title) { super(title); }

    // Draw flat tabs above the chat instead of adding vanilla ButtonWidgets.
    @Inject(method = "render", at = @At("TAIL"))
    private void chatsorter$render(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        chatsorter$pollDrag();
        // Invisible hit areas: drag the message body; resize by grabbing its edges.
        ChatSorter.Tab[] tabs = ChatSorter.Tab.values();
        int x = chatsorter$tabX();
        int y = 7;
        int width = Math.max(54, Math.min(100, (this.width - 12) / tabs.length));
        int h = 17;
        for (int i = 0; i < tabs.length; i++) {
            int left = x + i * width;
            boolean active = ChatSorter.filterEnabled(tabs[i]);
            boolean hovered = mouseX >= left && mouseX < left + width - 2 && mouseY >= y && mouseY < y + h;
            int bg = active ? dev.chatsorter.ChatTheme.accent() : hovered ? 0xDB393E42 : 0xCB25282D;
            context.fill(left, y, left + width - 2, y + h, bg);
            if (active) context.fill(left, y + h - 2, left + width - 2, y + h, dev.chatsorter.ChatTheme.bright());
            String label = tabs[i].title;
            int textX = left + (width - 2 - this.textRenderer.getWidth(label)) / 2;
            context.drawTextWithShadow(this.textRenderer, label, textX, y + 4, active ? dev.chatsorter.ChatTheme.text() : 0xFFD3D5D6);
            int count = ChatSorter.unread[tabs[i].ordinal()];
            if (count > 0) {
                String number = count > 999 ? "999+" : Integer.toString(count);
                int bx = left + width - 4 - this.textRenderer.getWidth(number);
                context.fill(bx - 3, y - 7, left + width - 1, y + 3, 0xE0A53737);
                context.drawTextWithShadow(this.textRenderer, number, bx, y - 6, 0xFFFFFFFF);
            }
        }
        if (RulesIntegration.available()) {
            int left = 5, top = 7, buttonWidth = 82, buttonHeight = 17;
            boolean hovered = mouseX >= left && mouseX < left + buttonWidth && mouseY >= top && mouseY < top + buttonHeight;
            context.fill(left, top, left + buttonWidth, top + buttonHeight, hovered ? ChatTheme.accent() : 0xCB25282D);
            context.fill(left, top + buttonHeight - 2, left + buttonWidth, top + buttonHeight, ChatTheme.bright());
            context.drawCenteredTextWithShadow(this.textRenderer, "Правила", left + buttonWidth / 2, top + 4,
                hovered ? ChatTheme.text() : 0xFFD3D5D6);
        }
        chatsorter$renderCopyMenu(context, mouseX, mouseY);
    }

    // Vanilla hit testing redraws text into a ClickHandler without a DrawContext.
    // Its pointer must be moved back to the original chat origin.
    @org.spongepowered.asm.mixin.injection.ModifyArgs(method = "mouseClicked", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/font/DrawnTextConsumer$ClickHandler;<init>(Lnet/minecraft/client/font/TextRenderer;II)V"))
    private void chatsorter$offsetClick(org.spongepowered.asm.mixin.injection.invoke.arg.Args args) {
        args.set(1, (Integer)args.get(1) - ChatPosition.x);
        args.set(2, (Integer)args.get(2) - ChatPosition.y);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void chatsorter$click(Click click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        double mouseX = click.x();
        double mouseY = click.y();
        if (chatsorter$copyMenuEntry != null && click.button() == 0) {
            int row = (int)((mouseY - chatsorter$copyMenuY) / COPY_MENU_ROW);
            int menuRows = chatsorter$copyMenuRows();
            if (mouseX >= chatsorter$copyMenuX && mouseX < chatsorter$copyMenuX + COPY_MENU_WIDTH
                    && mouseY >= chatsorter$copyMenuY && mouseY < chatsorter$copyMenuY + COPY_MENU_ROW * menuRows
                    && row >= 0 && row < menuRows) {
                chatsorter$copyOption(row);
                cir.setReturnValue(true);
                return;
            }
            chatsorter$copyMenuEntry = null;
            chatsorter$copyMenuHasJump = false;
        }
        for (net.minecraft.client.gui.Element child : children()) {
            if (child.isMouseOver(mouseX, mouseY) && super.mouseClicked(click, doubled)) {
                cir.setReturnValue(true);
                return;
            }
        }
        if (click.button() == 0 && RulesIntegration.available()
                && click.x() >= 5 && click.x() < 87 && click.y() >= 7 && click.y() < 24) {
            RulesIntegration.open((Screen)(Object)this);
            cir.setReturnValue(true);
            return;
        }
        if (click.button() == 1 && chatsorter$openCopyMenu(mouseX, mouseY)) {
            cir.setReturnValue(true);
            return;
        }
        if (click.button() != 0) return;
        int x = chatsorter$tabX();
        int y = 7;
        int width = Math.max(54, Math.min(100, (this.width - 12) / ChatSorter.Tab.values().length));
        if (mouseY < y || mouseY >= y + 17) return;
        for (int i = 0; i < ChatSorter.Tab.values().length; i++) {
            int left = x + i * width;
            if (mouseX >= left && mouseX < left + width - 2) {
                ChatSorter.setSearchQuery("");
                if (chatsorter$search != null) chatsorter$search.setText("");
                ChatSorter.toggleFilter(ChatSorter.Tab.values()[i]);
                cir.setReturnValue(true);
                return;
            }
        }
    }

    @Inject(method = "mouseClicked", at = @At("RETURN"), cancellable = true)
    private void chatsorter$startDrag(Click click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) return;
        for (net.minecraft.client.gui.Element child : children()) {
            if (child.isMouseOver(click.x(), click.y())) return;
        }
        double mouseX = click.x();
        double mouseY = click.y();
        if (click.button() == 0) {
            double left = chatsorter$leftEdge();
            double right = chatsorter$rightEdge();
            double top = chatsorter$topEdge();
            double bottom = chatsorter$bottomEdge();
            double edge = 7;
            boolean withinX = mouseX >= left - edge && mouseX <= right + edge;
            boolean withinY = mouseY >= top - edge && mouseY <= bottom + edge;
            if (withinX && withinY && !(chatsorter$search != null && mouseX >= chatsorter$search.getX() && mouseX < chatsorter$search.getX() + chatsorter$search.getWidth() && mouseY >= chatsorter$search.getY() && mouseY < chatsorter$search.getY() + chatsorter$search.getHeight())) {
                chatsorter$resizeLeft = Math.abs(mouseX - left) <= edge;
                chatsorter$resizeRight = Math.abs(mouseX - right) <= edge;
                chatsorter$resizeTop = Math.abs(mouseY - top) <= edge;
                chatsorter$resizeBottom = Math.abs(mouseY - bottom) <= edge;
                chatsorter$resizing = chatsorter$resizeLeft || chatsorter$resizeRight
                    || chatsorter$resizeTop || chatsorter$resizeBottom;
                chatsorter$dragging = !chatsorter$resizing;
                chatsorter$lastX = mouseX;
                chatsorter$lastY = mouseY;
                cir.setReturnValue(true);
                return;
            }
        }
    }

    private int chatsorter$tabX() {
        int width = Math.max(54, Math.min(100, (this.width - 12) / ChatSorter.Tab.values().length));
        return Math.max(2, this.width - width * ChatSorter.Tab.values().length - 4);
    }

    private boolean chatsorter$openCopyMenu(double mouseX, double mouseY) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return false;
        ChatHud hud = mc.inGameHud.getChatHud();
        double scale = mc.options.getChatScale().getValue();
        if (scale <= 0) return false;
        double chatX = (mouseX - ChatPosition.x) / scale;
        double chatY = (this.height - 40 + ChatPosition.y - mouseY) / scale;
        double lineHeight = 9.0 * (mc.options.getChatLineSpacing().getValue() + 1.0);
        int index = (int) Math.floor(chatY / lineHeight);
        if (chatX < 0 || chatX > 400 || index < 0 || chatY < 0) return false;
        List<ChatHudLine.Visible> lines = ((ChatHudAccess) hud).chatsorter$getVisibleMessages();
        if (index >= lines.size()) return false;
        int bottom = index;
        while (bottom > 0 && !lines.get(bottom).endOfEntry()) bottom--;
        int messageIndex = 0;
        for (int row = 0; row <= bottom; row++) if (lines.get(row).endOfEntry()) messageIndex++;
        List<ChatHudLine> messages = ((ChatHudAccess) hud).chatsorter$getMessages();
        int sourceIndex = messageIndex - 1;
        if (sourceIndex < 0 || sourceIndex >= messages.size()) return false;
        ChatHistoryEntry entry = ChatSorter.entryForHudMessage(messages.get(sourceIndex).content());
        if (entry == null) return false;
        chatsorter$copyMenuEntry = entry;
        chatsorter$copyMenuHasJump = ChatSorter.isSearching();
        chatsorter$copyMenuX = Math.max(4, Math.min(this.width - COPY_MENU_WIDTH - 4, (int)mouseX));
        chatsorter$copyMenuY = Math.max(4, Math.min(this.height - COPY_MENU_ROW * chatsorter$copyMenuRows() - 4, (int)mouseY));
        return true;
    }
    private int chatsorter$copyMenuRows() { return chatsorter$copyMenuHasJump ? 7 : 6; }
    private void chatsorter$renderCopyMenu(DrawContext context, int mouseX, int mouseY) {
        ChatHistoryEntry entry = chatsorter$copyMenuEntry;
        if (entry == null) return;
        String[] copyLabels = {"Копировать текст", "Текст + исходное время", "Копировать с форматированием",
            entry.sender.isBlank() ? "Ник отправителя не распознан" : "Копировать ник отправителя",
            ChatSorter.copyLink(entry).isBlank() ? "Ссылка отсутствует" : "Копировать ссылку", "Техническая информация"};
        String[] labels = chatsorter$copyMenuHasJump
            ? java.util.Arrays.copyOf(copyLabels, 7) : copyLabels;
        if (chatsorter$copyMenuHasJump) labels[6] = "Показать в переписке";
        int x = chatsorter$copyMenuX, y = chatsorter$copyMenuY;
        int menuRows = chatsorter$copyMenuRows();
        context.fill(x - 1, y - 1, x + COPY_MENU_WIDTH + 1, y + COPY_MENU_ROW * menuRows + 1, 0xFF080A0D);
        context.fill(x, y, x + COPY_MENU_WIDTH, y + COPY_MENU_ROW * menuRows, 0xF222272D);
        for (int i = 0; i < labels.length; i++) {
            int rowY = y + i * COPY_MENU_ROW;
            boolean disabled = (i == 3 && entry.sender.isBlank()) || (i == 4 && ChatSorter.copyLink(entry).isBlank());
            boolean hovered = !disabled && mouseX >= x && mouseX < x + COPY_MENU_WIDTH && mouseY >= rowY && mouseY < rowY + COPY_MENU_ROW;
            if (hovered) context.fill(x, rowY, x + COPY_MENU_WIDTH, rowY + COPY_MENU_ROW, ChatTheme.accent());
            context.drawTextWithShadow(this.textRenderer, labels[i], x + 7, rowY + 5,
                disabled ? 0xFF747B82 : hovered ? ChatTheme.text() : 0xFFE2E5E8);
        }
    }
    private void chatsorter$copyOption(int option) {
        ChatHistoryEntry entry = chatsorter$copyMenuEntry;
        chatsorter$copyMenuEntry = null;
        boolean jump = chatsorter$copyMenuHasJump && option == 6;
        chatsorter$copyMenuHasJump = false;
        if (entry == null) return;
        if (jump) {
            if (chatsorter$search != null) chatsorter$search.setText("");
            ChatSorter.showInConversation(entry);
            chatsorter$scrollToEntry(entry);
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) mc.player.sendMessage(Text.literal("[ChatSorter] Открыта переписка вокруг сообщения"), true);
            return;
        }
        String content = switch (option) {
            case 0 -> entry.message.getString() + (entry.repeats > 1 ? " ×" + entry.repeats : "");
            case 1 -> ChatSorter.copyText(entry);
            case 2 -> ChatSorter.copyFormatted(entry);
            case 3 -> ChatSorter.copySender(entry);
            case 4 -> ChatSorter.copyLink(entry);
            case 5 -> ChatSorter.copyTechnical(entry);
            default -> "";
        };
        if (content.isBlank()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.keyboard.setClipboard(content);
        if (mc.player != null) mc.player.sendMessage(Text.literal("[ChatSorter] Скопировано: " + new String[]{"текст", "текст со временем", "форматированный текст", "ник", "ссылка", "технические данные"}[option]), true);
    }
    private void chatsorter$scrollToEntry(ChatHistoryEntry target) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ChatHud hud = mc.inGameHud.getChatHud();
        ChatHudAccess access = (ChatHudAccess) hud;
        List<ChatHudLine> messages = access.chatsorter$getMessages();
        int targetIndex = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (ChatSorter.entryForHudMessage(messages.get(i).content()) == target) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex < 0) return;
        List<ChatHudLine.Visible> lines = access.chatsorter$getVisibleMessages();
        int entryCount = 0;
        for (int row = 0; row < lines.size(); row++) {
            if (lines.get(row).endOfEntry() && ++entryCount == targetIndex + 1) {
                hud.scroll(Math.max(0, row - hud.getVisibleLineCount() / 2));
                return;
            }
        }
    }
    // ChatScreen does not declare mouseDragged in Minecraft 1.21.11.
    // Polling while the chat screen is open avoids a missing-target Mixin injection.
    private void chatsorter$pollDrag() {
        if (!chatsorter$dragging && !chatsorter$resizing) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            if (chatsorter$resizing) mc.options.write();
            chatsorter$dragging = false;
            chatsorter$resizing = false;
            ChatPosition.save();
            return;
        }
        double x = mc.mouse.getX() * this.width / mc.getWindow().getWidth();
        double y = mc.mouse.getY() * this.height / mc.getWindow().getHeight();
        if (chatsorter$resizing) {
            double scale = mc.options.getChatScale().getValue();
            if (scale > 0) {
                double dx = x - chatsorter$lastX;
                double dy = y - chatsorter$lastY;
                double currentWidth = 40 + mc.options.getChatWidth().getValue() * 280;
                double currentHeight = 20 + mc.options.getChatHeightFocused().getValue() * 160;
                double dw = chatsorter$resizeRight ? dx : chatsorter$resizeLeft ? -dx : 0;
                double dh = chatsorter$resizeTop ? -dy : chatsorter$resizeBottom ? dy : 0;
                double newWidth = Math.max(40, Math.min(320, currentWidth + dw / scale));
                double newHeight = Math.max(20, Math.min(180, currentHeight + dh / scale));
                double actualDw = (newWidth - currentWidth) * scale;
                double actualDh = (newHeight - currentHeight) * scale;
                mc.options.getChatWidth().setValue(Math.max(0, Math.min(1, (newWidth - 40) / 280)));
                mc.options.getChatHeightFocused().setValue(Math.max(0, Math.min(1, (newHeight - 20) / 160)));
                // The left and bottom edges stay under the pointer when dragged.
                if (chatsorter$resizeLeft) ChatPosition.move((int)Math.round(-actualDw), 0, this.width, this.height);
                if (chatsorter$resizeBottom) ChatPosition.move(0, (int)Math.round(actualDh), this.width, this.height);
            }
        } else {
            ChatPosition.move((int)Math.round(x - chatsorter$lastX),
                (int)Math.round(y - chatsorter$lastY), this.width, this.height);
        }
        chatsorter$lastX = x;
        chatsorter$lastY = y;
    }

    private int chatsorter$rightEdge() {
        MinecraftClient mc = MinecraftClient.getInstance();
        double scale = mc.options.getChatScale().getValue();
        return (int) Math.round(ChatPosition.x + (40 + mc.options.getChatWidth().getValue() * 280) * scale);
    }
    private int chatsorter$topEdge() {
        MinecraftClient mc = MinecraftClient.getInstance();
        double scale = mc.options.getChatScale().getValue();
        return (int) Math.round(this.height - 40 + ChatPosition.y
            - (20 + mc.options.getChatHeightFocused().getValue() * 160) * scale);
    }
    private int chatsorter$leftEdge() { return ChatPosition.x; }
    private int chatsorter$bottomEdge() { return this.height - 40 + ChatPosition.y; }

}
