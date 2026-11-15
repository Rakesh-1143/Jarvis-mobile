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
import java.util.List;
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
                        .put("generationConfig", new JSONObject().put("maxOutputTokens", 1024));
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
                String text = clean(replyText(j));
                if (text.isEmpty()) { last = "Boss, Gemini gave no answer. Please try again."; continue; }
                synchronized (history) { history.addLast(new String[]{question, text}); while (history.size() > 3) history.removeFirst(); }
                return text;
            } catch (Exception e) { last = "Boss, I couldn't reach Gemini right now."; }
        }
        return last;
    }

    /** The reply's text parts joined, skipping "thought" parts that thinking models may add. */
    static String replyText(JSONObject j) throws Exception {
        JSONArray parts = j.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.getJSONObject(i);
            if (p.optBoolean("thought", false)) continue;
            b.append(p.optString("text", ""));
        }
        return b.toString();
    }

    static final String BRAIN = "You are the understanding part of KALKI (Jarvis), a voice assistant on an Android phone in India. "
            + "You get what the speech recognizer heard (several guesses, best first; they may have mistakes, Indian accent, Telugu or Teluglish words) "
            + "and the names of installed apps. Work out what the user wants and reply with ONE JSON object only: "
            + "{\"action\":\"...\",\"target\":\"...\",\"message\":\"...\",\"answer\":\"...\"}. Use \"\" for unused fields.\n"
            + "Allowed actions and fields:\n"
            + "OPEN_APP target=exact app name from the list. SEARCH target=app name or \"\" for web, message=what to search. "
            + "SEND_MESSAGE target=person name, message=text to send (WhatsApp). WHATSAPP_CALL target=person, message=voice|video. "
            + "CALL target=person name or phone number (normal phone call). CALL_CONTROL target=answer|reject|end. "
            + "ASK_APP target=claude|chatgpt|gemini|perplexity|copilot, message=question (only when the user names that app). "
            + "SCROLL target=down|up. PLAY_ITEM target=1-6 (which video/result on screen). MEDIA target=play|pause|next|previous. "
            + "TAP target=visible button or text to tap. TYPE message=text to type. BACK, HOME, RECENTS, NOTIFICATIONS (open the shade), READ_SCREEN. "
            + "TIME, DATE, BATTERY. VOLUME target=up|down|mute|unmute|max. TORCH target=on|off. "
            + "SETTINGS target=wifi|bluetooth|mobile data|hotspot|location|display|sound|battery|apps|storage|security|date and time|airplane mode. "
            + "ALARM target=HH:MM 24-hour. TIMER target=seconds. REMINDER target=HH:MM 24-hour or +seconds from now, message=what to remind. "
            + "READ_NOTIFICATIONS target=app name or \"\". NOTE message=text to save. READ_NOTES. CLEAR_NOTES. NAVIGATE target=place. "
            + "WEATHER target=city or \"\". PHOTO target=photo|selfie. SET_NAME target=what to call the user (message=city to set the weather city instead). "
            + "SPEECH_SPEED target=faster|slower|normal. HELP. STOP_LISTENING. CANCEL.\n"
            + "ANSWER answer=1 to 3 short spoken sentences, plain text: use it for questions, chat, maths or facts, and when the request is not one of the actions "
            + "(say briefly what you can't do). Never invent an action or claim you did something. If you don't know a live fact, say so.\n"
            + "Rules: pick the guess that makes most sense. Fix misheard app names to the closest installed app (cloud=Claude, what's up=WhatsApp, you tube=YouTube). "
            + "Never choose banking, UPI, payment or password apps. Keep message text in the user's own words and language. "
            + "Times: 'morning 7' = 07:00, 'evening 6' = 18:00, 'night 9' = 21:00. "
            + "If it is unclear, use ANSWER to ask one short question. The user is called %s; address them that way in answers.";

    /** Last few understood turns, so "send it to him too" style follow-ups work. Memory only. */
    private static final Deque<String[]> brainHistory = new ArrayDeque<>();

    /**
     * The brain: asks Gemini which allowed action the words mean. Sends only the recognized words and
     * installed app names (never audio, contacts or screen content). Blocking; call off the main thread.
     * Returns {action, target, message, answer}, or null if Gemini can't be reached.
     */
    static String[] understand(Context c, List<String> guesses, List<String> appNames, String userName) {
        String key = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
        if (key.isEmpty() || !online(c)) return null;
        StringBuilder q = new StringBuilder("Heard: ");
        int n = 0;
        for (String g : guesses) { if (g == null || g.trim().isEmpty()) continue; if (n++ > 0) q.append(" | "); q.append(g.trim()); if (n >= 3) break; }
        q.append("\nInstalled apps: ");
        int a = 0;
        for (String name : appNames) { if (a++ > 0) q.append(", "); q.append(name); if (a >= 250) break; }
        String sys = String.format(java.util.Locale.ROOT, BRAIN, userName == null || userName.isEmpty() ? "Boss" : userName);
        for (String model : MODELS) {
            try {
                JSONArray contents = new JSONArray();
                synchronized (brainHistory) {
                    for (String[] t : brainHistory) { contents.put(turn("user", "Heard: " + t[0])); contents.put(turn("model", t[1])); }
                }
                contents.put(turn("user", q.toString()));
                JSONObject body = new JSONObject()
                        .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", sys))))
                        .put("contents", contents)
                        .put("generationConfig", new JSONObject().put("maxOutputTokens", 1024).put("temperature", 0.2).put("responseMimeType", "application/json"));
                HttpURLConnection h = (HttpURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent").openConnection();
                h.setConnectTimeout(8000); h.setReadTimeout(15000);
                h.setRequestMethod("POST"); h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/json");
                h.setRequestProperty("x-goog-api-key", key);
                try (OutputStream o = h.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                int code = h.getResponseCode();
                String resp = read(code >= 400 ? h.getErrorStream() : h.getInputStream());
                if (code != 200) continue;
                String[] r = parseBrain(replyText(new JSONObject(resp)));
                if (r == null) continue;
                String first = guesses.isEmpty() || guesses.get(0) == null ? "" : guesses.get(0);
                synchronized (brainHistory) {
                    brainHistory.addLast(new String[]{first, new JSONObject().put("action", r[0]).put("target", r[1]).put("message", r[2]).put("answer", r[3]).toString()});
                    while (brainHistory.size() > 3) brainHistory.removeFirst();
                }
                return r;
            } catch (Exception ignored) { }
        }
        return null;
    }

    /** Reads the brain's JSON (tolerates ```json fences or extra words). Returns null if it isn't usable. */
    static String[] parseBrain(String text) {
        if (text == null) return null;
        int s = text.indexOf('{'), e = text.lastIndexOf('}');
        if (s < 0 || e <= s) return null;
        try {
            JSONObject o = new JSONObject(text.substring(s, e + 1));
            String action = o.optString("action", "").trim().toUpperCase(java.util.Locale.ROOT);
            if (action.isEmpty()) return null;
            return new String[]{action, o.optString("target", "").trim(), o.optString("message", "").trim(), clean(o.optString("answer", ""))};
        } catch (Exception ex) { return null; }
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
