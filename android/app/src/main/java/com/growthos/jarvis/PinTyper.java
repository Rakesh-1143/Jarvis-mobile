package com.growthos.jarvis;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;

/**
 * Types a PIN into Android's own lock screen PIN pad, for voice unlock only.
 * It touches nothing but the number keys and Enter of the System UI lock screen, and only when
 * WakeWordService calls it after the user's spoken password was accepted.
 */
final class PinTyper {
    interface Done { void result(String r); }

    /** Results passed to Done. */
    static final String OK = "ok", WRONG = "wrong", NO_PAD = "no_pad", ERROR = "error";

    private static final String SYSTEM_UI = "com.android.systemui", ID = SYSTEM_UI + ":id/";
    private static final long KEY_GAP_MS = 90, POLL_MS = 250;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private PinTyper() { }

    static void type(AccessibilityService s, String pin, Done done) {
        wakeScreen(s);
        // Asks Android to show the PIN pad. Allowed from the background because the accessibility service is bound.
        try { s.startActivity(new Intent(s, UnlockActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)); }
        catch (Exception ignored) { }
        waitForPad(s, pin, done, 0);
    }

    private static void waitForPad(AccessibilityService s, String pin, Done done, int tries) {
        if (!locked(s)) { done.result(OK); return; }
        if (find(s, "key0") != null) { main.postDelayed(() -> press(s, pin, 0, done), 200); return; }
        if (tries == 6) swipeUp(s); // about 1.5 s: if the PIN pad still isn't up, swipe up like a finger would
        if (tries >= 20) { done.result(NO_PAD); return; }
        main.postDelayed(() -> waitForPad(s, pin, done, tries + 1), POLL_MS);
    }

    private static void press(AccessibilityService s, String pin, int i, Done done) {
        if (i == pin.length()) {
            main.postDelayed(() -> {
                if (!locked(s)) { done.result(OK); return; } // phone confirmed the PIN by itself
                AccessibilityNodeInfo enter = find(s, "key_enter");
                if (enter != null) click(s, enter);
                main.postDelayed(() -> done.result(locked(s) ? WRONG : OK), 1500);
            }, 300);
            return;
        }
        AccessibilityNodeInfo key = find(s, "key" + pin.charAt(i));
        if (key == null || !click(s, key)) { done.result(ERROR); return; }
        main.postDelayed(() -> press(s, pin, i + 1, done), KEY_GAP_MS);
    }

    /** A visible node with this id on the System UI lock screen, or null. */
    private static AccessibilityNodeInfo find(AccessibilityService s, String id) {
        try {
            for (AccessibilityWindowInfo w : s.getWindows()) {
                AccessibilityNodeInfo n = inRoot(w.getRoot(), id);
                if (n != null) return n;
            }
        } catch (Exception ignored) { }
        return inRoot(s.getRootInActiveWindow(), id);
    }

    private static AccessibilityNodeInfo inRoot(AccessibilityNodeInfo root, String id) {
        if (root == null || root.getPackageName() == null || !SYSTEM_UI.contentEquals(root.getPackageName())) return null;
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(ID + id);
        for (AccessibilityNodeInfo n : found) if (n.isVisibleToUser()) return n;
        return null;
    }

    private static boolean click(AccessibilityService s, AccessibilityNodeInfo n) {
        AccessibilityNodeInfo t = n;
        while (t != null && !t.isClickable()) t = t.getParent();
        if (t != null && t.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        Path p = new Path();
        p.moveTo(r.centerX(), r.centerY());
        return s.dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, 50)).build(), null, null);
    }

    private static void swipeUp(AccessibilityService s) {
        DisplayMetrics m = s.getResources().getDisplayMetrics();
        Path p = new Path();
        p.moveTo(m.widthPixels / 2f, m.heightPixels * 0.85f);
        p.lineTo(m.widthPixels / 2f, m.heightPixels * 0.3f);
        try { s.dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, 250)).build(), null, null); }
        catch (Exception ignored) { }
    }

    @SuppressWarnings("deprecation")
    private static void wakeScreen(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (pm == null || pm.isInteractive()) return;
        try {
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "kalki:voiceunlock");
            wl.acquire(5000);
        } catch (Exception ignored) { }
    }

    static boolean locked(Context c) {
        KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isKeyguardLocked();
    }
}
