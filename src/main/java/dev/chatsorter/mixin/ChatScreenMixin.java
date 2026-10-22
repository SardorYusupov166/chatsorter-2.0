package dev.chatsorter.mixin;

import dev.chatsorter.ChatSorter;
import dev.chatsorter.ChatHistoryEntry;
import dev.chatsorter.ChatPosition;
import dev.chatsorter.KeywordAlerts;
import dev.chatsorter.ChatTheme;
import dev.chatsorter.ChatPanelManager;
import dev.chatsorter.ChatSelection;
import dev.chatsorter.RulesIntegration;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.Click;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.hud.InGameHud;
import java.util.List;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin extends Screen {
    private TextFieldWidget chatsorter$search;
    private ChatHud chatsorter$activeClickHud;
    private ChatHistoryEntry chatsorter$copyMenuEntry;
    private boolean chatsorter$copyMenuHasJump;
    private int chatsorter$copyMenuX, chatsorter$copyMenuY;
    private static final int COPY_MENU_WIDTH = 190, COPY_MENU_ROW = 18;
    @Inject(method = "init", at = @At("TAIL"))
    private void chatsorter$initSearch(CallbackInfo ci) {
        int w = Math.min(210, Math.max(100, this.width / 3));
        chatsorter$search = new TextFieldWidget(this.textRenderer, this.width - w - 5, 49, w, 17, Text.literal("Поиск во всех чатах"));
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
        ChatPanelManager.pollDividerDrag(this.width);
        // Invisible hit areas: drag the message body; resize by grabbing its edges.
        List<ChatSorter.FilterToggle> toggles = ChatSorter.filterToggles(this.width);
        List<ChatSorter.FilterToggle> standard = toggles.stream().filter(t -> {
            try { ChatSorter.Tab.valueOf(t.key()); return true; } catch (IllegalArgumentException ignored) { return false; }
        }).toList();
        List<ChatSorter.FilterToggle> custom = toggles.stream().filter(t -> t.key().startsWith("custom:")).toList();
        ChatSorter.FilterToggle favorite = toggles.stream().filter(t -> t.key().equals("favorites")).findFirst().orElse(null);
        int builtinCount = standard.size();
        boolean panelsHovered = mouseX >= 5 && mouseX < 99 && mouseY >= 47 && mouseY < 68;
        context.fill(5, 47, 99, 68, panelsHovered ? ChatTheme.accent() : 0xCB25282D);
        context.drawCenteredTextWithShadow(this.textRenderer, ChatPanelManager.enabled() ? "Панели чата: 2" : "Панели чата: 1",
            52, 53, 0xFFE2E5E8);
        if (!ChatPanelManager.enabled()) {
        int x = chatsorter$tabX(builtinCount);
        int y = 7;
        int width = chatsorter$tabWidth(builtinCount);
        int h = 17;
        for (int i = 0; i < builtinCount; i++) {
            int left = x + i * width;
            ChatSorter.FilterToggle toggle = standard.get(i);
            boolean active = toggle.enabled();
            boolean hovered = mouseX >= left && mouseX < left + width - 2 && mouseY >= y && mouseY < y + h;
            int bg = active ? dev.chatsorter.ChatTheme.accent() : hovered ? 0xDB393E42 : 0xCB25282D;
            context.fill(left, y, left + width - 2, y + h, bg);
            if (active) context.fill(left, y + h - 2, left + width - 2, y + h, dev.chatsorter.ChatTheme.bright());
            String label = this.textRenderer.trimToWidth(toggle.label(), Math.max(20, width - 8));
            int textX = left + (width - 2 - this.textRenderer.getWidth(label)) / 2;
            context.drawTextWithShadow(this.textRenderer, label, textX, y + 4, active ? dev.chatsorter.ChatTheme.text() : 0xFFD3D5D6);
            int count = 0;
            try { count = ChatSorter.unread[ChatSorter.Tab.valueOf(toggle.key()).ordinal()]; } catch (IllegalArgumentException ignored) { }
            if (count > 0) {
                String number = count > 999 ? "999+" : Integer.toString(count);
                int bx = left + width - 4 - this.textRenderer.getWidth(number);
                context.fill(bx - 3, y - 7, left + width - 1, y + 3, 0xE0A53737);
                context.drawTextWithShadow(this.textRenderer, number, bx, y - 6, 0xFFFFFFFF);
            }
        }
        int customCount = custom.size();
        if (favorite != null) {
            boolean favHover=mouseX>=5&&mouseX<85&&mouseY>=27&&mouseY<44;
            context.fill(5,27,85,44,favorite.enabled()?ChatTheme.accent():favHover?0xDB393E42:0xCB25282D);
            if(favorite.enabled())context.fill(5,42,85,44,ChatTheme.bright());
            context.drawCenteredTextWithShadow(this.textRenderer,"Избранное",45,31,favorite.enabled()?ChatTheme.text():0xFFD3D5D6);
        }
        if (customCount > 0) {
            int cy=27, cw=Math.max(42,Math.min(100,(this.width-160)/customCount));
            int cx=this.width-cw*customCount-4;
            for(int i=0;i<customCount;i++) {
                ChatSorter.FilterToggle toggle=custom.get(i); int left=cx+i*cw;
                boolean active=toggle.enabled(),hovered=mouseX>=left&&mouseX<left+cw-2&&mouseY>=cy&&mouseY<cy+h;
                context.fill(left,cy,left+cw-2,cy+h,active?ChatTheme.accent():hovered?0xDB393E42:0xCB25282D);
                if(active) context.fill(left,cy+h-2,left+cw-2,cy+h,ChatTheme.bright());
                String label=this.textRenderer.trimToWidth(toggle.label(),Math.max(18,cw-8));
                context.drawCenteredTextWithShadow(this.textRenderer,label,left+(cw-2)/2,cy+4,active?ChatTheme.text():0xFFD3D5D6);
            }
            String page=ChatSorter.customFilterPageLabel(this.width);
            if(!page.isEmpty()) {
                context.fill(90,27,107,44,mouseX>=90&&mouseX<107&&mouseY>=27&&mouseY<44?ChatTheme.accent():0xCB25282D);
                context.drawCenteredTextWithShadow(this.textRenderer,"‹",98,31,0xFFFFFFFF);
                context.fill(110,27,127,44,mouseX>=110&&mouseX<127&&mouseY>=27&&mouseY<44?ChatTheme.accent():0xCB25282D);
                context.drawCenteredTextWithShadow(this.textRenderer,"›",118,31,0xFFFFFFFF);
                context.drawTextWithShadow(this.textRenderer,page,132,31,0xFFD3D5D6);
            }
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
        chatsorter$renderSelection(context, mouseX, mouseY);
        chatsorter$renderHoveredRow(context, mouseX, mouseY);
        chatsorter$renderSelectedRows(context);
        chatsorter$renderCopyMenu(context, mouseX, mouseY);
    }

    // Vanilla hit testing redraws text into a ClickHandler without a DrawContext.
    // Its pointer must be moved back to the original chat origin.
    @org.spongepowered.asm.mixin.injection.ModifyArgs(method = "mouseClicked", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/font/DrawnTextConsumer$ClickHandler;<init>(Lnet/minecraft/client/font/TextRenderer;II)V"))
    private void chatsorter$offsetClick(org.spongepowered.asm.mixin.injection.invoke.arg.Args args) {
        int originX = chatsorter$activeClickHud != null && ChatPanelManager.isPanelHud(chatsorter$activeClickHud)
            ? ChatPanelManager.offsetX(chatsorter$activeClickHud) : ChatPosition.x;
        args.set(1, (Integer)args.get(1) - originX);
        args.set(2, (Integer)args.get(2) - ChatPosition.y);
    }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "mouseClicked", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/hud/InGameHud;getChatHud()Lnet/minecraft/client/gui/hud/ChatHud;"), require = 0)
    private ChatHud chatsorter$usePanelHud(InGameHud inGameHud) {
        return chatsorter$activeClickHud == null ? inGameHud.getChatHud() : chatsorter$activeClickHud;
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void chatsorter$click(Click click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        double mouseX = click.x();
        double mouseY = click.y();
        MinecraftClient mc = MinecraftClient.getInstance();
        ChatHud panelHud = ChatPanelManager.enabled() ? ChatPanelManager.hudAt(mouseX, mouseY, this.height) : null;
        chatsorter$activeClickHud = panelHud == null ? mc.inGameHud.getChatHud() : panelHud;
        if (click.button() == 0 && ChatPanelManager.click(mouseX, mouseY, this.width, this.height)) {
            cir.setReturnValue(true);
            return;
        }
        if (click.button() == 0 && !ChatSelection.isEmpty() && chatsorter$selectionToolbarClick(mouseX, mouseY)) {
            cir.setReturnValue(true);
            return;
        }
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
        if (ChatPanelManager.enabled()) return;
        List<ChatSorter.FilterToggle> toggles = ChatSorter.filterToggles(this.width);
        List<ChatSorter.FilterToggle> standard = toggles.stream().filter(t -> {
            try { ChatSorter.Tab.valueOf(t.key()); return true; } catch (IllegalArgumentException ignored) { return false; }
        }).toList();
        List<ChatSorter.FilterToggle> custom = toggles.stream().filter(t -> t.key().startsWith("custom:")).toList();
        int builtinCount = standard.size();
        if (click.button() == 0 && mouseY >= 27 && mouseY < 44 && mouseX >= 5 && mouseX < 85
                && toggles.stream().anyMatch(t -> t.key().equals("favorites"))) {
            ChatSorter.toggleFilterKey("favorites"); cir.setReturnValue(true); return;
        }
        if (click.button() == 0 && mouseY >= 27 && mouseY < 44 && !ChatSorter.customFilterPageLabel(this.width).isEmpty()) {
            if (mouseX >= 90 && mouseX < 107) { ChatSorter.moveCustomFilterPage(-1,this.width); cir.setReturnValue(true); return; }
            if (mouseX >= 110 && mouseX < 127) { ChatSorter.moveCustomFilterPage(1,this.width); cir.setReturnValue(true); return; }
        }
        if (click.button() == 0 && mouseY >= 27 && mouseY < 44 && !custom.isEmpty()) {
            int count=custom.size(),cw=Math.max(42,Math.min(100,(this.width-160)/count));
            int cx=this.width-cw*count-4;
            for(int i=0;i<count;i++) if(mouseX>=cx+i*cw&&mouseX<cx+i*cw+cw-2) {
                ChatSorter.setSearchQuery(""); if(chatsorter$search!=null) chatsorter$search.setText("");
                ChatSorter.toggleFilterKey(custom.get(i).key()); cir.setReturnValue(true); return;
            }
        }
        boolean controlDown = chatsorter$modifierDown(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL);
        boolean shiftDown = chatsorter$modifierDown(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT);
        if (controlDown || shiftDown) {
            ChatHistoryEntry entry = chatsorter$entryAt(mouseX, mouseY);
            if (entry != null) {
                ChatSelection.click(entry, controlDown, shiftDown, ChatSorter.visibleEntriesForSelection());
                cir.setReturnValue(true);
                return;
            }
        }
        int x = chatsorter$tabX(builtinCount);
        int y = 7;
        int width = chatsorter$tabWidth(builtinCount);
        if (mouseY < y || mouseY >= y + 17) return;
        for (int i = 0; i < builtinCount; i++) {
            int left = x + i * width;
            if (mouseX >= left && mouseX < left + width - 2) {
                ChatSorter.setSearchQuery("");
                if (chatsorter$search != null) chatsorter$search.setText("");
                ChatSorter.toggleFilterKey(standard.get(i).key());
                cir.setReturnValue(true);
                return;
            }
        }
    }

    private boolean chatsorter$modifierDown(int firstKey, int secondKey) {
        long window = MinecraftClient.getInstance().getWindow().getHandle();
        return GLFW.glfwGetKey(window, firstKey) == GLFW.GLFW_PRESS
            || GLFW.glfwGetKey(window, secondKey) == GLFW.GLFW_PRESS;
    }

    private ChatHistoryEntry chatsorter$entryAt(double mouseX, double mouseY) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return null;
        double scale = mc.options.getChatScale().getValue();
        if (scale <= 0) return null;
        ChatHud hud = ChatPanelManager.enabled() ? ChatPanelManager.hudAt(mouseX, mouseY, this.height) : mc.inGameHud.getChatHud();
        if (hud == null) return null;
        double chatX = ChatPanelManager.localX(hud, mouseX);
        double chatY = (this.height - 40 + ChatPosition.y - mouseY) / scale;
        double lineHeight = 9.0 * (mc.options.getChatLineSpacing().getValue() + 1.0);
        int index = (int)Math.floor(chatY / lineHeight);
        if (chatX < 0 || (ChatPanelManager.width(hud) >= 0 && chatX > ChatPanelManager.width(hud)) || index < 0 || chatY < 0) return null;
        List<ChatHudLine.Visible> lines = ((ChatHudAccess)hud).chatsorter$getVisibleMessages();
        if (index >= lines.size()) return null;
        int bottom = index;
        while (bottom > 0 && !lines.get(bottom).endOfEntry()) bottom--;
        int messageIndex = 0;
        for (int row = 0; row <= bottom; row++) if (lines.get(row).endOfEntry()) messageIndex++;
        List<ChatHudLine> messages = ((ChatHudAccess)hud).chatsorter$getMessages();
        int sourceIndex = messageIndex - 1;
        return sourceIndex < 0 || sourceIndex >= messages.size() ? null
            : ChatSorter.entryForHudMessage(messages.get(sourceIndex).content());
    }

    private void chatsorter$renderSelection(DrawContext context, int mouseX, int mouseY) {
        if (ChatSelection.isEmpty()) return;
        int x = 5, y = 69, gap = 3;
        int[] widths = {48, 57, 57, 38, 44, 44};
        String[] labels = {"Текст", "+время", "+ники", "TXT", "JSON", "Снять"};
        context.fill(x - 2, y - 2, x + 48 + 57 + 57 + 38 + 44 + 44 + gap * 5 + 2, y + 19, 0xE8191C20);
        int left = x;
        for (int i = 0; i < labels.length; i++) {
            boolean hovered = mouseX >= left && mouseX < left + widths[i] && mouseY >= y && mouseY < y + 17;
            context.fill(left, y, left + widths[i], y + 17, hovered ? ChatTheme.accent() : 0xCB25282D);
            context.drawCenteredTextWithShadow(this.textRenderer, labels[i], left + widths[i] / 2, y + 4, 0xFFE2E5E8);
            left += widths[i] + gap;
        }
        int repeatY = y + 20;
        String repeatLabel = "Повторы: " + (ChatSelection.expandsRepeats() ? "каждое сообщение" : "одна строка");
        boolean repeatHover = mouseX >= x && mouseX < x + 164 && mouseY >= repeatY && mouseY < repeatY + 17;
        context.fill(x, repeatY, x + 164, repeatY + 17, repeatHover ? ChatTheme.accent() : 0xCB25282D);
        context.drawCenteredTextWithShadow(this.textRenderer, repeatLabel, x + 82, repeatY + 4, 0xFFE2E5E8);
        context.drawTextWithShadow(this.textRenderer, "Выделено: " + ChatSelection.size(), x + 171, repeatY + 4, 0xFFFFFFFF);
    }

    private boolean chatsorter$selectionToolbarClick(double mouseX, double mouseY) {
        int y = 69, left = 5, gap = 3;
        int[] widths = {48, 57, 57, 38, 44, 44};
        if (mouseY >= y + 20 && mouseY < y + 37 && mouseX >= 5 && mouseX < 169) {
            ChatSelection.toggleRepeatExpansion();
            return true;
        }
        if (mouseY < y || mouseY >= y + 17) return false;
        for (int i = 0; i < widths.length; i++) {
            if (mouseX >= left && mouseX < left + widths[i]) {
                List<ChatHistoryEntry> visible = ChatSorter.visibleEntriesForSelection();
                String clipboard = switch (i) {
                    case 0 -> ChatSelection.plainText(visible, false, false);
                    case 1 -> ChatSelection.plainText(visible, true, false);
                    case 2 -> ChatSelection.plainText(visible, true, true);
                    default -> "";
                };
                if (i <= 2) {
                    if (!clipboard.isBlank()) MinecraftClient.getInstance().keyboard.setClipboard(clipboard);
                } else if (i == 3 || i == 4) {
                    boolean json = i == 4;
                    List<ChatHistoryEntry> selected = ChatSelection.selected(visible);
                    CompletableFuture.runAsync(() -> {
                        try {
                            Path path = ChatSelection.export(selected, json);
                            MinecraftClient.getInstance().execute(() -> {
                                MinecraftClient mc = MinecraftClient.getInstance();
                                if (mc.player != null) mc.player.sendMessage(Text.literal("[ChatSorter] Экспорт сохранён: " + path.getFileName()), true);
                            });
                        } catch (Exception exception) {
                            MinecraftClient.getInstance().execute(() -> {
                                MinecraftClient mc = MinecraftClient.getInstance();
                                if (mc.player != null) mc.player.sendMessage(Text.literal("[ChatSorter] Ошибка экспорта: " + exception.getMessage()), true);
                            });
                        }
                    });
                } else ChatSelection.clear();
                return true;
            }
            left += widths[i] + gap;
        }
        return false;
    }

    private void chatsorter$renderSelectedRows(DrawContext context) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || ChatSelection.isEmpty()) return;
        ChatHud hud = mc.inGameHud.getChatHud();
        List<ChatHudLine.Visible> lines = ((ChatHudAccess)hud).chatsorter$getVisibleMessages();
        List<ChatHudLine> messages = ((ChatHudAccess)hud).chatsorter$getMessages();
        double scale = mc.options.getChatScale().getValue();
        double lineHeight = 9.0 * (mc.options.getChatLineSpacing().getValue() + 1.0) * scale;
        int row = 0, messageIndex = 0;
        while (row < lines.size()) {
            int groupStart = row;
            while (row < lines.size() - 1 && !lines.get(row).endOfEntry()) row++;
            int groupEnd = row;
            if (row < lines.size() && lines.get(row).endOfEntry()) messageIndex++;
            int sourceIndex = messageIndex - 1;
            if (sourceIndex >= 0 && sourceIndex < messages.size()
                    && ChatSelection.contains(ChatSorter.entryForHudMessage(messages.get(sourceIndex).content()))) {
                for (int line = groupStart; line <= groupEnd; line++) {
                    int bottom = (int)Math.round(this.height - 40 + ChatPosition.y - line * lineHeight);
                    int top = (int)Math.round(bottom - lineHeight);
                    context.fill(ChatPosition.x, top, ChatPosition.x + Math.max(2, (int)Math.ceil(2 * scale)), bottom, 0xD65AA9FF);
                }
            }
            row = groupEnd + 1;
        }
    }

    private void chatsorter$renderHoveredRow(DrawContext context, int mouseX, int mouseY) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        ChatHistoryEntry hovered = chatsorter$entryAt(mouseX, mouseY);
        if (hovered == null) return;

        ChatHud hud = mc.inGameHud.getChatHud();
        List<ChatHudLine.Visible> lines = ((ChatHudAccess)hud).chatsorter$getVisibleMessages();
        List<ChatHudLine> messages = ((ChatHudAccess)hud).chatsorter$getMessages();
        double scale = mc.options.getChatScale().getValue();
        double lineHeight = 9.0 * (mc.options.getChatLineSpacing().getValue() + 1.0) * scale;
        int row = 0, messageIndex = 0;
        int background = (ChatTheme.accent() & 0x00FFFFFF) | 0x3A000000;
        int right = Math.max(ChatPosition.x + 1, chatsorter$rightEdge());

        while (row < lines.size()) {
            int groupStart = row;
            while (row < lines.size() - 1 && !lines.get(row).endOfEntry()) row++;
            int groupEnd = row;
            if (row < lines.size() && lines.get(row).endOfEntry()) messageIndex++;
            int sourceIndex = messageIndex - 1;
            if (sourceIndex >= 0 && sourceIndex < messages.size()) {
                ChatHistoryEntry entry = ChatSorter.entryForHudMessage(messages.get(sourceIndex).content());
                if (entry != null && entry.id.equals(hovered.id)) {
                    for (int line = groupStart; line <= groupEnd; line++) {
                        int bottom = (int)Math.round(this.height - 40 + ChatPosition.y - line * lineHeight);
                        int top = (int)Math.round(bottom - lineHeight);
                        context.fill(ChatPosition.x, top, right, bottom, background);
                        context.fill(ChatPosition.x, top, ChatPosition.x + Math.max(2, (int)Math.ceil(2 * scale)), bottom, ChatTheme.bright());
                    }
                    return;
                }
            }
            row = groupEnd + 1;
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

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void chatsorter$scrollPanels(double mouseX, double mouseY, double horizontalAmount, double verticalAmount,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (ChatPanelManager.enabled() && ChatPanelManager.scroll(mouseX, mouseY, verticalAmount, this.height))
            cir.setReturnValue(true);
    }

    private int chatsorter$tabWidth(int count) {
        return Math.max(42, Math.min(100, (this.width - 12) / Math.max(1, count)));
    }
    private int chatsorter$tabX(int count) {
        int width = chatsorter$tabWidth(count);
        return Math.max(2, this.width - width * count - 4);
    }

    private boolean chatsorter$openCopyMenu(double mouseX, double mouseY) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return false;
        ChatHud hud = ChatPanelManager.enabled() ? ChatPanelManager.hudAt(mouseX, mouseY, this.height) : mc.inGameHud.getChatHud();
        if (hud == null) return false;
        double scale = mc.options.getChatScale().getValue();
        if (scale <= 0) return false;
        double chatX = ChatPanelManager.localX(hud, mouseX);
        double chatY = (this.height - 40 + ChatPosition.y - mouseY) / scale;
        double lineHeight = 9.0 * (mc.options.getChatLineSpacing().getValue() + 1.0);
        int index = (int) Math.floor(chatY / lineHeight);
        if (chatX < 0 || (ChatPanelManager.width(hud) >= 0 && chatX > ChatPanelManager.width(hud)) || index < 0 || chatY < 0) return false;
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
        chatsorter$copyMenuHasJump = ChatSorter.isSearching() && !entry.bookmarkFallback;
        chatsorter$copyMenuX = Math.max(4, Math.min(this.width - COPY_MENU_WIDTH - 4, (int)mouseX));
        chatsorter$copyMenuY = Math.max(4, Math.min(this.height - COPY_MENU_ROW * chatsorter$copyMenuRows() - 4, (int)mouseY));
        return true;
    }
    private int chatsorter$copyMenuRows() { return chatsorter$copyMenuHasJump ? 8 : 7; }
    private void chatsorter$renderCopyMenu(DrawContext context, int mouseX, int mouseY) {
        ChatHistoryEntry entry = chatsorter$copyMenuEntry;
        if (entry == null) return;
        String[] copyLabels = {"Копировать текст", "Текст + исходное время", "Копировать с форматированием",
            entry.sender.isBlank() ? "Ник отправителя не распознан" : "Копировать ник отправителя",
            ChatSorter.copyLink(entry).isBlank() ? "Ссылка отсутствует" : "Копировать ссылку", "Техническая информация",
            ChatSorter.isBookmarked(entry) ? "Убрать из избранного" : "Добавить в избранное"};
        String[] labels = chatsorter$copyMenuHasJump
            ? java.util.Arrays.copyOf(copyLabels, 8) : copyLabels;
        if (chatsorter$copyMenuHasJump) labels[7] = "Показать в переписке";
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
        boolean jump = chatsorter$copyMenuHasJump && option == 7;
        chatsorter$copyMenuHasJump = false;
        if (entry == null) return;
        if (option == 6) {
            boolean added=ChatSorter.toggleBookmark(entry);
            MinecraftClient mc=MinecraftClient.getInstance();
            if(mc.player!=null)mc.player.sendMessage(Text.literal(added?"[ChatSorter] Добавлено в избранное":"[ChatSorter] Удалено из избранного"),true);
            return;
        }
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
        ChatHud panelHud = ChatPanelManager.enabled() ? ChatPanelManager.hudContaining(target) : null;
        ChatHud hud = panelHud == null ? mc.inGameHud.getChatHud() : panelHud;
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
            if (ChatPanelManager.enabled()) ChatPanelManager.save();
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
