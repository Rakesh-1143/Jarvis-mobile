package com.growthos.jarvis;

import android.content.ComponentName;
import android.content.Context;
import android.service.voice.VoiceInteractionService;

/**
 * Exists so the user can choose KALKI as Android's "Digital assistant app".
 * Android keeps this service bound while KALKI is the default assistant.
 * Step 1 only records when it started; it does not open the microphone.
 *
 * Hardware (DSP) hotwords: AlwaysOnHotwordDetector is not part of the public Android SDK
 * (it is a system API), so a normal app cannot use it. Jarvis uses a software wake word instead.
 */
public final class JarvisVoiceInteractionService extends VoiceInteractionService {
    static final String PREFS = "jarvis_diagnostics";
    static final String KEY_READY_AT = "assistant_ready_at";

    static boolean isDefaultAssistant(Context c) {
        try { return isActiveService(c, new ComponentName(c, JarvisVoiceInteractionService.class)); }
        catch (Exception e) { return false; }
    }

    @Override public void onReady() {
        super.onReady();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(KEY_READY_AT, System.currentTimeMillis()).apply();
    }
}
