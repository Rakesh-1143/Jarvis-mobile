package com.growthos.jarvis;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;
import java.lang.ref.WeakReference;

/** Assistant session: opens apps for Jarvis, and handles the assist gesture (long-press Home / corner swipe). */
public final class JarvisSessionService extends VoiceInteractionSessionService {
    private static WeakReference<Session> current = new WeakReference<>(null);

    static void hideCurrent() { Session s = current.get(); if (s != null) s.hide(); }

    @Override public VoiceInteractionSession onNewSession(Bundle args) { return new Session(this); }

    static final class Session extends VoiceInteractionSession {
        private final Handler handler = new Handler(Looper.getMainLooper());
        Session(Context context) { super(context); }

        @Override public void onShow(Bundle args, int showFlags) {
            super.onShow(args, showFlags);
            current = new WeakReference<>(this);
            handler.removeCallbacksAndMessages(null);
            Intent target = args == null ? null : args.getParcelable(AppOpener.EXTRA_TARGET);
            if (target != null) {
                Intent i = new Intent(getContext(), LaunchActivity.class).putExtra(AppOpener.EXTRA_TARGET, target);
                try { if (Build.VERSION.SDK_INT >= 29) startAssistantActivity(i); else getContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception e) { hide(); return; }
                handler.postDelayed(this::hide, 60000L); // safety net; LaunchActivity hides it when done
                return;
            }
            // Assist gesture: "Yes Boss?" and one command, even if the wake word is off.
            WakeWordService.start(getContext(), WakeWordService.ACTION_LISTEN_NOW);
            hide();
        }

        @Override public void onHide() { handler.removeCallbacksAndMessages(null); super.onHide(); }
    }
}
