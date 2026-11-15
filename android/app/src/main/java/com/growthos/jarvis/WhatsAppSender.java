package com.growthos.jarvis;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import java.util.ArrayList;
import java.util.Collections;
import java.util.regex.Pattern;
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
 * Jarvis's optional screen helper (Android accessibility), limited to WhatsApp and YouTube in
 * res/xml/whatsapp_sender.xml. It acts only when the user gives a voice command:
 *  - WhatsApp: taps Send once, only right after the user said "yes" to "Send it, Boss?".
 *  - YouTube: scrolls, or taps the n-th video on screen, when asked by voice.
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

    static final String YOUTUBE = "com.google.android.youtube";
    private static volatile WhatsAppSender instance;

    @Override protected void onServiceConnected() { super.onServiceConnected(); instance = this; }
    @Override public boolean onUnbind(android.content.Intent i) { if (instance == this) instance = null; return super.onUnbind(i); }
    @Override public void onDestroy() { if (instance == this) instance = null; super.onDestroy(); }

    /** Money apps Jarvis never touches (banking, UPI, wallets, trading). */
    private static final Pattern BLOCKED = Pattern.compile("(?i).*(bank|upi|paytm|phonepe|paisa|nbu\\.|wallet|mobikwik|dreamplug|freecharge|bhim|npci|sbi|hdfc|icici|axis|kotak|zerodha|groww|upstox|angelbroking|trading|finance|loan|insurance|authenticator|password|keepass|bitwarden|1password|lastpass|\\.pay\\b|pay$).*");

    static boolean blocked(CharSequence pkg) { return pkg == null || BLOCKED.matcher(pkg).matches(); }

    private static boolean allowedPkg(CharSequence p) {
        return p != null && !blocked(p) && !"com.android.systemui".contentEquals(p);
    }

    /**
     * Root of the WhatsApp/YouTube window on screen. Looks at the active window first, then at all
     * app windows (the active one can be the keyboard or a system bar). Null if neither app is on screen.
     */
    private AccessibilityNodeInfo appRoot() {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r != null && allowedPkg(r.getPackageName())) return r;
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo wr = w.getRoot();
                if (wr != null && allowedPkg(wr.getPackageName())) return wr;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Package of the app on screen, if it is one this helper may control; else null. */
    static String foregroundApp() {
        WhatsAppSender s = instance;
        if (s == null) return null;
        AccessibilityNodeInfo root = s.appRoot();
        return root == null || root.getPackageName() == null ? null : root.getPackageName().toString();
    }

    /** For the setup report: what the helper sees (package names only). */
    static String describeScreen() {
        WhatsAppSender s = instance;
        if (s == null) return "helper not connected";
        AccessibilityNodeInfo a = s.getRootInActiveWindow();
        StringBuilder b = new StringBuilder("active=").append(a == null ? "none" : a.getPackageName()).append(" windows=");
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : s.getWindows()) {
                AccessibilityNodeInfo wr = w.getRoot();
                b.append(wr == null ? "?" : wr.getPackageName()).append('/').append(w.getType()).append(' ');
            }
        } catch (Exception e) { b.append("error"); }
        return b.toString().trim();
    }

    static boolean running() { return instance != null; }

    /** Scrolls the main list of the app on screen. */
    static boolean scroll(boolean down) {
        WhatsAppSender s = instance;
        if (s == null) return false;
        AccessibilityNodeInfo root = s.appRoot();
        if (root == null) return false;
        AccessibilityNodeInfo list = biggestScrollable(root, 0);
        if (list != null && list.performAction(down ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true;
        // Fallback: a finger swipe in the middle of the screen.
        Rect r = new Rect();
        root.getBoundsInScreen(r);
        Path p = new Path();
        float x = r.centerX(), top = r.top + r.height() * 0.3f, bottom = r.top + r.height() * 0.75f;
        p.moveTo(x, down ? bottom : top);
        p.lineTo(x, down ? top : bottom);
        return s.dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, 300)).build(), null, null);
    }

    private static final Pattern VIDEO = Pattern.compile("(?is).*(play video|\\d[\\d.,]*\\s*[kmb]?\\s+views|\\d+\\s+(?:seconds?|minutes?|hours?)\\b.*go to channel).*");

    /** Taps the n-th video (1 = top) visible in YouTube. */
    static String playVideo(int n) {
        WhatsAppSender s = instance;
        if (s == null) return null;
        AccessibilityNodeInfo root = s.appRoot();
        if (root == null || !YOUTUBE.contentEquals(root.getPackageName())) return null;
        List<AccessibilityNodeInfo> found = new ArrayList<>();
        collectVideos(root, found, 0);
        if (found.isEmpty()) return null;
        Collections.sort(found, (a, b) -> { Rect ra = new Rect(), rb = new Rect(); a.getBoundsInScreen(ra); b.getBoundsInScreen(rb); return ra.top != rb.top ? ra.top - rb.top : ra.left - rb.left; });
        AccessibilityNodeInfo v = found.get(Math.min(n, found.size()) - 1);
        CharSequence d = v.getContentDescription();
        AccessibilityNodeInfo target = v;
        while (target != null && !target.isClickable()) target = target.getParent();
        boolean ok = target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!ok) {
            Rect r = new Rect(); v.getBoundsInScreen(r);
            Path p = new Path(); p.moveTo(r.centerX(), r.centerY());
            ok = s.dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
        }
        if (!ok) return null;
        String title = d == null ? "" : d.toString().split(" - |, ")[0].trim();
        return title.length() > 80 ? title.substring(0, 80) : title;
    }

    private static void collectVideos(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 40) return;
        CharSequence d = n.getContentDescription();
        if (d != null && n.isVisibleToUser() && VIDEO.matcher(d).matches()) {
            Rect r = new Rect(); n.getBoundsInScreen(r);
            if (r.height() > 80) { out.add(n); return; }
        }
        for (int i = 0; i < n.getChildCount(); i++) collectVideos(n.getChild(i), out, depth + 1);
    }

    private static AccessibilityNodeInfo biggestScrollable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 40) return null;
        AccessibilityNodeInfo best = null; int bestArea = 0;
        if (n.isScrollable() && n.isVisibleToUser()) { Rect r = new Rect(); n.getBoundsInScreen(r); best = n; bestArea = r.width() * r.height(); }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = biggestScrollable(n.getChild(i), depth + 1);
            if (c != null) { Rect r = new Rect(); c.getBoundsInScreen(r); int a = r.width() * r.height(); if (a > bestArea) { best = c; bestArea = a; } }
        }
        return best;
    }

    // ---------- any-screen control ----------

    /** Taps the visible item whose text or label best matches the spoken words. Returns its label or null. */
    static String tap(String spoken) {
        WhatsAppSender s = instance;
        if (s == null) return null;
        AccessibilityNodeInfo root = s.appRoot();
        if (root == null) return null;
        String q = AppMatcher.norm(spoken);
        AccessibilityNodeInfo[] best = {null};
        int[] bestScore = {0};
        findLabel(root, q, best, bestScore, 0);
        AccessibilityNodeInfo n = best[0];
        if (n == null) return null;
        String label = label(n);
        AccessibilityNodeInfo t = n;
        while (t != null && !t.isClickable()) t = t.getParent();
        if (t != null && t.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return label;
        Rect r = new Rect(); n.getBoundsInScreen(r);
        Path p = new Path(); p.moveTo(r.centerX(), r.centerY());
        return s.dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null) ? label : null;
    }

    private static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText() != null && n.getText().length() > 0 ? n.getText() : n.getContentDescription();
        return t == null ? "" : t.toString();
    }

    private static void findLabel(AccessibilityNodeInfo n, String q, AccessibilityNodeInfo[] best, int[] bestScore, int depth) {
        if (n == null || depth > 40) return;
        if (n.isVisibleToUser() && !n.isPassword()) {
            String l = AppMatcher.norm(label(n));
            if (!l.isEmpty()) {
                int sc = l.equals(q) ? 100 : l.startsWith(q + " ") || l.startsWith(q) && q.length() >= 4 ? 80
                        : (" " + l + " ").contains(" " + q + " ") ? 60 : AppMatcher.similarity(l.replace(" ", ""), q.replace(" ", "")) >= 0.8 ? 50 : 0;
                if (sc > 0 && l.length() > 120) sc -= 20; // long text blocks rank below buttons
                if (sc > bestScore[0]) { bestScore[0] = sc; best[0] = n; }
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) findLabel(n.getChild(i), q, best, bestScore, depth + 1);
    }

    /** Types into the focused text box (or the first one on screen). Never into password boxes. */
    static String type(String text) {
        WhatsAppSender s = instance;
        if (s == null) return "off";
        AccessibilityNodeInfo root = s.appRoot();
        if (root == null) return "blocked";
        AccessibilityNodeInfo box = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (box == null || !box.isEditable()) box = firstEditable(root, 0);
        if (box == null) return "none";
        if (box.isPassword()) return "password";
        CharSequence old = box.getText();
        String now = old == null || box.isShowingHintText() || old.length() == 0 ? text : old + " " + text;
        android.os.Bundle b = new android.os.Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, now);
        box.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        return box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b) ? "ok" : "failed";
    }

    private static AccessibilityNodeInfo firstEditable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 40) return null;
        if (n.isEditable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) { AccessibilityNodeInfo r = firstEditable(n.getChild(i), depth + 1); if (r != null) return r; }
        return null;
    }

    /** Short spoken summary of the visible text on screen (no password boxes). */
    static String readScreen() {
        WhatsAppSender s = instance;
        if (s == null) return null;
        AccessibilityNodeInfo root = s.appRoot();
        if (root == null) return null;
        List<String> out = new ArrayList<>();
        collectText(root, out, 0);
        StringBuilder b = new StringBuilder();
        for (String t : out) { if (b.length() + t.length() > 600) break; b.append(t).append(". "); }
        return b.toString().trim();
    }

    private static void collectText(AccessibilityNodeInfo n, List<String> out, int depth) {
        if (n == null || depth > 40 || out.size() > 40) return;
        if (n.isVisibleToUser() && !n.isPassword() && n.getText() != null) {
            String t = n.getText().toString().trim();
            if (t.length() > 1 && !out.contains(t)) out.add(t);
        }
        for (int i = 0; i < n.getChildCount(); i++) collectText(n.getChild(i), out, depth + 1);
    }

    /** Home, recent apps, notifications. These work on any screen. */
    static boolean global(int action) {
        WhatsAppSender s = instance;
        return s != null && s.performGlobalAction(action);
    }

    static boolean back() {
        WhatsAppSender s = instance;
        return s != null && s.performGlobalAction(GLOBAL_ACTION_BACK);
    }

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
