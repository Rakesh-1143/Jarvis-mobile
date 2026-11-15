package com.growthos.jarvis;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.role.RoleManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.service.voice.VoiceInteractionService;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Native setup and diagnostic screen. Works without internet and without the website.
 * It only reads status through public Android APIs and opens Android settings pages;
 * it never changes a system setting by itself.
 */
public final class AssistantSetupActivity extends Activity {
    private static final int MIC_REQUEST = 71, CONTACTS_REQUEST = 72;
    private static final int TEXT = 0xffe6f7ff, MUTED = 0xff8aa4b4, GOOD = 0xff4ade80, BAD = 0xfffbbf24, BG = 0xff02070d, CARD = 0xff0b1722;

    private LinearLayout list;
    private TextView liveView;
    private String liveText;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable liveTick = this::updateLive;
    private TextToSpeech tts;
    private String ttsStatus = "Checking…";
    private final StringBuilder report = new StringBuilder();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        list.setPadding(p, p, p, p);
        scroll.addView(list);
        setContentView(scroll);
        try { tts = new TextToSpeech(this, status -> runOnUiThread(() -> { ttsStatus = describeTts(status); render(); })); }
        catch (Exception e) { ttsStatus = "Not available"; }
    }

    @Override protected void onResume() { super.onResume(); render(); }

    @Override protected void onDestroy() { if (tts != null) tts.shutdown(); super.onDestroy(); }

    /** Never close the screen on an error: show it instead, so it can be copied and reported. */
    private void render() {
        try { renderChecks(); }
        catch (Throwable t) {
            note("Error while checking: " + t);
            report.append("\nERROR: ").append(t).append('\n');
            button("Copy report", v -> copyReport());
        }
    }

    private void renderChecks() {
        list.removeAllViews();
        report.setLength(0);
        title("Jarvis setup");
        note("Status of this phone. Nothing here listens to the microphone or changes settings by itself.");

        boolean isDefault = JarvisVoiceInteractionService.isDefaultAssistant(this);
        boolean roleHeld = false;
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager rm = getSystemService(RoleManager.class);
            try { roleHeld = rm != null && rm.isRoleHeld(RoleManager.ROLE_ASSISTANT); } catch (Exception ignored) { }
        }
        SharedPreferences diag = getSharedPreferences(JarvisVoiceInteractionService.PREFS, MODE_PRIVATE);
        long readyAt = diag.getLong(JarvisVoiceInteractionService.KEY_READY_AT, 0L);

        section("Phone");
        row("Android version", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")", null);
        row("Device model", Build.MANUFACTURER + " " + Build.MODEL, null);
        row("KALKI app version", appVersion(), null);

        section("Assistant");
        row("Default digital assistant", (isDefault || roleHeld) ? "Yes, KALKI is selected" : "No", isDefault || roleHeld);
        row("Assistant service", assistantServiceDeclared() ? (readyAt > 0 ? "Registered · last started " + time(readyAt) : "Registered · not started yet") : "Missing", assistantServiceDeclared());
        row("Hardware hotword (DSP)", "Not available to normal apps (Android keeps this API for system apps). Jarvis will use a software wake word.", null);
        button("Choose KALKI as default assistant", v -> openAssistantSettings());
        note("Android shows its own page. Tap \"Digital assistant app\" (or \"Default digital assistant app\") and pick KALKI. You can change it back any time.");

        section("Hey Jarvis");
        SharedPreferences sp = WakeWordService.prefs(this);
        boolean on = WakeWordService.isEnabled(this);
        row("Always-on listening", on ? (WakeWordService.running ? WakeWordService.state : "On, but not running") : "Off", on ? (Boolean) WakeWordService.running : null);
        button(on ? "Turn off Hey Jarvis" : "Turn on Hey Jarvis", v -> toggleWake());
        note("When on, the phone checks for \"Hey Jarvis\" on the phone itself. That audio is never saved or sent. After you say it, your command goes to the phone's speech recognizer (Google app).");
        long trainedAt = sp.getLong(WakeWordService.KEY_TRAINED_AT, 0);
        boolean trained = trainedAt > 0 && TemplateMatcher.file(this).exists();
        row("Your voice", trained ? "Trained for \"" + sp.getString(WakeWordService.KEY_PHRASE, "Hey Jarvis") + "\" on " + time(trainedAt) : "Not trained yet (recommended: Jarvis then wakes for your voice and accent)", trained);
        button(trained ? "Train my voice again" : "Train my voice", v -> startActivity(new Intent(this, VoiceTrainingActivity.class)));
        if (trained) button("Delete my voice training", v -> {
            TemplateMatcher.file(this).delete();
            sp.edit().remove(WakeWordService.KEY_TRAINED_AT).remove(WakeWordService.KEY_PHRASE).apply();
            if (WakeWordService.running) { stopService(new Intent(this, WakeWordService.class)); WakeWordService.start(this, null); }
            render();
        });
        float threshold = sp.getFloat(WakeWordService.KEY_THRESHOLD, 0.5f);
        row("Sensitivity", threshold <= 0.36f ? "High (wakes more easily)" : threshold >= 0.64f ? "Low (fewer false wakes)" : "Normal", null);
        button("Change sensitivity", v -> {
            float t = sp.getFloat(WakeWordService.KEY_THRESHOLD, 0.5f);
            float next = t <= 0.36f ? 0.65f : t >= 0.64f ? 0.5f : 0.35f;
            sp.edit().putFloat(WakeWordService.KEY_THRESHOLD, next).apply();
            if (WakeWordService.running) WakeWordService.start(this, WakeWordService.ACTION_RESUME);
            render();
        });
        if (WakeWordService.running) {
            liveView = text("", 15, TEXT);
            liveView.setPadding(dp(12), dp(10), dp(12), dp(10));
            liveView.setBackgroundColor(CARD);
            list.addView(liveView);
            note("Live check: say your wake phrase now. Mic level should jump when you talk. Built-in model above " + String.format(Locale.US, "%.2f", threshold) + ", or your voice match at 1.00, wakes Jarvis.");
            updateLive();
        } else liveView = null;
        long lastWake = sp.getLong(WakeWordService.KEY_LAST_WAKE, 0);
        row("Last \"Hey Jarvis\" heard", lastWake == 0 ? "Not yet" : time(lastWake) + String.format(Locale.US, " · score %.2f", sp.getFloat(WakeWordService.KEY_LAST_SCORE, 0)), null);
        String lastCmd = sp.getString(WakeWordService.KEY_LAST_COMMAND, null);
        row("Last command heard", lastCmd == null ? "None yet" : lastCmd.isEmpty() ? "(nothing understood)" : lastCmd, null);
        String log = sp.getString(WakeWordService.KEY_COMMAND_LOG, null);
        if (log != null && !log.isEmpty()) row("Recent commands", log, null);
        float cpu = sp.getFloat(WakeWordService.KEY_CPU, 0);
        if (cpu > 0) row("Wake word work per 80 ms of audio", String.format(Locale.US, "%.1f ms (%.0f%% of one CPU core)", cpu, cpu / 80f * 100f), null);
        long started = sp.getLong(WakeWordService.KEY_STARTED_AT, 0);
        int startBattery = sp.getInt(WakeWordService.KEY_START_BATTERY, -1);
        if (WakeWordService.running && started > 0 && startBattery >= 0) {
            double hours = (System.currentTimeMillis() - started) / 3600000.0;
            row("Battery since listening started", String.format(Locale.US, "%d%% → %d%% in %.1f h (all phone use, not only Jarvis)", startBattery, batteryNow(), hours), null);
        }
        String err = sp.getString(WakeWordService.KEY_ERROR, null);
        if (err != null) row("Last problem", err, Boolean.FALSE);

        section("Microphone and background");
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        row("Microphone permission", mic ? "Allowed" : "Not allowed", mic);
        if (!mic) button("Allow microphone", v -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST));
        boolean contacts = Contacts.allowed(this);
        row("Contacts permission (for \"send message to …\")", contacts ? "Allowed · names are read on the phone only" : "Not allowed", contacts);
        if (!contacts) button("Allow contacts", v -> requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, CONTACTS_REQUEST));
        boolean calls = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED;
        row("Phone calls (for \"call …\", \"answer\", \"end call\")", calls ? "Allowed · Jarvis asks before every call" : "Not allowed · Jarvis opens the dialer instead", calls);
        if (!calls) button("Allow phone calls", v -> requestPermissions(new String[]{Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS}, CONTACTS_REQUEST + 1));
        boolean sender = WhatsAppSender.isOn(this);
        row("Jarvis control (accessibility)", sender ? "On · tap, type, scroll in apps; never money apps or password boxes; WhatsApp sends only after you say yes" : "Off · no tapping, typing or scrolling by voice", sender);
        button(sender ? "Turn off Jarvis control" : "Turn on Jarvis control", v -> {
            Toast.makeText(this, "Tap \"Jarvis control\" (it may be under Installed apps or Downloaded apps)", Toast.LENGTH_LONG).show();
            try { startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) { }
        });
        row("Background listening service", WakeWordService.running ? "Running" : "Not running", null);
        row("Open apps from background", JarvisVoiceInteractionService.instance != null ? "Ready (through the assistant)" : "Needs KALKI as default assistant", JarvisVoiceInteractionService.instance != null);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean unrestricted = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        boolean restricted = false;
        if (Build.VERSION.SDK_INT >= 28) {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            restricted = am != null && am.isBackgroundRestricted();
        }
        row("Battery setting", restricted ? "Restricted (Jarvis will be stopped)" : unrestricted ? "Unrestricted" : "Optimized (Android may stop Jarvis)", restricted ? Boolean.FALSE : unrestricted);
        button("Open KALKI battery settings", v -> openAppDetails());
        note("Tap Battery, then choose Unrestricted. Motorola may otherwise stop Jarvis in the background.");

        section("Voice");
        boolean sr = SpeechRecognizer.isRecognitionAvailable(this);
        String srText = sr ? "Available" : "Not available";
        if (Build.VERSION.SDK_INT >= 31) srText += SpeechRecognizer.isOnDeviceRecognitionAvailable(this) ? " · offline engine available" : " · no offline engine";
        ComponentName delegate = JarvisRecognitionService.findDelegate(this);
        if (delegate != null) srText += " · uses " + delegate.getPackageName();
        row("Speech recognition", srText, sr);
        row("Text to speech", ttsStatus, ttsStatus.startsWith("Ready") ? Boolean.TRUE : ttsStatus.startsWith("Checking") ? null : Boolean.FALSE);

        section("Network");
        boolean online = isOnline();
        row("Internet", online ? "Connected" : "Offline", online);

        section("Self-test");
        note("Checks every command on this phone by itself. If Jarvis control is on, it also opens YouTube, searches, scrolls and comes back here (about 15 seconds). It never calls anyone, sets alarms or sends messages.");
        button("Run self-test", v -> {
            Toast.makeText(this, "Self-test running… please don't touch the phone for 15 seconds", Toast.LENGTH_LONG).show();
            new SelfTest(this).run(this::render);
        });
        String st = SelfTest.last(this);
        if (st != null) row("Last self-test", st.trim(), st.contains("\nFAIL ") || st.startsWith("FAIL") ? Boolean.FALSE : Boolean.TRUE);

        section("Share results");
        button("Copy report", v -> copyReport());
        note("Paste the report in the KALKI project chat so Claude can see your phone's real results.");
        button("Refresh", v -> render());
    }

    private void updateLive() {
        ui.removeCallbacks(liveTick);
        if (liveView == null || !WakeWordService.running) return;
        String s = String.format(Locale.US, "Mic level: %.0f dB%s\nBuilt-in model: now %.2f · best in 10 s %.2f\nYour voice match: now %.2f · best in 10 s %.2f (1.00 wakes)",
                WakeWordService.liveDb, WakeWordService.liveDb < -85 ? " (silent?)" : "", WakeWordService.liveScore, WakeWordService.bestScore, WakeWordService.liveMatch, WakeWordService.bestMatch);
        if (!WakeWordService.engineInfo.isEmpty()) s += "\nEngine: " + WakeWordService.engineInfo;
        liveView.setText(s);
        liveText = s.replace("\n", " · ");
        ui.postDelayed(liveTick, 500);
    }

    @Override protected void onPause() { ui.removeCallbacks(liveTick); super.onPause(); }

    private void toggleWake() {
        boolean on = !WakeWordService.isEnabled(this);
        if (on && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
            return;
        }
        WakeWordService.setEnabled(this, on);
        list.postDelayed(this::render, 800);
    }

    private int batteryNow() {
        android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
        return bm == null ? -1 : bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }

    private boolean assistantServiceDeclared() {
        Intent i = new Intent(VoiceInteractionService.SERVICE_INTERFACE).setPackage(getPackageName());
        return !getPackageManager().queryIntentServices(i, 0).isEmpty();
    }

    private void openAssistantSettings() {
        for (String action : new String[]{Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS}) {
            try { startActivity(new Intent(action)); return; } catch (Exception ignored) { }
        }
        Toast.makeText(this, "Could not open settings", Toast.LENGTH_SHORT).show();
    }

    private void openAppDetails() {
        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
        catch (Exception e) { Toast.makeText(this, "Could not open settings", Toast.LENGTH_SHORT).show(); }
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null || cm.getActiveNetwork() == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) { return false; }
    }

    private String describeTts(int status) {
        try { return describeTtsUnsafe(status); } catch (Exception e) { return "Not available (" + e.getClass().getSimpleName() + ")"; }
    }

    private String describeTtsUnsafe(int status) {
        if (status != TextToSpeech.SUCCESS || tts == null) return "Not available";
        String engine = tts.getDefaultEngine();
        int en = tts.isLanguageAvailable(new Locale("en", "IN"));
        boolean ok = en >= TextToSpeech.LANG_AVAILABLE;
        return (ok ? "Ready · English (India) voice" : "Ready · English (India) voice missing") + (engine == null ? "" : " · " + engine);
    }

    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    private void copyReport() {
        ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cb == null) return;
        String text = report.toString() + (liveView != null && liveText != null ? "\n[Live]\n" + liveText + "\n" : "");
        cb.setPrimaryClip(ClipData.newPlainText("Jarvis report", text));
        Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        render();
    }

    private static String time(long ms) { return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(ms)); }

    // ---- simple views ----
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void title(String s) {
        TextView t = text(s, 24, TEXT); t.setTypeface(Typeface.DEFAULT_BOLD); list.addView(t);
        report.append(s).append(" report\n");
    }

    private void section(String s) {
        TextView t = text(s, 15, 0xff00e5ff); t.setTypeface(Typeface.DEFAULT_BOLD); t.setPadding(0, dp(20), 0, dp(6)); list.addView(t);
        report.append("\n[").append(s).append("]\n");
    }

    private void note(String s) { TextView t = text(s, 13, MUTED); t.setPadding(0, dp(4), 0, dp(4)); list.addView(t); }

    /** ok: true = green, false = amber, null = neutral. */
    private void row(String label, String value, Boolean ok) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(CARD);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        card.addView(text(label, 13, MUTED));
        card.addView(text((ok == null ? "" : ok ? "✓ " : "! ") + value, 16, ok == null ? TEXT : ok ? GOOD : BAD));
        list.addView(card, lp);
        report.append(label).append(": ").append(value).append('\n');
    }

    private void button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(l);
        list.addView(b);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        return t;
    }
}
