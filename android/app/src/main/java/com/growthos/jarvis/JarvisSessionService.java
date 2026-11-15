package com.growthos.jarvis;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/** Runs when the user long-presses Home (or uses the assist gesture) with KALKI as default assistant. */
public final class JarvisSessionService extends VoiceInteractionSessionService {
    @Override public VoiceInteractionSession onNewSession(Bundle args) { return new Session(this); }

    static final class Session extends VoiceInteractionSession {
        Session(Context context) { super(context); }

        @Override public void onShow(Bundle args, int showFlags) {
            super.onShow(args, showFlags);
            // Step 1: open KALKI. Later steps replace this with "Yes Boss?" and listening.
            Intent i = new Intent(getContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { if (android.os.Build.VERSION.SDK_INT >= 29) startAssistantActivity(i); else getContext().startActivity(i); }
            catch (Exception e) { try { getContext().startActivity(i); } catch (Exception ignored) { } }
            hide();
        }
    }
}
