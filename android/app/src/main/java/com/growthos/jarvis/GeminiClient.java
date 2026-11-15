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

    static final String KEY_ENC = "gemini_key_enc";
    private static final String KS_ALIAS = "kalki_gemini_key";
    private static volatile String cachedKey;

    /** Saves the key encrypted with an Android Keystore key (never leaves the phone's secure hardware). */
    static void saveKey(Context c, String key) {
        if (key == null) return;
        key = key.trim();
        if (key.isEmpty() || key.equals(getKey(c))) return;
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try { p.edit().putString(KEY_ENC, encrypt(key)).remove(KEY).apply(); }
        catch (Exception e) { p.edit().putString(KEY, key).apply(); }
        cachedKey = key;
    }

    /** The saved key, or "". Moves an old plain-text key to encrypted storage. */
    static String getKey(Context c) {
        String k = cachedKey;
        if (k != null) return k;
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String enc = p.getString(KEY_ENC, "");
        if (!enc.isEmpty()) {
            try { k = decrypt(enc); } catch (Exception e) { k = ""; }
        } else {
            k = p.getString(KEY, "");
            if (!k.isEmpty()) { try { p.edit().putString(KEY_ENC, encrypt(k)).remove(KEY).apply(); } catch (Exception ignored) { } }
        }
        cachedKey = k;
        return k;
    }

    static boolean hasKey(Context c) { return !getKey(c).isEmpty(); }

    static boolean keyEncrypted(Context c) { return !c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENC, "").isEmpty(); }

    private static javax.crypto.SecretKey keystoreKey() throws Exception {
        java.security.KeyStore ks = java.security.KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KS_ALIAS)) return ((java.security.KeyStore.SecretKeyEntry) ks.getEntry(KS_ALIAS, null)).getSecretKey();
        javax.crypto.KeyGenerator g = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new android.security.keystore.KeyGenParameterSpec.Builder(KS_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return g.generateKey();
    }

    private static String encrypt(String plain) throws Exception {
        javax.crypto.Cipher ci = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(javax.crypto.Cipher.ENCRYPT_MODE, keystoreKey());
        byte[] ct = ci.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return android.util.Base64.encodeToString(ci.getIV(), android.util.Base64.NO_WRAP) + ":" + android.util.Base64.encodeToString(ct, android.util.Base64.NO_WRAP);
    }

    private static String decrypt(String enc) throws Exception {
        String[] parts = enc.split(":", 2);
        javax.crypto.Cipher ci = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(javax.crypto.Cipher.DECRYPT_MODE, keystoreKey(), new javax.crypto.spec.GCMParameterSpec(128, android.util.Base64.decode(parts[0], android.util.Base64.NO_WRAP)));
        return new String(ci.doFinal(android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    /** Index in MODELS of the last model that answered; tried first next time (saves a slow failed call). */
    private static volatile int preferred = 0;

    private static String[] modelOrder() {
        String[] out = new String[MODELS.length];
        int p = preferred;
        out[0] = MODELS[p];
        for (int i = 0, j = 1; i < MODELS.length; i++) if (i != p) out[j++] = MODELS[i];
        return out;
    }

    private static void worked(String model) {
        for (int i = 0; i < MODELS.length; i++) if (MODELS[i].equals(model)) preferred = i;
    }

    static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities nc = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) { return false; }
    }

    /** Returns the spoken answer, or a short spoken error. Blocking. */
    static String ask(Context c, String question) {
        String key = getKey(c);
        if (key.isEmpty()) return "Boss, I need your Gemini key for that. Open the KALKI app once after saving it in Settings.";
        if (!online(c)) return "Boss, internet is unavailable. I can still perform offline actions.";
        String last = "Boss, I couldn't reach Gemini right now.";
        for (String model : modelOrder()) {
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
                worked(model);
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

    static final String BRAIN = "You are the brain of KALKI (Jarvis), a smart voice assistant on an Android phone in India. "
            + "You get what the speech recognizer heard (up to 3 guesses, best first; expect mistakes, Indian accent, Telugu, Hindi or Teluglish words), "
            + "the time, the app on screen, installed app names, and facts the user asked you to remember. "
            + "Work out what the user really wants and reply with ONE JSON object only:\n"
            + "{\"actions\":[{\"action\":\"...\",\"target\":\"...\",\"message\":\"...\"}],\"answer\":\"...\",\"live\":false}\n"
            + "actions: 0 to 3 steps, run in order (for example 'set alarm at 6 and turn on torch' = 2 steps). Use \"\" for unused fields. "
            + "answer: what to say. With no actions it is the full spoken reply. With actions leave it \"\" (each action speaks for itself).\n"
            + "live: true only when the answer needs fresh facts from the internet (news, scores, prices, today's events, opening hours).\n"
            + "Allowed actions and fields:\n"
            + "OPEN_APP target=exact app name from the list. SEARCH target=app name or \"\" for web, message=what to search. "
            + "SEND_MESSAGE target=person name, message=text to send (WhatsApp). WHATSAPP_CALL target=person, message=voice|video. "
            + "CALL target=person name or phone number. CALL_BACK (call the last caller). CALL_CONTROL target=answer|reject|end. MISSED_CALLS. "
            + "ASK_APP target=claude|chatgpt|gemini|perplexity|copilot, message=question (only when the user names that app). "
            + "SCROLL target=down|up. PLAY_ITEM target=1-6 (which video/result on screen). MEDIA target=play|pause|next|previous. "
            + "TAP target=visible button or text. TYPE message=text. BACK, HOME, RECENTS, NOTIFICATIONS (open the shade), READ_SCREEN, SCREENSHOT, LOCK. "
            + "TIME, DATE, BATTERY, BRIEFING (summary of the day), NEXT_ALARM, CALENDAR target=today|tomorrow. "
            + "VOLUME target=up|down|mute|unmute|max. TORCH target=on|off. DND target=on|off (Do Not Disturb). RINGER target=silent|vibrate|normal. "
            + "SETTINGS target=wifi|bluetooth|mobile data|hotspot|location|display|sound|battery|apps|storage|security|date and time|airplane mode. "
            + "ALARM target=HH:MM 24-hour. TIMER target=seconds. REMINDER target=HH:MM 24-hour or +seconds from now, message=what to remind. "
            + "CALCULATE message=maths using digits and + - * / ^ ( ) (for sums only). "
            + "READ_NOTIFICATIONS target=app name or \"\". NOTE message=text to save. READ_NOTES. CLEAR_NOTES. "
            + "REMEMBER message=a fact about the user to keep (never passwords, PINs, OTPs or bank details). FORGET target=words of the fact, or everything. READ_MEMORY. "
            + "NAVIGATE target=place. WEATHER target=city or \"\". PHOTO target=photo|selfie. SET_NAME target=what to call the user (message=city to set the weather city instead). "
            + "SPEECH_SPEED target=faster|slower|normal. CHANGE_VOICE target=next|default (Jarvis's speaking voice). HELP. STOP_LISTENING. CANCEL. REPEAT.\n"
            + "Rules:\n"
            + "- Pick the guess that makes most sense. Fix misheard app names to the closest installed app (cloud=Claude, what's up=WhatsApp, you tube=YouTube, insta=Instagram).\n"
            + "- Use the conversation so far: 'him', 'her', 'it', 'that', 'again', 'the same to Ravi' refer to earlier turns.\n"
            + "- Use remembered facts (for example 'call my wife' when a fact says the wife is Priya means CALL Priya).\n"
            + "- If an app is open on screen, 'search X', 'scroll', 'play the first one', 'tap X' are about that app.\n"
            + "- Never choose banking, UPI, payment or password apps. Never put a password or code in any field.\n"
            + "- Keep message text in the user's own words and language. Times: 'morning 7' = 07:00, 'evening 6' = 18:00, 'night 9' = 21:00, 'tomorrow 6 am' = 06:00.\n"
            + "- Answers are spoken: 1 to 3 short sentences, plain text, no markdown, lists, emojis or links. Reply in the user's language (Telugu or Hindi in Latin letters if they used it).\n"
            + "- Never invent an action, never claim you did something, never make up live facts. If the request is not possible, say so briefly in answer.\n"
            + "- If it is truly unclear, ask one short question in answer with no actions.\n"
            + "The user is called %s; address them that way.";

    /** One reading of what was said. */
    static final class Brain {
        final List<String[]> actions = new java.util.ArrayList<>(); // {action, target, message}
        String answer = "";
        boolean live;
    }

    /** Recent turns {time, heard, reply json}, so follow-ups like "send it to him too" work. Memory only; older than 10 minutes is dropped. */
    private static final Deque<Object[]> brainHistory = new ArrayDeque<>();
    private static final long HISTORY_MS = 10 * 60 * 1000;

    static void forgetConversation() { synchronized (brainHistory) { brainHistory.clear(); } }

    /** Adds what Jarvis itself said (for example an action's reply) to the last turn, so follow-ups have context. */
    static void noteSpoken(String said) {
        if (said == null || said.isEmpty()) return;
        synchronized (brainHistory) {
            Object[] last = brainHistory.peekLast();
            if (last != null && System.currentTimeMillis() - (Long) last[0] < 60_000) last[3] = said.length() > 300 ? said.substring(0, 300) : said;
        }
    }

    /**
     * The brain: asks Gemini what the words mean. Sends only the recognized words, the time, the app on screen,
     * installed app names and the user's remembered facts (never audio, contacts or screen text).
     * Blocking; call off the main thread. Returns null if Gemini can't be reached.
     */
    static Brain understand(Context c, List<String> guesses, List<String> appNames, String onScreen, String userName, String city, String facts) {
        String key = getKey(c);
        if (key.isEmpty() || !online(c)) return null;
        StringBuilder q = new StringBuilder("Heard: ");
        int n = 0;
        for (String g : guesses) { if (g == null || g.trim().isEmpty()) continue; if (n++ > 0) q.append(" | "); q.append(g.trim()); if (n >= 3) break; }
        q.append("\nNow: ").append(new java.text.SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", java.util.Locale.ENGLISH).format(new java.util.Date()));
        if (city != null && !city.isEmpty()) q.append(", user's city ").append(city);
        if (onScreen != null && !onScreen.isEmpty()) q.append("\nApp open on screen now: ").append(onScreen);
        if (facts != null && !facts.isEmpty()) q.append("\nFacts the user asked you to remember:\n").append(facts);
        q.append("\nInstalled apps: ");
        int a = 0;
        for (String name : appNames) { if (a++ > 0) q.append(", "); q.append(name); if (a >= 250) break; }
        String sys = String.format(java.util.Locale.ROOT, BRAIN, userName == null || userName.isEmpty() ? "Boss" : userName);
        for (String model : modelOrder()) {
            try {
                JSONArray contents = new JSONArray();
                long now = System.currentTimeMillis();
                synchronized (brainHistory) {
                    while (!brainHistory.isEmpty() && now - (Long) brainHistory.peekFirst()[0] > HISTORY_MS) brainHistory.removeFirst();
                    for (Object[] t : brainHistory) {
                        contents.put(turn("user", "Heard: " + t[1]));
                        String said = (String) t[3];
                        contents.put(turn("model", (String) t[2] + (said == null || said.isEmpty() ? "" : "\n(Jarvis then said: " + said + ")")));
                    }
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
                if ((code == 400 || code == 401 || code == 403) && (resp.contains("API_KEY") || resp.contains("API key"))) return null;
                if (code != 200) continue;
                String text = replyText(new JSONObject(resp));
                Brain r = parseBrain(text);
                if (r == null) continue;
                worked(model);
                String first = guesses.isEmpty() || guesses.get(0) == null ? "" : guesses.get(0);
                synchronized (brainHistory) {
                    brainHistory.addLast(new Object[]{now, first, text.trim(), ""});
                    while (brainHistory.size() > 5) brainHistory.removeFirst();
                }
                return r;
            } catch (Exception ignored) { }
        }
        return null;
    }

    /**
     * Reads the brain's JSON (tolerates ```json fences or extra words, and the older single-action shape).
     * Returns null if it isn't usable.
     */
    static Brain parseBrain(String text) {
        if (text == null) return null;
        int s = text.indexOf('{'), e = text.lastIndexOf('}');
        if (s < 0 || e <= s) return null;
        try {
            JSONObject o = new JSONObject(text.substring(s, e + 1));
            Brain b = new Brain();
            b.answer = clean(o.optString("answer", ""));
            b.live = o.optBoolean("live", false);
            JSONArray acts = o.optJSONArray("actions");
            if (acts == null && o.has("action")) acts = new JSONArray().put(o);
            if (acts != null) {
                for (int i = 0; i < acts.length() && b.actions.size() < 3; i++) {
                    JSONObject x = acts.optJSONObject(i);
                    if (x == null) continue;
                    String act = x.optString("action", "").trim().toUpperCase(java.util.Locale.ROOT);
                    if (act.isEmpty()) continue;
                    if (act.equals("ANSWER")) { if (b.answer.isEmpty()) b.answer = clean(x.optString("answer", x.optString("message", ""))); continue; }
                    b.actions.add(new String[]{act, x.optString("target", "").trim(), x.optString("message", "").trim()});
                }
            }
            if (b.actions.isEmpty() && b.answer.isEmpty()) return null;
            return b;
        } catch (Exception ex) { return null; }
    }

    /**
     * A spoken answer that uses Google Search for fresh facts (news, scores, prices). Uses the same free key;
     * returns null when search isn't available on the key or model, so the caller keeps the normal answer.
     */
    static String askLive(Context c, String question, String userName, String city) {
        String key = getKey(c);
        if (key.isEmpty() || !online(c)) return null;
        String sys = SYSTEM.replace("Boss", userName == null || userName.isEmpty() ? "Boss" : userName)
                + " Today is " + new java.text.SimpleDateFormat("EEEE d MMMM yyyy", java.util.Locale.ENGLISH).format(new java.util.Date())
                + (city == null || city.isEmpty() ? "" : "; the user is in " + city) + ". Use Google Search for current facts and give the key fact first.";
        for (String model : modelOrder()) {
            try {
                JSONObject body = new JSONObject()
                        .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", sys))))
                        .put("contents", new JSONArray().put(turn("user", question)))
                        .put("tools", new JSONArray().put(new JSONObject().put("google_search", new JSONObject())))
                        .put("generationConfig", new JSONObject().put("maxOutputTokens", 1024));
                HttpURLConnection h = (HttpURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent").openConnection();
                h.setConnectTimeout(8000); h.setReadTimeout(20000);
                h.setRequestMethod("POST"); h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/json");
                h.setRequestProperty("x-goog-api-key", key);
                try (OutputStream o = h.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                int code = h.getResponseCode();
                String resp = read(code >= 400 ? h.getErrorStream() : h.getInputStream());
                if (code != 200) continue;
                String text = clean(replyText(new JSONObject(resp)));
                if (!text.isEmpty()) return text;
            } catch (Exception ignored) { }
        }
        return null;
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
