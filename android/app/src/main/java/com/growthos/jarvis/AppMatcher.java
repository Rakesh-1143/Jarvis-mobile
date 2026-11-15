package com.growthos.jarvis;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Picks the installed app whose name best matches spoken words. Pure Java, unit-tested. */
public final class AppMatcher {
    public static final class App {
        public final String label, packageName;
        public App(String label, String packageName) { this.label = label; this.packageName = packageName; }
    }

    /** Spoken words -> common app name. Only used when no installed app matches the words directly. */
    private static final Map<String, String[]> ALIASES = new HashMap<>();
    static {
        alias("whatsapp", "whatsapp", "whats app", "what's app", "what sap", "watsapp", "whatsup");
        alias("youtube", "youtube", "you tube", "yt", "videos", "video app");
        alias("chrome", "chrome", "google chrome", "browser", "internet");
        alias("play store", "play store", "playstore", "google play", "app store");
        alias("maps", "maps", "google maps", "map");
        alias("gallery", "gallery", "photos", "google photos", "pictures");
        alias("photos", "photos", "gallery", "pictures");
        alias("phone", "phone", "dialer", "dial pad", "calls");
        alias("messages", "messages", "message", "sms", "texts");
        alias("contacts", "contacts", "contact");
        alias("calculator", "calculator", "calc");
        alias("camera", "camera");
        alias("settings", "settings", "setting");
        alias("gmail", "gmail", "g mail", "email", "mail");
        alias("instagram", "instagram", "insta");
        alias("facebook", "facebook", "fb");
        alias("clock", "clock", "alarm", "alarms");
        alias("files", "files", "file manager", "files by google");
        alias("claude", "claude", "cloud", "claud", "clawed", "cloude", "clod", "claude ai", "cloud ai");
        alias("chatgpt", "chatgpt", "chat gpt", "chat gbt", "chat gpd");
    }
    private static void alias(String target, String... spoken) { for (String s : spoken) ALIASES.put(norm(s), new String[]{target}); }

    /** Lowercase, no accents, no punctuation, single spaces. */
    public static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replace("'", "").replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String compact(String s) { return norm(s).replace(" ", ""); }

    /** Best match, or null when nothing is close enough. */
    public static App match(String spoken, List<App> apps) {
        String q = norm(spoken).replaceFirst("^(the|my)\\s+", "").replaceFirst("\\s+(app|application)$", "");
        if (q.isEmpty()) return null;
        App best = find(q, apps);
        if (best != null) return best;
        String[] alias = ALIASES.get(q);
        return alias == null ? null : find(alias[0], apps);
    }

    /** Best match for a person's name (no app aliases), or null. */
    public static App matchName(String spoken, List<App> people) {
        String q = norm(spoken).replaceFirst("^(the|my)\\s+", "");
        return q.isEmpty() ? null : find(q, people);
    }

    private static App find(String q, List<App> apps) {
        String cq = compact(q);
        App best = null; int bestScore = 0;
        for (App a : apps) {
            String l = norm(a.label), cl = compact(a.label);
            int score;
            if (cl.equals(cq)) score = 100;
            else if (l.startsWith(q + " ") || cl.startsWith(cq) && cq.length() >= 4) score = 80;
            else if ((" " + l + " ").contains(" " + q + " ")) score = 70;
            else if (similarity(cq, cl) >= 0.8) score = 60;
            else score = soundsAlike(q, l) ? 50 : 0;
            // Prefer shorter labels on ties ("Maps" over "Maps Go").
            if (score > bestScore || score == bestScore && score > 0 && best != null && a.label.length() < best.label.length()) { best = a; bestScore = score; }
        }
        return bestScore > 0 ? best : null;
    }

    /**
     * Speech engines often write a name as a similar-sounding word ("cloud" for Claude,
     * "ravie" for Ravi). Compares the consonant sound of the words; the first word, or all words.
     */
    static boolean soundsAlike(String q, String label) {
        String a = sound(q.replace(" ", ""));
        if (a.length() < 3) return false;
        if (a.equals(sound(label.replace(" ", "")))) return true;
        String first = label.split(" ")[0];
        return !first.equals(label) && a.equals(sound(first));
    }

    /** Rough sound key: first letter, then consonants, with similar sounds merged. */
    static String sound(String w) {
        if (w.isEmpty()) return "";
        w = w.replace("ph", "f").replace("ck", "k").replace("sh", "s").replace("th", "t").replace("gh", "g");
        StringBuilder b = new StringBuilder();
        char prev = 0;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            switch (c) { case 'c': case 'q': c = 'k'; break; case 'z': c = 's'; break; case 'v': c = 'w'; break; case 'j': c = 'g'; break; default: }
            boolean vowel = "aeiouyh".indexOf(c) >= 0;
            if (i > 0 && vowel) { prev = 0; continue; }
            if (c != prev) b.append(c);
            prev = c;
        }
        return b.toString();
    }

    /** 1 - edit distance / longer length. */
    static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev; prev = cur; cur = t;
        }
        return 1.0 - (double) prev[b.length()] / Math.max(a.length(), b.length());
    }
}
