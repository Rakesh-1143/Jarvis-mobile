package com.growthos.jarvis;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Invisible step between Jarvis and the app it opens. If the phone is locked it asks Android
 * to show the unlock screen first; Jarvis never bypasses the lock. Only opens app launch intents.
 */
public final class LaunchActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent target = getIntent().getParcelableExtra(AppOpener.EXTRA_TARGET);
        if (!isAppLaunch(target)) { finish(); return; }
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (Build.VERSION.SDK_INT >= 27 && km != null && km.isKeyguardLocked()) {
            setShowWhenLocked(true);
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override public void onDismissSucceeded() { go(target); }
                @Override public void onDismissCancelled() { finish(); }
                @Override public void onDismissError() { finish(); }
            });
        } else go(target);
    }

    /** Only a normal "open this app" intent is allowed. */
    static boolean isAppLaunch(Intent i) {
        return i != null && Intent.ACTION_MAIN.equals(i.getAction()) && (i.getComponent() != null || i.getPackage() != null) && i.getData() == null;
    }

    private void go(Intent target) {
        try { startActivity(target); }
        catch (Exception e) { Toast.makeText(this, "Could not open the app", Toast.LENGTH_SHORT).show(); }
        finish();
    }

    @Override protected void onDestroy() { JarvisSessionService.hideCurrent(); super.onDestroy(); }
}
