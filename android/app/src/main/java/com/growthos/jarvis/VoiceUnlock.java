package com.growthos.jarvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;

/**
 * Voice unlock, switched on by the user in Jarvis setup. Two ways:
 *  - MODE_SAY_PIN: the user says the phone's PIN out loud and Jarvis types what it heard. Nothing is stored;
 *    Android itself checks the PIN.
 *  - MODE_SECRET: the user says a secret password. Only a salted PBKDF2 hash of it is kept. If it matches,
 *    Jarvis types the PIN, which is kept encrypted with an AES key in the Android Keystore (the key never
 *    leaves the phone's secure hardware). Never plain text, never logged, never sent anywhere.
 *
 * After MAX_WRONG wrong tries in a row voice unlock stops until the phone is unlocked by hand.
 */
final class VoiceUnlock {
    static final String MODE_OFF = "off", MODE_SAY_PIN = "say_pin", MODE_SECRET = "secret";
    static final int MAX_WRONG = 3;
    private static final String PREFS = "jarvis_unlock", KEY_MODE = "mode", KEY_SALT = "salt", KEY_HASH = "hash",
            KEY_PIN = "pin", KEY_WRONG = "wrong";
    private static final String ALIAS = "kalki_voice_unlock";
    private static final int ITERATIONS = 20000;

    private VoiceUnlock() { }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static String mode(Context c) {
        String m = prefs(c).getString(KEY_MODE, MODE_OFF);
        if (MODE_SECRET.equals(m) && !prefs(c).contains(KEY_PIN)) return MODE_OFF;
        return m;
    }

    static void useSayPin(Context c) {
        clearSecret(c);
        prefs(c).edit().putString(KEY_MODE, MODE_SAY_PIN).putInt(KEY_WRONG, 0).apply();
    }

    /** Saves the secret password (hashed) and the PIN (encrypted). Returns an error to show, or null. */
    static String useSecret(Context c, String password, String pin) {
        String p = normalize(password);
        if (p.replace(" ", "").length() < 6) return "The password must have at least 6 letters.";
        if (p.replace(" ", "").matches("\\d+")) return "Use words for the password, not only numbers.";
        if (pin == null || !pin.matches("\\d{4,16}")) return "The PIN must be 4 to 16 digits.";
        if (p.replace(" ", "").equals(pin)) return "The password must be different from the PIN.";
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            String enc = encrypt(pin);
            prefs(c).edit()
                    .putString(KEY_SALT, b64(salt))
                    .putString(KEY_HASH, b64(hash(p, salt)))
                    .putString(KEY_PIN, enc)
                    .putString(KEY_MODE, MODE_SECRET)
                    .putInt(KEY_WRONG, 0)
                    .apply();
            return null;
        } catch (Exception e) {
            return "Could not save it safely on this phone (" + e.getClass().getSimpleName() + ").";
        }
    }

    static void turnOff(Context c) {
        clearSecret(c);
        prefs(c).edit().putString(KEY_MODE, MODE_OFF).putInt(KEY_WRONG, 0).apply();
    }

    private static void clearSecret(Context c) {
        prefs(c).edit().remove(KEY_SALT).remove(KEY_HASH).remove(KEY_PIN).apply();
        try { KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null); ks.deleteEntry(ALIAS); } catch (Exception ignored) { }
    }

    /** True if any of the recognizer's guesses is the secret password. */
    static boolean checkSecret(Context c, List<String> guesses) {
        SharedPreferences p = prefs(c);
        String salt = p.getString(KEY_SALT, null), want = p.getString(KEY_HASH, null);
        if (salt == null || want == null) return false;
        try {
            byte[] s = unb64(salt), w = unb64(want);
            for (String g : guesses) {
                if (g == null) continue;
                String n = stripPreamble(normalize(g));
                if (!n.isEmpty() && MessageDigest.isEqual(hash(n, s), w)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** The stored PIN, decrypted only for typing it. Null if missing or the key is gone. */
    static String storedPin(Context c) {
        String enc = prefs(c).getString(KEY_PIN, null);
        if (enc == null) return null;
        try { return decrypt(enc); } catch (Exception e) { return null; }
    }

    static boolean lockedOut(Context c) { return prefs(c).getInt(KEY_WRONG, 0) >= MAX_WRONG; }

    /** Counts a wrong try; returns how many tries are left. */
    static int wrong(Context c) {
        int n = prefs(c).getInt(KEY_WRONG, 0) + 1;
        prefs(c).edit().putInt(KEY_WRONG, n).apply();
        return Math.max(0, MAX_WRONG - n);
    }

    /** The phone was unlocked (by voice or by hand): wrong tries start again from zero. */
    static void unlocked(Context c) {
        if (prefs(c).getInt(KEY_WRONG, 0) != 0) prefs(c).edit().putInt(KEY_WRONG, 0).apply();
    }

    // ---------- pure helpers (unit tested) ----------

    /** "blue Mango!" -> "blue mango". */
    static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]+", " ").replaceAll("\\s+", " ").trim();
    }

    /** "my password is blue mango" -> "blue mango". */
    static String stripPreamble(String n) {
        return n.replaceFirst("^(?:(?:my|the) )?(?:password|pass word|secret word|secret)(?: is)? ", "");
    }

    /**
     * What the user said as a PIN, as digits: "4 7 1 9", "four seven one nine", "4719", "double five one two".
     * Null if it contains anything that is not a digit, or is not 4 to 16 digits long.
     */
    static String digitsFromSpeech(String s) {
        String t = normalize(s);
        t = t.replaceFirst("^(?:(?:my|the) )?(?:pin|pin number|password|passcode|code|pin code)(?: is)? ", "");
        if (t.isEmpty()) return null;
        StringBuilder out = new StringBuilder();
        int repeat = 1;
        for (String w : t.split(" ")) {
            if (w.equals("double")) { if (repeat != 1) return null; repeat = 2; continue; }
            if (w.equals("triple")) { if (repeat != 1) return null; repeat = 3; continue; }
            String d = w.matches("\\d+") ? w : digit(w);
            if (d == null) return null;
            if (repeat > 1) {
                if (d.length() != 1) return null;
                String one = d;
                for (int i = 1; i < repeat; i++) d += one;
            }
            out.append(d);
            repeat = 1;
        }
        if (repeat != 1) return null;
        return out.length() >= 4 && out.length() <= 16 ? out.toString() : null;
    }

    private static String digit(String w) {
        switch (w) {
            case "zero": case "oh": case "o": return "0";
            case "one": case "won": return "1";
            case "two": case "to": case "too": return "2";
            case "three": case "tree": return "3";
            case "four": case "for": return "4";
            case "five": return "5";
            case "six": return "6";
            case "seven": return "7";
            case "eight": case "ate": return "8";
            case "nine": return "9";
            default: return null;
        }
    }

    static byte[] hash(String normalized, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(normalized.toCharArray(), salt, ITERATIONS, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }

    // ---------- Android Keystore ----------

    /** No user-authentication requirement: the key must work while the phone is locked, which is the point. */
    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    private static String encrypt(String plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return b64(c.getIV()) + ":" + b64(ct);
    }

    private static String decrypt(String stored) throws Exception {
        String[] parts = stored.split(":");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, unb64(parts[0])));
        return new String(c.doFinal(unb64(parts[1])), StandardCharsets.UTF_8);
    }

    private static String b64(byte[] b) { return Base64.encodeToString(b, Base64.NO_WRAP); }
    private static byte[] unb64(String s) { return Base64.decode(s, Base64.NO_WRAP); }
}
