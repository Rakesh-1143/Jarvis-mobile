package com.growthos.jarvis;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * Invisible screen used by voice unlock: turns the screen on and asks Android to show its own PIN pad.
 * It does not unlock anything itself; Android checks the PIN that PinTyper types.
 */
public final class UnlockActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km == null || !km.isKeyguardLocked() || Build.VERSION.SDK_INT < 27) { finish(); return; }
        km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override public void onDismissSucceeded() { finish(); }
            @Override public void onDismissCancelled() { finish(); }
            @Override public void onDismissError() { finish(); }
        });
        handler.postDelayed(this::finish, 20000); // never stay around
    }

    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
