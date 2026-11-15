package com.growthos.jarvis;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.telecom.TelecomManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Offline phone actions for voice commands. Each returns what Jarvis should say. Only public Android APIs. */
final class PhoneActions {
    private PhoneActions() { }

    static String time() {
        return "Boss, it's " + new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new Date()) + ".";
    }

    static String date() {
        return "Boss, today is " + new SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH).format(new Date()) + ".";
    }

    static String battery(Context c) {
        BatteryManager bm = (BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
        if (bm == null) return "Boss, I can't read the battery.";
        int pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return "Boss, battery is at " + pct + " percent" + (bm.isCharging() ? " and charging." : ".");
    }

    /** up, down, max, mute, unmute on the media volume. */
    static String volume(Context c, String a) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return "Boss, I can't change the volume.";
        int s = AudioManager.STREAM_MUSIC, f = AudioManager.FLAG_SHOW_UI;
        try {
            switch (a) {
                case "up": am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, f); am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, f); break;
                case "down": am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, f); am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, f); break;
                case "max": am.setStreamVolume(s, am.getStreamMaxVolume(s), f); break;
                case "unmute": am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, f); break;
                default: am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, f); return "Muted, Boss.";
            }
        } catch (SecurityException e) { return "Boss, Android didn't let me change the volume (Do Not Disturb may be on)."; }
        int pct = Math.round(100f * am.getStreamVolume(s) / Math.max(1, am.getStreamMaxVolume(s)));
        return "Volume " + pct + " percent, Boss.";
    }

    static String torch(Context c, boolean on) {
        CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String id : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(flash)) { cm.setTorchMode(id, on); return on ? "Torch on, Boss." : "Torch off, Boss."; }
            }
            return "Boss, this phone has no torch.";
        } catch (Exception e) { return "Boss, the torch is busy (the camera may be open)."; }
    }

    static Intent alarm(int hour, int minute) {
        return new Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis").putExtra(AlarmClock.EXTRA_SKIP_UI, true);
    }

    static Intent timer(int seconds) {
        return new Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis").putExtra(AlarmClock.EXTRA_SKIP_UI, true);
    }

    /**
     * Android 10+ does not let normal apps switch wifi, bluetooth, data or airplane mode,
     * so these open the right settings page.
     */
    static Intent settings(String what) {
        String a;
        switch (what) {
            case "wifi": a = Settings.ACTION_WIFI_SETTINGS; break;
            case "bluetooth": a = Settings.ACTION_BLUETOOTH_SETTINGS; break;
            case "mobile data": case "data": a = Settings.ACTION_DATA_ROAMING_SETTINGS; break;
            case "hotspot": a = Settings.ACTION_WIRELESS_SETTINGS; break;
            case "location": case "gps": a = Settings.ACTION_LOCATION_SOURCE_SETTINGS; break;
            case "display": case "brightness": a = Settings.ACTION_DISPLAY_SETTINGS; break;
            case "sound": a = Settings.ACTION_SOUND_SETTINGS; break;
            case "battery": a = Intent.ACTION_POWER_USAGE_SUMMARY; break;
            case "apps": case "app": a = Settings.ACTION_APPLICATION_SETTINGS; break;
            case "storage": a = Settings.ACTION_INTERNAL_STORAGE_SETTINGS; break;
            case "security": a = Settings.ACTION_SECURITY_SETTINGS; break;
            case "date and time": a = Settings.ACTION_DATE_SETTINGS; break;
            case "airplane mode": case "flight mode": a = Settings.ACTION_AIRPLANE_MODE_SETTINGS; break;
            default: a = Settings.ACTION_SETTINGS;
        }
        return new Intent(a);
    }

    static boolean canCall(Context c) {
        return c.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Places the call when allowed, else opens the dialer with the number. */
    static Intent call(Context c, String digits) {
        Uri tel = Uri.parse("tel:" + (digits.length() > 10 && !digits.startsWith("+") ? "+" + digits : digits));
        return new Intent(canCall(c) ? Intent.ACTION_CALL : Intent.ACTION_DIAL, tel);
    }

    /** answer, reject, end. Needs the "phone calls" permission. */
    @SuppressWarnings("deprecation")
    static String callControl(Context c, String a) {
        if (c.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED)
            return "Boss, please allow phone calls in Jarvis setup.";
        TelecomManager tm = (TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
        if (tm == null) return "Boss, I can't control calls on this phone.";
        try {
            if ("answer".equals(a)) { tm.acceptRingingCall(); return null; }
            return tm.endCall() ? null : "Boss, there is no call to end.";
        } catch (SecurityException e) { return "Boss, Android didn't allow that."; }
    }

    /** Opens an AI app (Claude, ChatGPT, Gemini...) with the question typed in, using Android's normal share. */
    static Intent shareText(String pkg, String text) {
        return new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage(pkg);
    }
}
