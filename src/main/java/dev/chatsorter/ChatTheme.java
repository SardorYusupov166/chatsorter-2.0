package dev.chatsorter;
import java.nio.file.*;
import java.util.Properties;
public final class ChatTheme {
    private static final Path PATH=Path.of("config","chatsorter","theme.properties");
    private static final String[] NAMES={"Изумрудная","Гранатовая","Синяя","Фиолетовая","Графитовая"};
    private static final int[] ACCENTS={0xE048604C,0xE06B283C,0xE02B537C,0xE0593979,0xE044484E};
    private static final int[] BRIGHTS={0xFF91C79B,0xFFCE7892,0xFF82B7E9,0xFFBE9AE8,0xFFB5BCC3};
    private static int index=0;
    public static void load(){try{Properties p=new Properties();if(Files.exists(PATH)){try(var in=Files.newInputStream(PATH)){p.load(in);}index=Math.floorMod(Integer.parseInt(p.getProperty("theme","0")),NAMES.length);}}catch(Exception ignored){}}
    public static int accent(){return ACCENTS[index];}
    public static int bright(){return BRIGHTS[index];}
    public static int text(){return BRIGHTS[index];}
    public static String name(){return NAMES[index];}
    public static void next(){index=(index+1)%NAMES.length;try{Files.createDirectories(PATH.getParent());Properties p=new Properties();p.setProperty("theme",Integer.toString(index));try(var out=Files.newOutputStream(PATH)){p.store(out,"ChatSorter theme");}}catch(Exception ignored){}}
    private ChatTheme(){}
}
