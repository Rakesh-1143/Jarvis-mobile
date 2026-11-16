package com.growthos.jarvis;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.provider.CalendarContract;
import android.provider.CallLog;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Briefing, next alarm, calendar, Do Not Disturb, ringer mode and missed calls. Public Android APIs only. */
final class Extras {
    private Extras() { }

    static boolean has(Context c, String perm) { return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED; }

    private static String clock(long t) { return new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new Date(t)); }

    private static String dayWord(long t) {
        Calendar now = Calendar.getInstance(), x = Calendar.getInstance();
        x.setTimeInMillis(t);
        int d = x.get(Calendar.DAY_OF_YEAR) - now.get(Calendar.DAY_OF_YEAR) + 365 * (x.get(Calendar.YEAR) - now.get(Calendar.YEAR));
        return d == 0 ? "today" : d == 1 ? "tomorrow" : "on " + new SimpleDateFormat("EEEE d MMMM", Locale.ENGLISH).format(new Date(t));
    }

    /** Next alarm from the clock app, as Android reports it. */
    static String nextAlarm(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        AlarmManager.AlarmClockInfo a = am == null ? null : am.getNextAlarmClock();
        if (a == null) return "Boss, you have no alarms set.";
        return "Boss, your next alarm is at " + clock(a.getTriggerTime()) + " " + dayWord(a.getTriggerTime()) + ".";
    }

    /** Events for today (offset 0) or tomorrow (1). Null when calendar permission is missing. */
    static List<String> events(Context c, int dayOffset) {
        if (!has(c, Manifest.permission.READ_CALENDAR)) return null;
        Calendar from = Calendar.getInstance();
        from.set(Calendar.HOUR_OF_DAY, 0); from.set(Calendar.MINUTE, 0); from.set(Calendar.SECOND, 0); from.set(Calendar.MILLISECOND, 0);
        from.add(Calendar.DAY_OF_MONTH, dayOffset);
        long start = from.getTimeInMillis(), end = start + 24L * 3600 * 1000;
        List<String> out = new ArrayList<>();
        String[] cols = {CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY};
        try (Cursor cur = CalendarContract.Instances.query(c.getContentResolver(), cols, start, end)) {
            List<long[]> order = new ArrayList<>();
            List<String> titles = new ArrayList<>();
            while (cur != null && cur.moveToNext()) {
                String title = cur.getString(0);
                if (title == null || title.trim().isEmpty()) title = "an event";
                boolean allDay = cur.getInt(2) == 1;
                titles.add(allDay ? title.trim() + " all day" : title.trim() + " at " + clock(cur.getLong(1)));
                order.add(new long[]{allDay ? 0 : cur.getLong(1), titles.size() - 1});
            }
            order.sort((x, y) -> Long.compare(x[0], y[0]));
            for (long[] o : order) out.add(titles.get((int) o[1]));
        } catch (Exception ignored) { }
        return out;
    }

    static String calendar(Context c, int dayOffset) {
        List<String> ev = events(c, dayOffset);
        String day = dayOffset == 0 ? "today" : "tomorrow";
        if (ev == null) return "Boss, please allow calendar in Jarvis setup first.";
        if (ev.isEmpty()) return "Boss, your calendar is free " + day + ".";
        StringBuilder b = new StringBuilder("Boss, you have ").append(ev.size()).append(ev.size() == 1 ? " event " : " events ").append(day).append(". ");
        for (int i = 0; i < Math.min(5, ev.size()); i++) b.append(ev.get(i)).append(". ");
        if (ev.size() > 5) b.append("And ").append(ev.size() - 5).append(" more.");
        return b.toString().trim();
    }

    /** Morning-style summary built on the phone; weather is added by the caller when online. */
    static String briefing(Context c, String name) {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String hello = h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
        StringBuilder b = new StringBuilder(hello).append(", ").append(name == null || name.isEmpty() ? "Boss" : name).append(". ");
        b.append("It's ").append(new SimpleDateFormat("h:mm a 'on' EEEE, d MMMM", Locale.ENGLISH).format(new Date())).append(". ");
        android.os.BatteryManager bm = (android.os.BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
        if (bm != null) b.append("Battery is ").append(bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)).append(" percent. ");
        List<String> ev = events(c, 0);
        if (ev != null) {
            if (ev.isEmpty()) b.append("No events on your calendar today. ");
            else b.append("You have ").append(ev.size()).append(ev.size() == 1 ? " event" : " events").append(" today, first ").append(ev.get(0)).append(". ");
        }
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        AlarmManager.AlarmClockInfo a = am == null ? null : am.getNextAlarmClock();
        if (a != null) b.append("Next alarm ").append(clock(a.getTriggerTime())).append(" ").append(dayWord(a.getTriggerTime())).append(". ");
        int notes = Notes.all(c).size();
        if (notes > 0) b.append("You have ").append(notes).append(notes == 1 ? " note." : " notes.");
        return b.toString().trim();
    }

    /** Do Not Disturb on (priority only) or off, through the notification listener (no extra permission). */
    static String dnd(Context c, boolean on) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && nm.isNotificationPolicyAccessGranted()) {
            nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_PRIORITY : NotificationManager.INTERRUPTION_FILTER_ALL);
            return on ? "Do Not Disturb is on, Boss." : "Do Not Disturb is off, Boss.";
        }
        if (JarvisNotifications.setDnd(on)) return on ? "Do Not Disturb is on, Boss." : "Do Not Disturb is off, Boss.";
        return null; // caller opens the settings page
    }

    /** silent, vibrate or normal. Returns null when Android needs Do Not Disturb access first. */
    static String ringer(Context c, String mode) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return "Boss, I can't change the ringer.";
        try {
            if ("silent".equals(mode)) {
                if (!JarvisNotifications.connected()) { am.setRingerMode(AudioManager.RINGER_MODE_SILENT); }
                else { am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE); JarvisNotifications.setDnd(true); }
                return "Phone is on silent, Boss.";
            }
            if ("vibrate".equals(mode)) { am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE); return "Phone is on vibrate, Boss."; }
            JarvisNotifications.setDnd(false);
            am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
            return "Ringer is back on, Boss.";
        } catch (SecurityException e) { return null; }
    }

    /** Missed calls in the last 24 hours, newest first: {name or number, number}. Null without permission. */
    static List<String[]> missedCalls(Context c) {
        return calls(c, CallLog.Calls.TYPE + "=" + CallLog.Calls.MISSED_TYPE + " AND " + CallLog.Calls.DATE + ">" + (System.currentTimeMillis() - 24L * 3600 * 1000), 10);
    }

    /** The most recent incoming or missed call: {name or number, number}, or null. */
    static String[] lastCaller(Context c) {
        List<String[]> l = calls(c, CallLog.Calls.TYPE + " IN (" + CallLog.Calls.MISSED_TYPE + "," + CallLog.Calls.INCOMING_TYPE + ")", 1);
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    private static List<String[]> calls(Context c, String where, int max) {
        if (!has(c, Manifest.permission.READ_CALL_LOG)) return null;
        List<String[]> out = new ArrayList<>();
        String[] cols = {CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.DATE};
        try (Cursor cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI, cols, where, null, CallLog.Calls.DATE + " DESC")) {
            while (cur != null && cur.moveToNext() && out.size() < max) {
                String name = cur.getString(0), num = cur.getString(1);
                if (num == null || num.trim().isEmpty()) continue;
                out.add(new String[]{name == null || name.trim().isEmpty() ? spaced(num) : name.trim(), num, clock(cur.getLong(2))});
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** "9876543210" -> "9 8 7 6 5 4 3 2 1 0" so text-to-speech reads digits one by one. */
    static String spaced(String num) { return num.replaceAll("[^0-9+]", "").replaceAll("(.)(?=.)", "$1 "); }

    static String speakMissed(Context c) {
        List<String[]> l = missedCalls(c);
        if (l == null) return "Boss, please allow call history in Jarvis setup first.";
        if (l.isEmpty()) return "Boss, no missed calls today.";
        StringBuilder b = new StringBuilder("Boss, ").append(l.size()).append(l.size() == 1 ? " missed call. " : " missed calls. ");
        for (int i = 0; i < Math.min(5, l.size()); i++) b.append(l.get(i)[0]).append(" at ").append(l.get(i)[2]).append(". ");
        return b.toString().trim();
    }
}
