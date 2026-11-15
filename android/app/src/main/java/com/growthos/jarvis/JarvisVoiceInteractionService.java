package com.growthos.jarvis;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.service.voice.AlwaysOnHotwordDetector;
import android.service.voice.VoiceInteractionService;
import java.util.Locale;

/**
 * Exists so the user can choose KALKI as Android's "Digital assistant app".
 * Android keeps this service bound while KALKI is the default assistant.
 * Step 1 only records diagnostics; it does not open the microphone.
 */
public final class JarvisVoiceInteractionService extends VoiceInteractionService {
    static final String PREFS = "jarvis_diagnostics";
    static final String KEY_READY_AT = "assistant_ready_at";
    static final String KEY_HOTWORD = "hotword_probe";

    private AlwaysOnHotwordDetector detector;

    static boolean isDefaultAssistant(Context c) {
        try { return isActiveService(c, new ComponentName(c, JarvisVoiceInteractionService.class)); }
        catch (Exception e) { return false; }
    }

    @Override public void onReady() {
        super.onReady();
        prefs().edit().putLong(KEY_READY_AT, System.currentTimeMillis()).apply();
        probeHotwordHardware();
    }

    /**
     * Asks Android whether a hardware (DSP) hotword is available to this app.
     * Only records the answer; never starts hotword recognition.
     */
    private void probeHotwordHardware() {
        try {
            detector = createAlwaysOnHotwordDetector("Hey Jarvis", Locale.US, new AlwaysOnHotwordDetector.Callback() {
                @Override public void onAvailabilityChanged(int status) { save(describe(status)); release(); }
                @Override public void onDetected(AlwaysOnHotwordDetector.EventPayload eventPayload) { }
                @Override public void onError() { save("Error while checking"); release(); }
                @Override public void onRecognitionPaused() { }
                @Override public void onRecognitionResumed() { }
            });
        } catch (Throwable t) {
            save("Not available to this app (" + t.getClass().getSimpleName() + ")");
        }
    }

    // Raw values of AlwaysOnHotwordDetector.STATE_* (some are not public on every API level).
    static String describe(int status) {
        switch (status) {
            case -2: return "No: hardware hotword not available on this phone";
            case -1: return "No: hotword not supported for this app";
            case 1: return "Hardware present, but no keyphrase enrolled (enrolling needs a system app)";
            case 2: return "Yes: keyphrase enrolled";
            default: return "Unknown (state " + status + ")";
        }
    }

    private void save(String value) { prefs().edit().putString(KEY_HOTWORD, value).apply(); }

    private void release() {
        AlwaysOnHotwordDetector d = detector; detector = null;
        if (d != null && Build.VERSION.SDK_INT >= 31) { try { d.destroy(); } catch (Throwable ignored) { } }
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    @Override public void onShutdown() { release(); super.onShutdown(); }
}
