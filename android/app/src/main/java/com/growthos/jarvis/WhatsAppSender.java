package com.growthos.jarvis;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

/**
 * Taps WhatsApp's Send button once, only right after the user said "yes" to "Send it, Boss?".
 * Android limits it to WhatsApp (see res/xml/whatsapp_sender.xml). It does nothing unless armed,
 * reads no other screens, and only taps Send when the typed text is exactly the confirmed message.
 * The user turns it on or off in Android Settings > Accessibility.
 */
public final class WhatsAppSender extends AccessibilityService {
    private static final long WINDOW_MS = 60_000L;
    private static volatile String pendingPkg, pendingText;
    private static volatile long deadline;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Runnable expire = () -> {
        if (pendingText != null) { disarm(); WakeWordService.announce("Boss, I couldn't press send. Please tap it."); }
    };

    static void arm(String pkg, String text) {
        pendingPkg = pkg; pendingText = text.trim(); deadline = SystemClock.elapsedRealtime() + WINDOW_MS;
        main.removeCallbacks(expire);
        main.postDelayed(expire, WINDOW_MS);
    }

    private static void disarm() { pendingText = null; pendingPkg = null; main.removeCallbacks(expire); }

    /** Whether the user switched this on in Android accessibility settings. */
    static boolean isOn(Context c) {
        String on = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (on == null) return false;
        ComponentName me = new ComponentName(c, WhatsAppSender.class);
        for (String s : on.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(s);
            if (me.equals(cn)) return true;
        }
        return false;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        String text = pendingText, pkg = pendingPkg;
        if (text == null || pkg == null || e == null || !pkg.contentEquals(e.getPackageName() == null ? "" : e.getPackageName())) return;
        if (SystemClock.elapsedRealtime() > deadline) { disarm(); return; }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        if (!typed(root, pkg, text)) return;
        AccessibilityNodeInfo send = first(root.findAccessibilityNodeInfosByViewId(pkg + ":id/send"));
        if (send == null) send = byDescription(root, "Send", 0);
        if (send == null) return;
        AccessibilityNodeInfo target = send;
        while (target != null && !target.isClickable()) target = target.getParent();
        if (target == null) return;
        disarm();
        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) WakeWordService.announce("Message sent, Boss.");
        else WakeWordService.announce("Boss, I couldn't press send. Please tap it.");
    }

    /** True when WhatsApp's message box holds exactly the confirmed message. */
    private static boolean typed(AccessibilityNodeInfo root, String pkg, String text) {
        AccessibilityNodeInfo entry = first(root.findAccessibilityNodeInfosByViewId(pkg + ":id/entry"));
        if (entry == null || entry.getText() == null) return false;
        return entry.getText().toString().trim().equals(text);
    }

    private static AccessibilityNodeInfo first(List<AccessibilityNodeInfo> l) { return l == null || l.isEmpty() ? null : l.get(0); }

    private static AccessibilityNodeInfo byDescription(AccessibilityNodeInfo n, String desc, int depth) {
        if (n == null || depth > 30) return null;
        if (n.getContentDescription() != null && desc.equalsIgnoreCase(n.getContentDescription().toString())) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = byDescription(n.getChild(i), desc, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    @Override public void onInterrupt() { }
}
