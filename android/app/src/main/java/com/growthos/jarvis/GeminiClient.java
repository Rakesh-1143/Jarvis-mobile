package com.growthos.jarvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Answers general questions with Google Gemini, using the user's own key that they saved in the
 * KALKI app (copied from the app page on this phone; never hardcoded, never sent anywhere else).
 * Only the recognized words are sent, never audio. Call ask() off the main thread.
 */
final class GeminiClient {
    static final String PREFS = "jarvis_ai", KEY = "gemini_key";
    private static final String[] MODELS = {"gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.1-flash-lite"};
    private static final String SYSTEM = "You are KALKI (Jarvis), a voice assistant on the user's Android phone. The user is called Boss. "
            + "Your reply is spoken aloud: answer in 1 to 3 short sentences of plain text, no markdown, lists, emojis or links. "
            + "Use Telugu or Teluglish only if the user does. If you don't know a live fact (news, prices, weather), say so briefly. "
            + "Never claim you did an action on the phone.";
    /** Last few turns, so follow-up questions work. Kept in memory only. */
    private static final Deque<String[]> history = new ArrayDeque<>();

    static void saveKey(Context c, String key) {
        if (key == null) return;
        key = key.trim();
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (key.isEmpty() || key.equals(p.getString(KEY, ""))) return;
        p.edit().putString(KEY, key).apply();
    }

    static boolean hasKey(Context c) { return !c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").isEmpty(); }

    static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities nc = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) { return false; }
    }

    /** Returns the spoken answer, or a short spoken error. Blocking. */
    static String ask(Context c, String question) {
        String key = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
        if (key.isEmpty()) return "Boss, I need your Gemini key for that. Open the KALKI app once after saving it in Settings.";
        if (!online(c)) return "Boss, internet is unavailable. I can still perform offline actions.";
        String last = "Boss, I couldn't reach Gemini right now.";
        for (String model : MODELS) {
            try {
                JSONArray contents = new JSONArray();
                synchronized (history) {
                    for (String[] t : history) {
                        contents.put(turn("user", t[0]));
                        contents.put(turn("model", t[1]));
                    }
                }
                contents.put(turn("user", question));
                JSONObject body = new JSONObject()
                        .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", SYSTEM))))
                        .put("contents", contents)
                        .put("generationConfig", new JSONObject().put("maxOutputTokens", 300));
                HttpURLConnection h = (HttpURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent").openConnection();
                h.setConnectTimeout(8000); h.setReadTimeout(15000);
                h.setRequestMethod("POST"); h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/json");
                h.setRequestProperty("x-goog-api-key", key);
                try (OutputStream o = h.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                int code = h.getResponseCode();
                String resp = read(code >= 400 ? h.getErrorStream() : h.getInputStream());
                if (code == 400 || code == 401 || code == 403) { if (resp.contains("API_KEY") || resp.contains("API key")) return "Boss, your Gemini key was refused. Please check it in KALKI settings."; }
                if (code != 200) { last = "Boss, Gemini is busy right now. Please try again."; continue; }
                JSONObject j = new JSONObject(resp);
                String text = j.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
                text = clean(text);
                synchronized (history) { history.addLast(new String[]{question, text}); while (history.size() > 3) history.removeFirst(); }
                return text;
            } catch (Exception e) { last = "Boss, I couldn't reach Gemini right now."; }
        }
        return last;
    }

    private static JSONObject turn(String role, String text) throws Exception {
        return new JSONObject().put("role", role).put("parts", new JSONArray().put(new JSONObject().put("text", text)));
    }

    /** Removes markdown so text-to-speech doesn't read symbols. */
    static String clean(String t) {
        return t.replaceAll("[*_#`>]+", "").replaceAll("\\[(.*?)\\]\\(.*?\\)", "$1").replaceAll("\\s+", " ").trim();
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }
}
