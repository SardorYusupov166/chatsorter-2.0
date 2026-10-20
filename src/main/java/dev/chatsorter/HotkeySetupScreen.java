package dev.chatsorter;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
public final class HotkeySetupScreen extends Screen {
    private final Screen parent;
    private int wait=12;
    private final boolean[] previous=new boolean[GLFW.GLFW_KEY_LAST+1];
    public HotkeySetupScreen(Screen parent){super(Text.literal("ChatSorter — назначение клавиши"));this.parent=parent;}
    @Override public void tick(){
        super.tick();if(client==null)return;
        long handle=client.getWindow().getHandle();
        for(int k=GLFW.GLFW_KEY_SPACE;k<=GLFW.GLFW_KEY_LAST;k++){
            boolean pressed=GLFW.glfwGetKey(handle,k)==GLFW.GLFW_PRESS;
            if(wait<=0 && pressed && !previous[k] && k!=GLFW.GLFW_KEY_ESCAPE){AlertHotkey.assign(k);client.setScreen(parent);return;}
            previous[k]=pressed;
        }
        if(wait>0)wait--;
    }
    @Override public void render(DrawContext c,int mx,int my,float delta){
        c.fill(0,0,width,height,0xEE14181D);
        c.drawCenteredTextWithShadow(textRenderer,"ChatSorter — назначение горячей клавиши",width/2,height/2-36,ChatTheme.bright());
        c.drawCenteredTextWithShadow(textRenderer,"Нажмите любую клавишу для открытия настроек уведомлений",width/2,height/2-10,0xFFFFFFFF);
        c.drawCenteredTextWithShadow(textRenderer,"ESC — позже (при следующем входе предложим снова)",width/2,height/2+16,0xFFAAAAAA);
        super.render(c,mx,my,delta);
    }
    @Override public void close(){client.setScreen(parent);}
}
