package dev.chatsorter;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.loader.api.FabricLoader;

/** User filters compiled with RE2/J, whose matching time is linear in input length. */
public final class ChatUserFilters {
    public enum Mode { COPY, MOVE }
    public enum MatchField { TEXT, SENDER, CATEGORY }
    public record Rule(String name, String regex, MatchField field, Mode mode, int priority, boolean enabled) { }
    public record Match(Set<String> categories, boolean moved) { }

    private static final int MAX_RULES = 32;
    private static final int MAX_PATTERN_LENGTH = 256;
    private static final int MAX_MATCH_TEXT = 4096;
    public static final String MOVED_MARKER = "@MOVED";
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("chatsorter").resolve("filters.properties");
    private static final ArrayList<Rule> rules = new ArrayList<>();
    private static final Map<String, Pattern> compiled = new LinkedHashMap<>();
    private static final Map<String, Boolean> enabledCategories = new LinkedHashMap<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ChatSorter-filter-config"); thread.setDaemon(true); return thread;
    });

    public static void load() {
        rules.clear(); compiled.clear(); enabledCategories.clear();
        if (!Files.isRegularFile(FILE)) return;
        try (InputStream in = Files.newInputStream(FILE)) {
            Properties p = new Properties(); p.load(in);
            int count = Math.min(MAX_RULES, Math.max(0, Integer.parseInt(p.getProperty("count", "0"))));
            for (int i = 0; i < count; i++) {
                try {
                    Rule rule = new Rule(decode(p.getProperty("r."+i+".name", "")),
                        decode(p.getProperty("r."+i+".regex", "")),
                        MatchField.valueOf(p.getProperty("r."+i+".field", "TEXT")),
                        Mode.valueOf(p.getProperty("r."+i+".mode", "COPY")),
                        Integer.parseInt(p.getProperty("r."+i+".priority", "0")),
                        Boolean.parseBoolean(p.getProperty("r."+i+".enabled", "true")));
                    validate(rule.name(), rule.regex());
                    Pattern pattern=Pattern.compile(rule.regex());
                    if(pattern.programSize()>100_000) throw new IllegalArgumentException("Regex слишком сложный");
                    compiled.put(key(rule), pattern);
                    rules.add(rule);
                } catch (Exception e) {
                    System.err.println("[ChatSorter] Пропущено повреждённое правило фильтра №"+(i+1)+": "+e.getMessage());
                }
            }
            for (String key : p.stringPropertyNames()) if (key.startsWith("category.")) {
                enabledCategories.put(decode(key.substring("category.".length())), Boolean.parseBoolean(p.getProperty(key)));
            }
        } catch (Exception e) { System.err.println("[ChatSorter] Не удалось загрузить фильтры: "+e.getMessage()); }
    }

    public static synchronized List<Rule> rules() { return List.copyOf(rules); }
    public static synchronized List<String> categories() {
        return rules.stream().map(Rule::name).distinct().toList();
    }
    public static synchronized boolean categoryEnabled(String category) {
        return enabledCategories.getOrDefault(category, false);
    }
    public static synchronized void toggleCategory(String category) {
        enabledCategories.put(category, !categoryEnabled(category)); save(); ChatSorter.refreshChatFromFilters();
    }
    public static synchronized void setCategoryEnabled(String category, boolean enabled) {
        enabledCategories.put(category, enabled);
    }
    public static synchronized boolean addOrReplace(Rule rule) {
        if (rule == null || rules.size() >= MAX_RULES && rules.stream().noneMatch(r -> r.name().equalsIgnoreCase(rule.name()))) return false;
        try {
            validate(rule.name(), rule.regex());
            Pattern pattern = Pattern.compile(rule.regex());
            if (pattern.programSize() > 100_000) return false;
            int index = indexOf(rule.name());
            if (index >= 0) rules.set(index, rule); else rules.add(rule);
            compiled.keySet().removeIf(k -> k.startsWith(rule.name().toLowerCase(Locale.ROOT)+"\u0000"));
            compiled.put(key(rule), pattern);
            enabledCategories.putIfAbsent(rule.name(), false);
            save();
            return true;
        } catch (PatternSyntaxException | IllegalArgumentException e) { return false; }
    }
    public static synchronized void toggleRule(String name) {
        int i=indexOf(name); if(i<0) return;
        Rule r=rules.get(i); addOrReplace(new Rule(r.name(),r.regex(),r.field(),r.mode(),r.priority(),!r.enabled()));
    }
    public static synchronized void remove(String name) {
        rules.removeIf(r -> r.name().equalsIgnoreCase(name));
        compiled.keySet().removeIf(k -> k.startsWith(name.toLowerCase(Locale.ROOT)+"\u0000"));
        enabledCategories.remove(name); save(); ChatSorter.refreshChatFromFilters();
    }
    public static synchronized String test(String regex, String text) {
        try {
            validate("test", regex);
            Pattern pattern = Pattern.compile(regex);
            if (pattern.programSize() > 100_000) return "Слишком сложное выражение";
            String safe = text == null ? "" : text.substring(0, Math.min(text.length(), MAX_MATCH_TEXT));
            return pattern.matcher(safe).find() ? "Совпадение найдено" : "Совпадений нет";
        } catch (PatternSyntaxException | IllegalArgumentException e) { return "Ошибка Regex: " + e.getMessage(); }
    }

    public static synchronized Match match(String text, String sender, String category) {
        if (!ChatExperienceSettings.customFiltersEnabled || rules.isEmpty()) return new Match(Set.of(), false);
        String safeText = bounded(text), safeSender = bounded(sender), safeCategory = bounded(category);
        List<Rule> ordered = rules.stream().filter(Rule::enabled).sorted(Comparator.comparingInt(Rule::priority).reversed()).toList();
        LinkedHashSet<String> matched = new LinkedHashSet<>();
        boolean moved = false, moveDecisionSet = false;
        for (Rule rule : ordered) {
            String value = switch (rule.field()) { case TEXT -> safeText; case SENDER -> safeSender; case CATEGORY -> safeCategory; };
            Pattern pattern = compiled.get(key(rule));
            if (pattern == null) continue;
            try {
                if (pattern.matcher(value).find()) {
                    matched.add(rule.name());
                    if (!moveDecisionSet) { moved = rule.mode() == Mode.MOVE; moveDecisionSet = true; }
                }
            } catch (RuntimeException e) {
                System.err.println("[ChatSorter] Ошибка применения Regex для категории «"+rule.name()+"»: "+e.getMessage());
            }
        }
        if (moved) matched.add(MOVED_MARKER);
        return new Match(Set.copyOf(matched), moved);
    }

    private static String bounded(String value) { if (value == null) return ""; return value.substring(0, Math.min(value.length(), MAX_MATCH_TEXT)); }
    private static int indexOf(String name) { for (int i=0;i<rules.size();i++) if (rules.get(i).name().equalsIgnoreCase(name)) return i; return -1; }
    private static String key(Rule r) { return r.name().toLowerCase(Locale.ROOT)+"\u0000"+r.regex(); }
    private static void validate(String name, String regex) {
        if (name == null || name.isBlank() || name.length() > 24 || name.startsWith("@")) throw new IllegalArgumentException("Имя категории: 1–24 символа, нельзя начинать с @");
        for (ChatSorter.Tab tab : ChatSorter.Tab.values()) if (tab.name().equalsIgnoreCase(name)) throw new IllegalArgumentException("Это имя уже занято встроенной вкладкой");
        if (regex == null || regex.isBlank() || regex.length() > MAX_PATTERN_LENGTH) throw new IllegalArgumentException("Regex должен содержать 1–256 символов");
    }
    private static String encode(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String s) { try { return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8); } catch (Exception ignored) { return ""; } }
    private static synchronized void save() {
        Properties snapshot = new Properties(); snapshot.setProperty("count", Integer.toString(rules.size()));
        for (int i=0;i<rules.size();i++) {
            Rule r=rules.get(i); String base="r."+i+".";
            snapshot.setProperty(base+"name",encode(r.name())); snapshot.setProperty(base+"regex",encode(r.regex()));
            snapshot.setProperty(base+"field",r.field().name()); snapshot.setProperty(base+"mode",r.mode().name());
            snapshot.setProperty(base+"priority",Integer.toString(r.priority())); snapshot.setProperty(base+"enabled",Boolean.toString(r.enabled()));
        }
        enabledCategories.forEach((name,enabled)->snapshot.setProperty("category."+encode(name),Boolean.toString(enabled)));
        IO.execute(() -> write(snapshot));
    }
    private static void write(Properties p) {
        try {
            Files.createDirectories(FILE.getParent());
            try (var out=Files.newOutputStream(FILE)) { p.store(out,"ChatSorter user chat filters — RE2/J syntax"); }
        } catch (Exception e) { System.err.println("[ChatSorter] Не удалось сохранить фильтры: "+e.getMessage()); }
    }
    private ChatUserFilters() { }
}
