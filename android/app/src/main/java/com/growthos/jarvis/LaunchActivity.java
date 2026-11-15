package com.growthos.jarvis;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Invisible step between Jarvis and the app it opens. If the phone is locked it asks Android
 * to show the unlock screen first; Jarvis never bypasses the lock. Only opens app launch intents
 * and WhatsApp chat links (which only type the message; the user taps Send).
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

    /** Only a normal "open this app" intent, or a WhatsApp chat link, is allowed. */
    static boolean isAppLaunch(Intent i) {
        if (i == null) return false;
        if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null && i.getComponent() == null)
            return "https".equals(i.getData().getScheme()) && "wa.me".equals(i.getData().getHost())
                    && java.util.Arrays.asList(Contacts.WHATSAPP).contains(i.getPackage());
        return Intent.ACTION_MAIN.equals(i.getAction()) && (i.getComponent() != null || i.getPackage() != null) && i.getData() == null;
    }

    private void go(Intent target) {
        try { startActivity(target); }
        catch (Exception e) { Toast.makeText(this, "Could not open the app", Toast.LENGTH_SHORT).show(); }
        finish();
    }

    @Override protected void onDestroy() { JarvisSessionService.hideCurrent(); super.onDestroy(); }
}
