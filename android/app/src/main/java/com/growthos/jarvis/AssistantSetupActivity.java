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
    private static final int MIC_REQUEST = 71;
    private static final int TEXT = 0xffe6f7ff, MUTED = 0xff8aa4b4, GOOD = 0xff4ade80, BAD = 0xfffbbf24, BG = 0xff02070d, CARD = 0xff0b1722;

    private LinearLayout list;
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
        tts = new TextToSpeech(this, status -> runOnUiThread(() -> { ttsStatus = describeTts(status); render(); }));
    }

    @Override protected void onResume() { super.onResume(); render(); }

    @Override protected void onDestroy() { if (tts != null) tts.shutdown(); super.onDestroy(); }

    private void render() {
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
        String hotword = diag.getString(JarvisVoiceInteractionService.KEY_HOTWORD, null);
        row("Hardware hotword (DSP)", hotword != null ? hotword : "Unknown until KALKI is the default assistant", null);
        button("Choose KALKI as default assistant", v -> openAssistantSettings());
        note("Android shows its own page. Tap \"Digital assistant app\" (or \"Default digital assistant app\") and pick KALKI. You can change it back any time.");

        section("Microphone and background");
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        row("Microphone permission", mic ? "Allowed" : "Not allowed", mic);
        if (!mic) button("Allow microphone", v -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST));
        row("Background listening service", WakeWordService.running ? "Running" : "Not running", null);
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

        section("Share results");
        button("Copy report", v -> copyReport());
        note("Paste the report in the KALKI project chat so Claude can see your phone's real results.");
        button("Refresh", v -> render());
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
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private String describeTts(int status) {
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
        cb.setPrimaryClip(ClipData.newPlainText("Jarvis report", report.toString()));
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
