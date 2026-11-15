package com.growthos.jarvis;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Optional: lets Jarvis read your recent notifications aloud when you ask. The user turns it on in
 * Android's notification access settings. Kept in memory only (last 20), never saved or uploaded.
 * OTPs, codes, bank and payment notifications are never read.
 */
public final class JarvisNotifications extends NotificationListenerService {
    private static volatile JarvisNotifications instance;

    @Override public void onListenerConnected() { instance = this; }
    @Override public void onListenerDisconnected() { if (instance == this) instance = null; }
    @Override public void onDestroy() { if (instance == this) instance = null; super.onDestroy(); }

    static boolean isOn(Context c) {
        String on = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return on != null && on.contains(new ComponentName(c, JarvisNotifications.class).flattenToString());
    }

    static boolean connected() { return instance != null; }

    private static final Pattern SECRET = Pattern.compile("(?is).*(\\botp\\b|one[- ]time|verification code|security code|login code|passcode|password|pin\\b|\\bcvv\\b|"
            + "debited|credited|a/c|acct|account no|upi|transaction|txn|balance|bank|card ending|\\bcode\\b.*\\d{4,}|\\d{4,8}\\s+is your).*");

    /** Words to speak for the newest notifications, optionally only from one app (by name). */
    static String readRecent(Context c, String appName, int max) {
        JarvisNotifications s = instance;
        if (s == null) return null;
        StatusBarNotification[] all;
        try { all = s.getActiveNotifications(); } catch (Exception e) { return null; }
        if (all == null) return "";
        java.util.Arrays.sort(all, (a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
        PackageManager pm = c.getPackageManager();
        List<String> out = new ArrayList<>();
        int hidden = 0;
        for (StatusBarNotification n : all) {
            if (out.size() >= max) break;
            String pkg = n.getPackageName();
            if (pkg.equals(c.getPackageName()) || pkg.equals("android") || pkg.equals("com.android.systemui")) continue;
            Notification no = n.getNotification();
            if ((no.flags & Notification.FLAG_ONGOING_EVENT) != 0 || (no.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
            String label;
            try { label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception e) { label = pkg; }
            if (appName != null && !appName.isEmpty() && AppMatcher.match(appName, java.util.Collections.singletonList(new AppMatcher.App(label, pkg))) == null) continue;
            Bundle x = no.extras;
            String title = x == null ? "" : String.valueOf(x.getCharSequence(Notification.EXTRA_TITLE, ""));
            String text = x == null ? "" : String.valueOf(x.getCharSequence(Notification.EXTRA_TEXT, ""));
            if (title.isEmpty() && text.isEmpty()) continue;
            if (WhatsAppSender.blocked(pkg) || SECRET.matcher(title + " " + text).matches()) { hidden++; continue; }
            if (text.length() > 140) text = text.substring(0, 140);
            out.add(label + ", " + title + (text.isEmpty() ? "" : ": " + text));
        }
        StringBuilder b = new StringBuilder();
        if (out.isEmpty()) b.append(hidden > 0 ? "" : "Boss, you have no new notifications" + (appName == null || appName.isEmpty() ? "" : " from " + appName) + ".");
        else { b.append("Boss, ").append(out.size()).append(out.size() == 1 ? " notification. " : " notifications. "); for (String o : out) b.append(o).append(". "); }
        if (hidden > 0) b.append(" I skipped ").append(hidden).append(hidden == 1 ? " private one" : " private ones").append(", like codes or bank messages.");
        return b.toString().trim();
    }
}
