package com.growthos.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.speech.SpeechRecognizer;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * One-tap check of everything Jarvis needs, run on the phone itself. Quick checks never act
 * (no calls, alarms or torch); the live YouTube test opens YouTube, searches, scrolls and comes back.
 * Results are words only and stay on the phone until the user copies the report.
 */
final class SelfTest {
    static final String PREFS = "jarvis_selftest", KEY_RESULT = "result";
    private final Activity a;
    private final StringBuilder out = new StringBuilder();
    private int pass, fail;

    SelfTest(Activity a) { this.a = a; }

    static String last(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RESULT, null); }

    private void check(String name, boolean ok, String detail) {
        if (ok) pass++; else fail++;
        out.append(ok ? "PASS " : "FAIL ").append(name).append(detail == null || detail.isEmpty() ? "" : " · " + detail).append('\n');
    }

    private void info(String name, String detail) { out.append("INFO ").append(name).append(" · ").append(detail).append('\n'); }

    private boolean perm(String p) { return a.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    private boolean resolves(Intent i) { try { return a.getPackageManager().resolveActivity(i, 0) != null; } catch (Exception e) { return false; } }

    /** Runs quick checks, then (if Jarvis control is on) the live YouTube test. done runs on the main thread. */
    void run(Runnable done) {
        out.setLength(0); pass = 0; fail = 0;
        out.append("Self-test ").append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date())).append('\n');
        try { quick(); } catch (Throwable t) { check("quick checks crashed", false, String.valueOf(t)); }
        if (!WhatsAppSender.running()) { info("live YouTube test", "skipped: Jarvis control is off"); finish(done); return; }
        live(done);
    }

    private void quick() {
        // Assistant and listening
        check("KALKI is default assistant", JarvisVoiceInteractionService.isDefaultAssistant(a), null);
        check("Hey Jarvis service running", WakeWordService.running, WakeWordService.state);
        check("Microphone permission", perm(Manifest.permission.RECORD_AUDIO), null);
        check("Speech recognition available", SpeechRecognizer.isRecognitionAvailable(a), String.valueOf(JarvisRecognitionService.findDelegate(a)));
        check("Voice trained", TemplateMatcher.file(a).exists(), null);
        // Permissions for commands
        check("Contacts permission", perm(Manifest.permission.READ_CONTACTS), Contacts.allowed(a) ? Contacts.withNumbers(a).size() + " contacts with numbers" : "");
        check("Call permission", perm(Manifest.permission.CALL_PHONE), "without it Jarvis opens the dialer");
        check("Answer/end call permission", perm(Manifest.permission.ANSWER_PHONE_CALLS), null);
        check("Jarvis control switched on", WhatsAppSender.isOn(a), null);
        check("Jarvis control connected", WhatsAppSender.running(), null);
        // Apps by name
        List<AppMatcher.App> apps = WakeWordService.launcherApps(a);
        info("apps with icons", String.valueOf(apps.size()));
        for (String name : new String[]{"whatsapp", "youtube", "claude", "instagram", "chrome", "phone", "camera", "settings", "clock"}) {
            AppMatcher.App m = AppMatcher.match(name, apps);
            check("open \"" + name + "\"", m != null, m == null ? "not found" : m.label + " (" + m.packageName + ")");
        }
        // Command parsing on this phone
        String[][] samples = {
                {"open whatsapp", "OPEN_APP"}, {"send message to ravi saying hi", "SEND_MESSAGE"}, {"search latest movies on youtube", "SEARCH"},
                {"scroll down", "SCROLL"}, {"play the second video", "PLAY_ITEM"}, {"pause", "MEDIA"}, {"call amma", "CALL"},
                {"what time is it", "TIME"}, {"battery", "BATTERY"}, {"volume up", "VOLUME"}, {"turn on torch", "TORCH"},
                {"set alarm for 6 am", "ALARM"}, {"timer 5 minutes", "TIMER"}, {"open wifi settings", "SETTINGS"},
                {"ask claude what is ai", "ASK_APP"}, {"tap search", "TAP"}, {"type hello", "TYPE"}, {"go home", "HOME"}, {"read the screen", "READ_SCREEN"},
                {"read my notifications", "READ_NOTIFICATIONS"}, {"whatsapp video call ravi", "WHATSAPP_CALL"}, {"remind me to drink water at 5 pm", "REMINDER"},
                {"take a note buy milk", "NOTE"}, {"read my notes", "READ_NOTES"}, {"navigate to charminar", "NAVIGATE"}, {"what's the weather", "WEATHER"},
                {"take a photo", "PHOTO"}, {"what can you do", "HELP"}, {"who is the prime minister of india", "UNKNOWN"}};
        int ok = 0; StringBuilder bad = new StringBuilder();
        for (String[] s : samples) { String got = VoiceCommand.parse(s[0]).type.name(); if (got.equals(s[1])) ok++; else bad.append(s[0]).append("->").append(got).append("; "); }
        check("understands " + samples.length + " sample commands", ok == samples.length, ok + "/" + samples.length + (bad.length() > 0 ? " " + bad : ""));
        // Can each action be opened on this phone?
        AppMatcher.App yt = AppMatcher.match("youtube", apps);
        check("YouTube search can open", yt != null && resolves(Searches.intent(a, yt.packageName, "test")), null);
        check("Google search can open", resolves(Searches.intent(a, null, "test")), null);
        check("Alarm can be set", resolves(PhoneActions.alarm(6, 0)), null);
        check("Timer can be set", resolves(PhoneActions.timer(60)), null);
        check("Calls can be placed", resolves(PhoneActions.call(a, "100")), PhoneActions.canCall(a) ? "direct call" : "dialer only");
        check("Wifi settings can open", resolves(PhoneActions.settings("wifi")), null);
        String wa = Contacts.whatsappPackage(a);
        check("WhatsApp chat link can open", wa != null && resolves(Contacts.whatsappChat(wa, "919999999999", "hi")), wa);
        AppMatcher.App claude = AppMatcher.match("claude", apps);
        check("Claude accepts a question", claude != null && resolves(PhoneActions.shareText(claude.packageName, "hi")), null);
        check("Gemini key available for questions", GeminiClient.hasKey(a), "save it in KALKI Settings, then open KALKI once");
        check("Notification reading on", JarvisNotifications.isOn(a), JarvisNotifications.connected() ? "connected" : "not connected");
        info("unlock greeting", a.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE).getBoolean(WakeWordService.KEY_GREET, true) ? "on" : "off");
        // Hardware
        boolean flash = false;
        try { CameraManager cm = (CameraManager) a.getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) if (Boolean.TRUE.equals(cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) flash = true;
        } catch (Exception ignored) { }
        check("Torch available", flash, null);
        check("Navigation can open", resolves(PhoneActions.navigate("Charminar")), null);
        check("Camera can open", resolves(PhoneActions.camera(false)), null);
        try { Notes.all(a); check("Notes storage works", true, Notes.all(a).size() + " notes"); } catch (Exception e) { check("Notes storage works", false, String.valueOf(e)); }
        info("battery", PhoneActions.battery(a));
        check("Money apps are blocked", WhatsAppSender.blocked("com.phonepe.app") && WhatsAppSender.blocked("net.one97.paytm") && WhatsAppSender.blocked("com.google.android.apps.nbu.paisa.user") && !WhatsAppSender.blocked("com.google.android.youtube"), null);
    }

    /** Opens YouTube search, counts videos, scrolls, then returns to Jarvis setup. */
    private void live(Runnable done) {
        Handler h = new Handler(Looper.getMainLooper());
        AppMatcher.App yt = AppMatcher.match("youtube", WakeWordService.launcherApps(a));
        if (yt == null) { info("live YouTube test", "skipped: YouTube not installed"); finish(done); return; }
        Intent search = Searches.intent(a, yt.packageName, "latest movies");
        try { a.startActivity(search.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { check("live: YouTube search opened", false, String.valueOf(e)); finish(done); return; }
        h.postDelayed(() -> {
            String fg = WhatsAppSender.foregroundApp();
            check("live: YouTube on screen after search", WhatsAppSender.YOUTUBE.equals(fg), "screen: " + WhatsAppSender.describeScreen());
            int n = WhatsAppSender.countVideos();
            check("live: videos found in results", n > 0, n + " videos");
            boolean scrolled = WhatsAppSender.scroll(true);
            check("live: scroll down", scrolled, null);
            h.postDelayed(() -> {
                check("live: scroll up", WhatsAppSender.scroll(false), null);
                String text = WhatsAppSender.readScreen();
                check("live: read screen", text != null && !text.isEmpty(), text == null ? "" : text.length() + " characters");
                h.postDelayed(() -> {
                    a.startActivity(new Intent(a, AssistantSetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK));
                    finish(done);
                }, 1500);
            }, 2000);
        }, 6000);
    }

    private void finish(Runnable done) {
        out.append("Total: ").append(pass).append(" passed, ").append(fail).append(" failed\n");
        SharedPreferences p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putString(KEY_RESULT, out.toString()).apply();
        done.run();
    }
}
