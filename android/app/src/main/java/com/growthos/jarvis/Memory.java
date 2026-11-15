package com.growthos.jarvis;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Facts the user asks Jarvis to remember ("remember that my wife's name is Priya"). Stored in the
 * app's private files on the phone. They are added to what the brain reads so answers are personal.
 * Passwords, PINs, OTPs, card and bank details are refused and never stored.
 */
final class Memory {
    private Memory() { }

    static final int MAX = 50;
    private static final Pattern SECRET = Pattern.compile("(?is).*(\\botp\\b|one[- ]time|password|passcode|pass code|\\bpin\\b|\\bcvv\\b|\\bupi\\b|"
            + "card number|account number|a/c|ifsc|net ?banking|login|secret code|security code|\\b\\d{12,19}\\b).*");

    /** True for things Jarvis must never store. Pure Java, unit-tested. */
    static boolean secret(String fact) { return fact == null || SECRET.matcher(fact).matches(); }

    /** "my wife's name is priya" -> "Boss's wife's name is priya" style is left to the brain; we only tidy. */
    static String tidy(String fact) {
        String f = fact.trim().replaceAll("\\s+", " ");
        if (f.length() > 200) f = f.substring(0, 200);
        return f.isEmpty() ? f : Character.toUpperCase(f.charAt(0)) + f.substring(1);
    }

    private static File file(Context c) { return new File(c.getFilesDir(), "memory.txt"); }

    static synchronized List<String> all(Context c) {
        List<String> out = new ArrayList<>();
        try {
            File f = file(c);
            if (f.exists()) for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) if (!l.trim().isEmpty()) out.add(l.trim());
        } catch (Exception ignored) { }
        return out;
    }

    private static void write(Context c, List<String> facts) throws Exception {
        Files.write(file(c).toPath(), String.join("\n", facts).getBytes(StandardCharsets.UTF_8));
    }

    /** Returns false if the fact looks like a secret (not stored). */
    static synchronized boolean add(Context c, String fact) throws Exception {
        if (secret(fact)) return false;
        String f = tidy(fact);
        if (f.isEmpty()) return false;
        List<String> facts = all(c);
        facts.removeIf(x -> x.equalsIgnoreCase(f));
        facts.add(f);
        while (facts.size() > MAX) facts.remove(0);
        write(c, facts);
        return true;
    }

    /** Removes facts containing all the given words. Returns how many were removed. */
    static synchronized int forget(Context c, String about) throws Exception {
        List<String> facts = all(c);
        int before = facts.size();
        String[] words = about.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]", " ").trim().split("\\s+");
        facts.removeIf(x -> {
            String l = x.toLowerCase(Locale.ROOT);
            for (String w : words) if (w.length() > 1 && !l.contains(w)) return false;
            return true;
        });
        write(c, facts);
        return before - facts.size();
    }

    static synchronized void clear(Context c) { file(c).delete(); }

    static String speak(Context c) {
        List<String> f = all(c);
        if (f.isEmpty()) return "Boss, you haven't asked me to remember anything yet. Say: remember that, and the fact.";
        StringBuilder b = new StringBuilder("Boss, I remember ").append(f.size()).append(f.size() == 1 ? " thing. " : " things. ");
        for (int i = Math.max(0, f.size() - 8); i < f.size(); i++) b.append(f.get(i)).append(". ");
        return b.toString().trim();
    }

    /** For the brain prompt. */
    static String forPrompt(Context c) {
        List<String> f = all(c);
        if (f.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (String x : f) b.append("- ").append(x).append('\n');
        return b.toString();
    }
}
