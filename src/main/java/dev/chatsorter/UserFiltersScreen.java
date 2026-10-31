package dev.chatsorter;

import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Small editor for user categories and safe Regex rules. */
public final class UserFiltersScreen extends Screen {
    private final Screen parent;
    private EditBox category, regex, sample, priority;
    private ChatUserFilters.MatchField field = ChatUserFilters.MatchField.TEXT;
    private ChatUserFilters.Mode mode = ChatUserFilters.Mode.COPY;
    private String status = "Regex поддерживает безопасный синтаксис RE2/J.";
    private int scroll;
    private static final int ROW=27;
    public UserFiltersScreen(Screen parent) { super(Component.literal("ChatSorter — Пользовательские фильтры")); this.parent=parent; }
    @Override protected void init() {
        int x=width/2-180;
        category=new EditBox(font,x,52,350,20,Component.literal("Категория"));
        category.setMaxLength(24); category.setHint(Component.literal("Название категории")); addRenderableWidget(category);
        regex=new EditBox(font,x,82,350,20,Component.literal("Regex"));
        regex.setMaxLength(256); regex.setHint(Component.literal("Например: \\[(Money|Economy)\\]")); addRenderableWidget(regex);
        sample=new EditBox(font,x,112,350,20,Component.literal("Тестовое сообщение"));
        sample.setMaxLength(4096); sample.setHint(Component.literal("Тестовое сообщение")); addRenderableWidget(sample);
        priority=new EditBox(font,x,142,62,20,Component.literal("Приоритет"));
        priority.setMaxLength(5); priority.setValue("0"); addRenderableWidget(priority);
    }
    private int left(){ return width/2-180; }
    private int bottom(){ return height-40; }
    private void button(GuiGraphicsExtractor c,String label,int x,int y,int w,int h,int mx,int my) {
        boolean hover=mx>=x&&mx<x+w&&my>=y&&my<y+h;
        c.fill(x,y,x+w,y+h,hover?ChatTheme.accent():0xFF30363E);
        c.text(font, label, (x+w/2) - font.width(label) / 2, y+(h-8)/2, 0xFFFFFFFF, true);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor c,int mx,int my,float delta) {
        c.fill(0,0,width,height,0xEE14181D); c.fill(0,0,width,36,ChatTheme.accent());
        c.text(font, "Пользовательские фильтры", (width/2) - font.width("Пользовательские фильтры") / 2, 13, 0xFFFFFFFF, true);
        int x=left();
        c.text(font, "Категория", x, 42, 0xFFDCE2E8, true);
        c.text(font, "Regex (RE2/J)", x, 72, 0xFFDCE2E8, true);
        c.text(font, "Пробное сообщение", x, 102, 0xFFDCE2E8, true);
        c.text(font, "Приоритет", x, 148, 0xFFDCE2E8, true);
        button(c,"Поле: "+field.name(),x+70,142,130,20,mx,my);
        button(c,mode.name(),x+208,142,78,20,mx,my);
        button(c,"Проверить",x+294,142,82,20,mx,my);
        button(c,"Сохранить правило",x,171,180,22,mx,my);
        c.text(font, font.plainSubstrByWidth(status,width-30), x, 198, 0xFFBBC5CE, true);
        c.fill(12,220,width-12,bottom(),0xB52A2E33);
        c.text(font, "Правила (до 32)", x, 226, ChatTheme.text(), true);
        List<ChatUserFilters.Rule> rules=ChatUserFilters.rules();
        int rows=Math.max(1,(bottom()-252)/ROW); scroll=Math.max(0,Math.min(scroll,Math.max(0,rules.size()-rows)));
        c.enableScissor(14,248,width-14,bottom()-2);
        for(int i=0;i<rows&&i+scroll<rules.size();i++) {
            ChatUserFilters.Rule r=rules.get(i+scroll); int y=252+i*ROW;
            c.fill(x,y,width/2+180,y+24,0xAA1A2026);
            String desc=(r.enabled()?"● ":"○ ")+r.name()+"  ·  "+r.mode()+"  ·  "+r.field()+"  ·  "+r.regex();
            c.text(font, font.plainSubstrByWidth(desc,Math.max(10,width-112)), 18, y+8, r.enabled()?0xFFE2E5E8:0xFF858D95, true);
            button(c,r.enabled()?"✓":"○",width-70,y,25,23,mx,my);
            button(c,"×",width-40,y,24,23,mx,my);
        }
        c.disableScissor();
        button(c,"Включить все правила: "+(ChatExperienceSettings.customFiltersEnabled?"Да":"Нет"),12,height-31,230,22,mx,my);
        button(c,"Готово",width-104,height-31,92,22,mx,my);
        super.render(c,mx,my,delta);
    }
    @Override public boolean mouseClicked(MouseButtonEvent click,boolean doubled) {
        if(click.button()==0) {
            double mx=click.x(),my=click.y(); int x=left();
            if(mx>=width-104&&mx<width-12&&my>=height-31&&my<height-9){onClose();return true;}
            if(mx>=12&&mx<242&&my>=height-31&&my<height-9){ChatExperienceSettings.customFiltersEnabled=!ChatExperienceSettings.customFiltersEnabled;ChatExperienceSettings.save();ChatSorter.refreshChatFromFilters();return true;}
            if(mx>=x+70&&mx<x+200&&my>=142&&my<162){field=ChatUserFilters.MatchField.values()[(field.ordinal()+1)%ChatUserFilters.MatchField.values().length];return true;}
            if(mx>=x+208&&mx<x+286&&my>=142&&my<162){mode=mode==ChatUserFilters.Mode.COPY?ChatUserFilters.Mode.MOVE:ChatUserFilters.Mode.COPY;return true;}
            if(mx>=x+294&&mx<x+376&&my>=142&&my<162){status=ChatUserFilters.test(regex.getValue(),sample.getValue());return true;}
            if(mx>=x&&mx<x+180&&my>=171&&my<193){
                int p=0;try{p=Integer.parseInt(priority.getValue().strip());}catch(Exception ignored){}
                ChatUserFilters.Rule rule=new ChatUserFilters.Rule(category.getValue().strip(),regex.getValue(),field,mode,p,true);
                if(ChatUserFilters.addOrReplace(rule)){status="Правило сохранено. Выберите его категорию в панели фильтров чата.";ChatSorter.refreshChatFromFilters();}
                else status="Не удалось сохранить: проверьте имя категории и Regex (лимит 32 правила).";
                return true;
            }
            int rows=Math.max(1,(bottom()-252)/ROW);
            if(mx>=12&&mx<width-12&&my>=252&&my<252+rows*ROW){
                int index=(int)((my-252)/ROW)+scroll; List<ChatUserFilters.Rule> list=ChatUserFilters.rules();
                if(index>=0&&index<list.size()) {
                    ChatUserFilters.Rule r=list.get(index);
                    if(mx>=width-40){ChatUserFilters.remove(r.name());}
                    else if(mx>=width-70){ChatUserFilters.toggleRule(r.name());ChatSorter.refreshChatFromFilters();}
                    else {category.setValue(r.name());regex.setValue(r.regex());field=r.field();mode=r.mode();priority.setValue(Integer.toString(r.priority()));}
                    return true;
                }
            }
        }
        return super.mouseClicked(click,doubled);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        if(y>=248){scroll=Math.max(0,Math.min(Math.max(0,ChatUserFilters.rules().size()-Math.max(1,(bottom()-252)/ROW)),scroll-(int)Math.signum(vertical)));return true;}
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    @Override public void onClose(){minecraft.gui.setScreen(parent);}
}
