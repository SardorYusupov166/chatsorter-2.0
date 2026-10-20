package dev.chatsorter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;

/** Persistent per-server snapshots of bookmarked source messages. */
public final class ChatBookmarks {
    private static final Path DIRECTORY=FabricLoader.getInstance().getConfigDir().resolve("chatsorter").resolve("bookmarks");
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(task->{Thread t=new Thread(task,"ChatSorter-bookmark-writer");t.setDaemon(true);return t;});
    private static final LinkedHashMap<UUID,ChatMessageRecord> entries=new LinkedHashMap<>();
    private static String serverKey;

    public static synchronized void load(String server) {
        entries.clear(); serverKey=server;
        if(server==null) return;
        Path path=file(server);
        if(!Files.isRegularFile(path)) return;
        try {
            JsonArray array=JsonParser.parseString(Files.readString(path)).getAsJsonArray();
            for(JsonElement element:array) {
                if(!element.isJsonObject()) continue;
                JsonObject o=element.getAsJsonObject();
                UUID id=uuid(o,"id"); String raw=str(o,"rawText","");
                String component=str(o,"component",""); Text text=ChatHistoryStorage.deserializeText(component);
                if(text==null) text=Text.literal(raw);
                Set<String> categories=readCategories(o);
                ChatMessageRecord record=new ChatMessageRecord(id,str(o,"serverId",server),str(o,"sessionId",""),
                    instant(o,"receivedAt"),str(o,"senderId",""),str(o,"senderName",""),raw,text,component,
                    categories,str(o,"conversationId",""),integer(o,"repeatCount",1),false);
                entries.put(record.id(),record);
            }
        } catch(Exception e) {System.err.println("[ChatSorter] Не удалось загрузить закладки: "+e.getMessage());}
    }
    public static synchronized List<ChatMessageRecord> all(){return List.copyOf(entries.values());}
    public static synchronized boolean contains(UUID id){return entries.containsKey(id);}
    public static synchronized boolean contains(ChatHistoryEntry entry){return entry!=null&&entry.sourceRecords().stream().anyMatch(s->entries.containsKey(s.id()));}
    public static synchronized boolean toggle(ChatHistoryEntry entry) {
        if(entry==null||entry.sourceRecords().isEmpty()) return false;
        boolean remove=entry.sourceRecords().stream().allMatch(s->entries.containsKey(s.id()));
        for(ChatMessageRecord source:entry.sourceRecords()) {
            if(remove) entries.remove(source.id());
            else entries.put(source.id(),source);
        }
        save(); return !remove;
    }
    private static synchronized void save() {
        if(serverKey==null)return;
        ArrayList<ChatMessageRecord> snapshot=new ArrayList<>(entries.values()); Path target=file(serverKey);
        IO.execute(()->write(target,snapshot));
    }
    public static synchronized void flush() {
        if(serverKey==null)return;
        ArrayList<ChatMessageRecord> snapshot=new ArrayList<>(entries.values()); Path target=file(serverKey);
        CompletableFuture<Void> done=new CompletableFuture<>();
        try{IO.execute(()->{write(target,snapshot);done.complete(null);});done.get(3,TimeUnit.SECONDS);}
        catch(Exception e){System.err.println("[ChatSorter] Не удалось завершить сохранение закладок вовремя: "+e.getMessage());}
    }
    private static void write(Path target,List<ChatMessageRecord> snapshot) {
        try {
            Files.createDirectories(DIRECTORY); JsonArray array=new JsonArray();
            for(ChatMessageRecord r:snapshot) {
                JsonObject o=new JsonObject();o.addProperty("id",r.id().toString());o.addProperty("serverId",r.serverId());
                o.addProperty("sessionId",r.sessionId());o.addProperty("receivedAt",r.receivedAt().toString());
                o.addProperty("senderId",r.senderId());o.addProperty("senderName",r.senderName());
                o.addProperty("rawText",r.rawText());o.addProperty("component",r.componentJson().isBlank()?ChatHistoryStorage.serializeText(r.message()):r.componentJson());
                o.addProperty("conversationId",r.conversationId());o.addProperty("repeatCount",r.repeatCount());
                JsonArray categories=new JsonArray();for(String category:r.categories())categories.add(category);o.add("categories",categories);
                array.add(o);
            }
            Path temporary=target.resolveSibling(target.getFileName()+".tmp");
            Files.writeString(temporary,array.toString(),StandardCharsets.UTF_8);
            try{Files.move(temporary,target,java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}
            catch(java.nio.file.AtomicMoveNotSupportedException ignored){Files.move(temporary,target,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
        } catch(Exception e){System.err.println("[ChatSorter] Не удалось сохранить закладки: "+e.getMessage());}
    }
    private static Set<String> readCategories(JsonObject o){
        LinkedHashSet<String> result=new LinkedHashSet<>();
        try{if(o.has("categories"))for(JsonElement e:o.getAsJsonArray("categories"))if(e.isJsonPrimitive())result.add(e.getAsString());}catch(Exception ignored){}
        return Set.copyOf(result);
    }
    private static String str(JsonObject o,String key,String fallback){try{return o.has(key)?o.get(key).getAsString():fallback;}catch(Exception ignored){return fallback;}}
    private static int integer(JsonObject o,String key,int fallback){try{return o.has(key)?o.get(key).getAsInt():fallback;}catch(Exception ignored){return fallback;}}
    private static UUID uuid(JsonObject o,String key){try{return UUID.fromString(str(o,key,""));}catch(Exception ignored){return UUID.randomUUID();}}
    private static Instant instant(JsonObject o,String key){try{return Instant.parse(str(o,key,""));}catch(Exception ignored){return Instant.now();}}
    private static Path file(String server){
        try{return DIRECTORY.resolve(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(server.getBytes(StandardCharsets.UTF_8)))+".json");}
        catch(Exception e){return DIRECTORY.resolve("unknown.json");}
    }
    private ChatBookmarks(){}
}
