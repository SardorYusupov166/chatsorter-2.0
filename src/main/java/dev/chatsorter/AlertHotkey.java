package dev.chatsorter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.glfw.GLFW;
import java.nio.file.*;
import java.util.Properties;
public final class AlertHotkey {
    private static final Path PATH=Path.of("config","chatsorter","hotkey.properties");
    private static int key=-1;
    private static boolean wasDown=false;
    private static boolean prompted=false;
    public static void load(){try{if(Files.exists(PATH)){Properties p=new Properties();try(var in=Files.newInputStream(PATH)){p.load(in);}key=Integer.parseInt(p.getProperty("key","-1"));}}catch(Exception ignored){}}
    public static boolean needsSetup(){return key<0 && !prompted;}
    public static void prompt(){prompted=true;MinecraftClient mc=MinecraftClient.getInstance();mc.setScreen(new HotkeySetupScreen(mc.currentScreen));}
    public static String keyName(){return key<0?"Не назначена":GLFW.glfwGetKeyName(key,0)==null?"Код "+key:GLFW.glfwGetKeyName(key,0).toUpperCase(java.util.Locale.ROOT);}
    public static void assign(int newKey){key=newKey;wasDown=true;try{Files.createDirectories(PATH.getParent());Properties p=new Properties();p.setProperty("key",Integer.toString(key));try(var out=Files.newOutputStream(PATH)){p.store(out,"ChatSorter alert screen key");}}catch(Exception ignored){}}
    public static void tick(MinecraftClient mc){
        if(key<0 || mc.getWindow()==null)return;
        boolean down=GLFW.glfwGetKey(mc.getWindow().getHandle(),key)==GLFW.GLFW_PRESS;
        if(down && !wasDown && mc.currentScreen==null) mc.setScreen(new AlertSettingsScreen(mc.currentScreen));
        wasDown=down;
    }
    private AlertHotkey(){}
}
