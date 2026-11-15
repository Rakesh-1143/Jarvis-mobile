package com.growthos.jarvis;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads phone contacts (name + number) on the phone only, and builds WhatsApp chat links. */
final class Contacts {
    static final String[] WHATSAPP = {"com.whatsapp", "com.whatsapp.w4b"};

    static boolean allowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Contacts with a phone number. The App "packageName" field holds the number (digits with country code). */
    static List<AppMatcher.App> withNumbers(Context c) {
        List<AppMatcher.App> out = new ArrayList<>();
        if (!allowed(c)) return out;
        Set<String> seen = new HashSet<>();
        String[] cols = {ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER};
        try (Cursor cur = c.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                String name = cur.getString(0), num = normalizePhone(cur.getString(1));
                if (name == null || num == null || !seen.add(name.trim().toLowerCase() + "|" + num)) continue;
                out.add(new AppMatcher.App(name.trim(), num));
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Digits with country code; 10-digit numbers are treated as Indian (+91). */
    static String normalizePhone(String raw) {
        if (raw == null) return null;
        String d = raw.replaceAll("[^0-9]", "");
        if (raw.trim().startsWith("+")) return d.length() >= 8 ? d : null;
        if (d.startsWith("00")) d = d.substring(2);
        else if (d.length() == 11 && d.startsWith("0")) d = "91" + d.substring(1);
        else if (d.length() == 10) d = "91" + d;
        return d.length() >= 8 ? d : null;
    }

    /** Installed WhatsApp package, or null. */
    static String whatsappPackage(Context c) {
        for (String p : WHATSAPP) {
            try { c.getPackageManager().getPackageInfo(p, 0); return p; } catch (Exception ignored) { }
        }
        return null;
    }

    /** Opens the chat with the text typed in. The user still taps Send. */
    static Intent whatsappChat(String pkg, String number, String text) {
        Uri uri = Uri.parse("https://wa.me/" + number + (text == null || text.isEmpty() ? "" : "?text=" + Uri.encode(text)));
        return new Intent(Intent.ACTION_VIEW, uri).setPackage(pkg);
    }
}
