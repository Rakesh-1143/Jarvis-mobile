package com.growthos.jarvis;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Voice notes, saved only in KALKI's private storage on this phone. */
final class Notes {
    private Notes() { }

    private static File file(Context c) { return new File(c.getFilesDir(), "voice_notes.txt"); }

    static synchronized void add(Context c, String text) throws Exception {
        String line = new SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH).format(new Date()) + "|" + text.replace('\n', ' ').trim() + "\n";
        Files.write(file(c).toPath(), line.getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    /** Newest first. */
    static synchronized List<String[]> all(Context c) {
        List<String[]> out = new ArrayList<>();
        try {
            if (!file(c).exists()) return out;
            for (String l : Files.readAllLines(file(c).toPath(), StandardCharsets.UTF_8)) {
                int i = l.indexOf('|');
                if (i > 0) out.add(new String[]{l.substring(0, i), l.substring(i + 1)});
            }
        } catch (Exception ignored) { }
        Collections.reverse(out);
        return out;
    }

    static synchronized void clear(Context c) { file(c).delete(); }

    static String speak(Context c, int max) {
        List<String[]> n = all(c);
        if (n.isEmpty()) return "Boss, you have no notes.";
        StringBuilder b = new StringBuilder("Boss, you have " + n.size() + (n.size() == 1 ? " note. " : " notes. "));
        for (int i = 0; i < Math.min(max, n.size()); i++) b.append(n.get(i)[1]).append(". ");
        if (n.size() > max) b.append("And ").append(n.size() - max).append(" older.");
        return b.toString().trim();
    }
}
