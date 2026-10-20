package dev.chatsorter;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.List;

/** Full-screen notification settings: keywords, persistent played-alert history, and sound. */
public final class AlertSettingsScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget input;
    private TextFieldWidget soundSearch;
    private boolean soundPickerOpen;
    private boolean draggingVolume;
    private boolean draggingRestoreLimit;
    private int soundPickerScroll;
    private int soundCategory = 0;
    private int wordScroll, historyScroll, settingsScroll;
    private List<NotificationHistory.Entry> history = List.of();
    private NotificationHistory.Entry selected;
    private static final int DARK = 0xD625282D, PANEL = 0xB52A2E33;
    private static int green(){return ChatTheme.accent();}
    public AlertSettingsScreen(Screen parent) { super(Text.literal("ChatSorter — Уведомления")); this.parent = parent; }
    private int leftX() { return 12; }
    private int leftW() { return Math.max(120, width / 2 - 26); }
    private int rightX() { return width / 2 + 10; }
    private int rightW() { return Math.max(120, width - rightX() - 12); }
    private int listTop() { return 103; }
    private int listBottom() { return Math.max(130, height / 2 + 8); }
    private int historyTop() { return listBottom() + 16; }
    private int historyBottom() { return height - 38; }
    private int settingsTop() { return 101; }
    private int settingsBottom() { return historyBottom() - 5; }
    private int settingsContentHeight() { return selected == null ? 465 : 680; }
    private int maxSettingsScroll() { return Math.max(0, settingsContentHeight() - Math.max(1, settingsBottom() - settingsTop())); }
    private int wordRows() { return Math.max(1, (listBottom() - listTop()) / 22); }
    private int historyRows() { return Math.max(1, (historyBottom() - historyTop() - 19) / 24); }
    @Override protected void init() {
        int w = leftW();
        input = new TextFieldWidget(textRenderer, leftX() + 8, 59, Math.max(35, w - 115), 20, Text.literal("Новое слово"));
        input.setMaxLength(80);
        input.setPlaceholder(Text.literal("Слово или фраза..."));
        addDrawableChild(input);
        history = NotificationHistory.load();
        soundSearch = new TextFieldWidget(textRenderer, rightX()+22, 114, Math.max(60,rightW()-44), 20, Text.literal("Поиск звука"));
        soundSearch.setMaxLength(100);
        soundSearch.setPlaceholder(Text.literal("Поиск по русскому названию или ID..."));
        soundSearch.setVisible(false);
        addDrawableChild(soundSearch);
    }
    private void button(DrawContext c, String label, int x, int y, int w, int h, int mx, int my) {
        boolean hover = mx >= x && mx < x+w && my >= y && my < y+h;
        c.fill(x,y,x+w,y+h,hover ? green() : DARK);
        c.drawCenteredTextWithShadow(textRenderer,label,x+w/2,y+(h-8)/2,0xFFFFFFFF);
    }
    private void panel(DrawContext c, int x, int y, int w, int h) {
        c.fill(x,y,x+w,y+h,PANEL);
        c.fill(x,y,x+w,y+1,0xFF53605A);
    }
    private void scrollbar(DrawContext c,int x,int top,int bottom,int count,int rows,int scroll) {
        if(count<=rows || bottom<=top) return;
        int h=Math.max(12,(bottom-top)*rows/count);
        int y=top+(bottom-top-h)*scroll/Math.max(1,count-rows);
        c.fill(x-2,top,x,bottom,0xFF363C43);
        c.fill(x-2,y,x,y+h,0xFF7BA5E9);
    }
    private void setVolumeAt(double mouseX) {
        int sx=rightX()+12, sw=rightW()-24;
        if(sw<=0) return;
        float value=(float)((mouseX-sx)/sw);
        KeywordAlerts.setVolume(Math.max(0.0F,Math.min(1.0F,value)));
    }
    private double restoreLimitFraction() {
        int min=50, max=16_384;
        return (ChatExperienceSettings.restoreLimit-min)/(double)(max-min);
    }
    private void setRestoreLimitAt(double mouseX) {
        int sx=rightX()+12, sw=rightW()-24;
        if(sw<=0) return;
        double fraction=Math.max(0.0,Math.min(1.0,(mouseX-sx)/sw));
        ChatExperienceSettings.restoreLimit=ChatExperienceSettings.clamp((int)Math.round(50+fraction*(16_384-50)));
    }
    @Override public void render(DrawContext c, int mx, int my, float delta) {
        c.fill(0,0,width,height,0xEE14181D);
        c.fill(0,0,width,39,green());
        c.drawCenteredTextWithShadow(textRenderer,"Настройки ChatSorter",width/2,15,0xFFFFFFFF);
        int lx=leftX(), lw=leftW(), rx=rightX(), rw=rightW();
        panel(c,lx,47,lw,40);
        button(c,"+ Добавить",lx+lw-99,59,91,20,mx,my);
        panel(c,lx,92,lw,listBottom()-92);
        c.drawTextWithShadow(textRenderer,"Белый список",lx+8,96,ChatTheme.text());
        List<String> words=KeywordAlerts.words();
        wordScroll=Math.min(wordScroll,Math.max(0,words.size()-wordRows()));
        for(int i=0;i<wordRows() && i+wordScroll<words.size();i++) {
            int y=listTop()+i*22;
            c.fill(lx+6,y,lx+lw-7,y+20,0xAA1A2026);
            c.drawTextWithShadow(textRenderer,textRenderer.trimToWidth(words.get(i+wordScroll),Math.max(10,lw-67)),lx+12,y+6,0xFFE2E5E2);
            button(c,"×",lx+lw-36,y+1,23,18,mx,my);
        }
        scrollbar(c,lx+lw-3,listTop(),listBottom()-5,words.size(),wordRows(),wordScroll);
        panel(c,lx,historyTop(),lw,historyBottom()-historyTop());
        c.drawTextWithShadow(textRenderer,"История уведомлений ("+history.size()+")",lx+8,historyTop()+5,ChatTheme.text());
        int rows=historyRows();
        historyScroll=Math.min(historyScroll,Math.max(0,history.size()-rows));
        for(int i=0;i<rows && i+historyScroll<history.size();i++) {
            NotificationHistory.Entry e=history.get(i+historyScroll);
            int y=historyTop()+19+i*24;
            c.fill(lx+6,y,lx+lw-7,y+22,e==selected ? green() : 0xAA1A2026);
            String shortTime=e.time().length()>10 ? e.time().substring(11,Math.min(19,e.time().length())) : e.time();
            c.drawTextWithShadow(textRenderer,textRenderer.trimToWidth(shortTime+" · "+e.keyword(),lw-20),lx+11,y+2,0xFFFFFFFF);
            c.drawTextWithShadow(textRenderer,textRenderer.trimToWidth(e.message(),lw-20),lx+11,y+12,0xFFB6C2BD);
        }
        scrollbar(c,lx+lw-3,historyTop()+19,historyBottom()-4,history.size(),rows,historyScroll);
        panel(c,rx,47,rw,40);
        c.drawCenteredTextWithShadow(textRenderer,"Настройки уведомлений",rx+rw/2,63,ChatTheme.text());
        panel(c,rx,92,rw,historyBottom()-92);
        settingsScroll=Math.max(0,Math.min(settingsScroll,maxSettingsScroll()));
        int sx=rx+12, sw=rw-24, oy=settingsTop()-settingsScroll;
        c.enableScissor(rx+2,settingsTop(),rx+rw-2,settingsBottom());
        c.drawTextWithShadow(textRenderer,"Громкость: "+Math.round(KeywordAlerts.volume()*100)+"%",sx,oy,0xFFFFFFFF);
        c.fill(sx,oy+23,sx+sw,oy+37,0xFF30373B);
        c.fill(sx,oy+23,sx+Math.round(sw*KeywordAlerts.volume()),oy+37,ChatTheme.bright());
        c.drawTextWithShadow(textRenderer,"Звук из Minecraft",sx,oy+62,0xFFFFFFFF);
        button(c,textRenderer.trimToWidth(KeywordAlerts.soundId() + "  ▼",Math.max(30,sw-18)),sx,oy+85,sw,23,mx,my);
        button(c,"▶ Прослушать",sx,oy+123,Math.min(sw,150),23,mx,my);
        c.drawTextWithShadow(textRenderer,"Отдельная громкость, независимо от Minecraft",sx,oy+164,0xFFAAAAAA);
        c.drawTextWithShadow(textRenderer,"Свои сообщения игнорируются",sx,oy+180,0xFFAAAAAA);
        button(c,"Тема: " + ChatTheme.name(),sx,oy+205,Math.min(sw,190),23,mx,my);
        c.drawTextWithShadow(textRenderer,"Клавиша уведомлений: " + AlertHotkey.keyName(),sx,oy+245,0xFFFFFFFF);
        button(c,"Сменить клавишу",sx,oy+263,Math.min(sw,160),22,mx,my);
        c.drawTextWithShadow(textRenderer,"Настройки чата",sx,oy+299,ChatTheme.text());
        button(c,"Объединять повторы: " + (ChatExperienceSettings.mergeRepeats ? "Вкл." : "Выкл."),sx,oy+317,sw,22,mx,my);
        button(c,"Восстанавливать историю: " + (ChatExperienceSettings.restoreHistory ? "Вкл." : "Выкл."),sx,oy+344,sw,22,mx,my);
        c.drawTextWithShadow(textRenderer,"Лимит восстановления: " + ChatExperienceSettings.restoreLimit,sx,oy+371,0xFFFFFFFF);
        c.fill(sx,oy+390,sx+sw,oy+400,0xFF30373B);
        c.fill(sx,oy+390,sx+(int)Math.round(sw*restoreLimitFraction()),oy+400,ChatTheme.bright());
        int limitKnobX=sx+(int)Math.round(sw*restoreLimitFraction());
        c.fill(limitKnobX-2,oy+386,limitKnobX+3,oy+404,0xFFFFFFFF);
        button(c,"Очистить историю этого сервера",sx,oy+410,sw,22,mx,my);
        c.drawTextWithShadow(textRenderer,"Перетащите ползунок, чтобы изменить лимит.",sx,oy+437,0xFFAAAAAA);
        if(selected!=null) {
            c.drawTextWithShadow(textRenderer,"Выбранное уведомление",sx,oy+455,ChatTheme.text());
            c.drawTextWithShadow(textRenderer,textRenderer.trimToWidth("Время: "+selected.time(),sw),sx,oy+475,0xFFFFFFFF);
            c.drawTextWithShadow(textRenderer,textRenderer.trimToWidth("Слово: "+selected.keyword(),sw),sx,oy+491,0xFFFFFFFF);
            c.drawTextWithShadow(textRenderer,"Полное сообщение:",sx,oy+511,0xFFFFFFFF);
            String msg=selected.message();
            int maxChars=Math.max(8,sw/6);
            for(int i=0;i<Math.min(12,(msg.length()+maxChars-1)/maxChars);i++) {
                int start=i*maxChars;
                c.drawTextWithShadow(textRenderer,msg.substring(start,Math.min(msg.length(),start+maxChars)),sx,oy+528+i*12,0xFFE2E5E2);
            }
        }
        c.disableScissor();
        scrollbar(c,rx+rw-3,settingsTop(),settingsBottom(),settingsContentHeight(),Math.max(1,settingsBottom()-settingsTop()),settingsScroll);
        button(c,"Готово",width-112,height-29,92,21,mx,my);
        super.render(c,mx,my,delta);
        if (soundPickerOpen) renderSoundPicker(c,mx,my);
    }
    private static final String[] CATEGORIES = {"Все", "Блоки", "Существа", "Игрок", "Интерфейс", "Окружение", "Музыка", "Предметы", "Прочее"};
    private static final String[] PREFIXES = {"", "block.", "entity.", "entity.player.", "ui.", "ambient.", "music", "item.", ""};
    private static String category(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        if (path.startsWith("block.")) return "Блоки";
        if (path.startsWith("entity.player.")) return "Игрок";
        if (path.startsWith("entity.")) return "Существа";
        if (path.startsWith("ui.")) return "Интерфейс";
        if (path.startsWith("ambient.") || path.startsWith("weather.")) return "Окружение";
        if (path.startsWith("music") || path.startsWith("record.")) return "Музыка";
        if (path.startsWith("item.")) return "Предметы";
        return "Прочее";
    }
    private static final java.util.Map<String,String> WORDS = java.util.Map.ofEntries(
        java.util.Map.entry("cherry", "вишнёвая"), java.util.Map.entry("wood", "деревянная"),
        java.util.Map.entry("button", "кнопка"), java.util.Map.entry("door", "дверь"),
        java.util.Map.entry("fence", "забор"), java.util.Map.entry("gate", "калитка"),
        java.util.Map.entry("trapdoor", "люк"), java.util.Map.entry("chest", "сундук"),
        java.util.Map.entry("open", "открытие"), java.util.Map.entry("close", "закрытие"),
        java.util.Map.entry("click", "щелчок"), java.util.Map.entry("on", "включение"),
        java.util.Map.entry("off", "выключение"), java.util.Map.entry("break", "разрушение"),
        java.util.Map.entry("place", "установка"), java.util.Map.entry("step", "шаги"),
        java.util.Map.entry("hit", "удар"), java.util.Map.entry("fall", "падение"),
        java.util.Map.entry("hurt", "получение урона"), java.util.Map.entry("death", "смерть"),
        java.util.Map.entry("levelup", "повышение уровня"), java.util.Map.entry("experience", "опыт"),
        java.util.Map.entry("orb", "сфера"), java.util.Map.entry("pickup", "подбор"),
        java.util.Map.entry("totem", "тотем"), java.util.Map.entry("use", "использование"),
        java.util.Map.entry("note", "нота"), java.util.Map.entry("bell", "колокол"),
        java.util.Map.entry("player", "игрок"), java.util.Map.entry("villager", "житель"),
        java.util.Map.entry("zombie", "зомби"), java.util.Map.entry("creeper", "крипер"),
        java.util.Map.entry("portal", "портал"), java.util.Map.entry("rain", "дождь"),
        java.util.Map.entry("thunder", "гром"), java.util.Map.entry("music", "музыка"),
        java.util.Map.entry("anvil", "наковальня"), java.util.Map.entry("amethyst", "аметист"),
        java.util.Map.entry("glass", "стекло"), java.util.Map.entry("stone", "камень"),
        java.util.Map.entry("water", "вода"), java.util.Map.entry("lava", "лава")
    );
    private static String soundName(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        String[] segments = path.split("\\.");
        StringBuilder out = new StringBuilder();
        for (int i=1; i<segments.length; i++) {
            for (String token : segments[i].split("_")) {
                if (!out.isEmpty()) out.append(' ');
                out.append(WORDS.getOrDefault(token, token));
            }
        }
        if (out.isEmpty()) out.append(path.replace('.', ' ').replace('_', ' '));
        String result = out.toString();
        return Character.toUpperCase(result.charAt(0)) + result.substring(1);
    }
    private List<String> filteredSounds() {
        String filter = soundSearch.getText().strip().toLowerCase(java.util.Locale.ROOT);
        return KeywordAlerts.sounds().stream().filter(id ->
            (soundCategory == 0 || category(id).equals(CATEGORIES[soundCategory])) &&
            (filter.isEmpty() || id.toLowerCase(java.util.Locale.ROOT).contains(filter) ||
             soundName(id).toLowerCase(java.util.Locale.ROOT).contains(filter))).toList();
    }
    private int pickerX() { return Math.max(15,width/6); }
    private int pickerY() { return Math.max(30,height/9); }
    private int pickerW() { return width-pickerX()*2; }
    private int pickerH() { return height-pickerY()*2; }
    private int pickerRows() { return Math.max(1,(pickerH()-112)/34); }
    private void renderSoundPicker(DrawContext c,int mx,int my) {
        int x=pickerX(),y=pickerY(),w=pickerW(),h=pickerH();
        c.fill(0,0,width,height,0xCC080A0D);
        c.fill(x,y,x+w,y+h,0xFF252A30);
        c.fill(x,y,x+w,y+2,ChatTheme.accent());
        c.drawCenteredTextWithShadow(textRenderer,"Все звуки Minecraft ("+KeywordAlerts.sounds().size()+")",x+w/2,y+11,0xFFFFFFFF);
        soundSearch.setX(x+12);soundSearch.setY(y+31);soundSearch.setWidth(w-24);
        int cx=x+12;
        for (int i=0;i<CATEGORIES.length;i++) {
            int cw=textRenderer.getWidth(CATEGORIES[i])+16;
            if(cx+cw>x+w-12) break;
            c.fill(cx,y+57,cx+cw,y+76,i==soundCategory?ChatTheme.accent():0xFF363D45);
            c.drawCenteredTextWithShadow(textRenderer,CATEGORIES[i],cx+cw/2,y+62,0xFFFFFFFF);
            cx+=cw+4;
        }
        List<String> found=filteredSounds();
        soundPickerScroll=Math.min(soundPickerScroll,Math.max(0,found.size()-pickerRows()));
        c.enableScissor(x+8,y+83,x+w-8,y+h-34);
        for(int i=0;i<pickerRows() && i+soundPickerScroll<found.size();i++) {
            int ry=y+84+i*34;
            String id=found.get(i+soundPickerScroll);
            c.fill(x+12,ry,x+w-12,ry+33,id.equals(KeywordAlerts.soundId())?ChatTheme.accent():((mx>=x+12&&mx<x+w-12&&my>=ry&&my<ry+19)?0xFF404A55:0xFF30363E));
            c.drawTextWithShadow(textRenderer,textRenderer.trimToWidth(soundName(id),w-43),x+19,ry+4,0xFFFFFFFF);
            c.drawTextWithShadow(textRenderer,textRenderer.trimToWidth(id,w-43),x+19,ry+18,0xFFB0BAC4);
        }
        c.disableScissor();
        c.drawTextWithShadow(textRenderer,"Найдено: "+found.size()+"  •  Нажмите для выбора и прослушивания",x+12,y+h-24,0xFFB8C0C8);
        button(c,"Закрыть",x+w-100,y+h-29,88,22,mx,my);
        soundSearch.render(c,mx,my,0);
    }
    @Override public boolean mouseClicked(Click click, boolean doubled) {
        double x=click.x(), y=click.y();
        if(soundPickerOpen) {
            if(click.button()==0) {
                int px=pickerX(),py=pickerY(),pw=pickerW(),ph=pickerH();
                if(x>=px+pw-100 && x<px+pw-12 && y>=py+ph-29 && y<py+ph-7) { soundPickerOpen=false; soundSearch.setVisible(false); return true; }
                if(y>=py+57 && y<py+76) {
                    int cx=px+12;
                    for(int i=0;i<CATEGORIES.length;i++) {
                        int cw=textRenderer.getWidth(CATEGORIES[i])+16;
                        if(cx+cw>px+pw-12) break;
                        if(x>=cx && x<cx+cw) {soundCategory=i;soundPickerScroll=0;return true;}
                        cx+=cw+4;
                    }
                }
                if(x>=px+12 && x<px+pw-12 && y>=py+84 && y<py+ph-34) {
                    int idx=((int)y-py-84)/34+soundPickerScroll;
                    List<String> found=filteredSounds();
                    if(idx>=0 && idx<found.size()) { KeywordAlerts.setSoundId(found.get(idx)); KeywordAlerts.preview(); soundPickerOpen=false; soundSearch.setVisible(false); }
                    return true;
                }
            }
            return super.mouseClicked(click,doubled);
        }
        if(click.button()==0) {
            int lx=leftX(), lw=leftW(), rx=rightX(), rw=rightW(), sx=rx+12, sw=rw-24, oy=settingsTop()-settingsScroll;
            if(x>=width-112 && x<width-20 && y>=height-29 && y<height-8) { close(); return true; }
            if(x>=lx+lw-99 && x<lx+lw-8 && y>=59 && y<79) {
                if(KeywordAlerts.add(input.getText())) { input.setText(""); wordScroll=Math.max(0,KeywordAlerts.words().size()-wordRows()); }
                return true;
            }
            if(x>=lx+lw-36 && x<lx+lw-13 && y>=listTop() && y<listBottom()) {
                int index=((int)y-listTop())/22+wordScroll;
                if(index>=0 && index<KeywordAlerts.words().size()) KeywordAlerts.remove(KeywordAlerts.words().get(index));
                return true;
            }
            if(x>=lx && x<lx+lw && y>=historyTop()+19 && y<historyBottom()) {
                int index=((int)y-historyTop()-19)/24+historyScroll;
                if(index>=0 && index<history.size()) selected=history.get(index);
                return true;
            }
            if(y>=settingsTop() && y<settingsBottom() && x>=sx && x<sx+Math.min(sw,190) && y>=oy+205 && y<oy+228) { ChatTheme.next(); return true; }
            if(y>=settingsTop() && y<settingsBottom() && x>=sx && x<sx+Math.min(sw,160) && y>=oy+263 && y<oy+285) { client.setScreen(new HotkeySetupScreen(this)); return true; }
            if(y>=settingsTop() && y<settingsBottom() && x>=sx && x<sx+sw && y>=oy+317 && y<oy+339) {
                ChatExperienceSettings.mergeRepeats=!ChatExperienceSettings.mergeRepeats; ChatExperienceSettings.save(); return true;
            }
            if(y>=settingsTop() && y<settingsBottom() && x>=sx && x<sx+sw && y>=oy+344 && y<oy+366) {
                ChatExperienceSettings.restoreHistory=!ChatExperienceSettings.restoreHistory; ChatExperienceSettings.save(); return true;
            }
            if(y>=settingsTop() && y<settingsBottom() && x>=sx && x<=sx+sw && y>=oy+386 && y<oy+405) {
                draggingRestoreLimit=true;
                setRestoreLimitAt(x);
                return true;
            }
            if(y>=settingsTop() && y<settingsBottom() && x>=sx && x<sx+sw && y>=oy+410 && y<oy+432) {
                ChatSorter.clearCurrentServerHistory(); return true;
            }
            if(y>=settingsTop() && y<settingsBottom() && x>=sx && x<=sx+sw && y>=oy+23 && y<oy+38) {
                draggingVolume=true;
                setVolumeAt(x);
                return true;
            }
            if(y>=settingsTop() && y<settingsBottom() && x>=sx && x<sx+sw && y>=oy+85 && y<oy+108) {
                soundPickerOpen=true; soundPickerScroll=0; soundCategory=0; soundSearch.setText(""); soundSearch.setVisible(true);
                return true;
            }
            if(y>=settingsTop() && y<settingsBottom() && x>=sx && x<sx+Math.min(sw,150) && y>=oy+123 && y<oy+146) { KeywordAlerts.preview(); return true; }
        }
        return super.mouseClicked(click,doubled);
    }
    @Override public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if(draggingRestoreLimit && click.button()==0) {
            setRestoreLimitAt(click.x());
            return true;
        }
        if(draggingVolume && click.button()==0) {
            setVolumeAt(click.x());
            return true;
        }
        return super.mouseDragged(click,offsetX,offsetY);
    }
    @Override public boolean mouseReleased(Click click) {
        if(draggingRestoreLimit && click.button()==0) {
            setRestoreLimitAt(click.x());
            draggingRestoreLimit=false;
            ChatExperienceSettings.save();
            return true;
        }
        if(draggingVolume && click.button()==0) {
            setVolumeAt(click.x());
            draggingVolume=false;
            return true;
        }
        return super.mouseReleased(click);
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int step=(int)Math.signum(verticalAmount);
        if(soundPickerOpen) {
            soundPickerScroll=Math.max(0,Math.min(Math.max(0,filteredSounds().size()-pickerRows()),soundPickerScroll-step*3));
            return true;
        }
        if(mouseX<width/2) {
            if(mouseY>=historyTop()) historyScroll=Math.max(0,Math.min(Math.max(0,history.size()-historyRows()),historyScroll-step));
            else if(mouseY>=listTop()) wordScroll=Math.max(0,Math.min(Math.max(0,KeywordAlerts.words().size()-wordRows()),wordScroll-step));
            return true;
        }
        if(mouseY<settingsTop() || mouseY>=settingsBottom()) return false;
        settingsScroll=Math.max(0,Math.min(maxSettingsScroll(),settingsScroll-step*16));
        return true;
    }
    @Override public void close() {
        if(draggingRestoreLimit) ChatExperienceSettings.save();
        draggingRestoreLimit=false;
        draggingVolume=false;
        client.setScreen(parent);
    }
}
