package com.growthos.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * "Record my voice for Jarvis": the user reads sentences aloud, one by one, to make a free voice model of
 * their own voice (trained later in Google Colab with Piper). Recording happens only while the user
 * presses Record on this screen. Files stay in this app's folder until the user taps Export, which saves
 * one zip (wavs + metadata.csv, LJSpeech format) to Downloads. "Delete recordings" removes them.
 */
public final class VoiceRecordActivity extends Activity {
    static final int RATE = 22050;
    private static final int TEXT = 0xffe6f7ff, MUTED = 0xff8aa4b4, ACCENT = 0xff00e5ff, GOOD = 0xff4ade80, BAD = 0xffff6b6b, BG = 0xff02070d;
    private static final String PREFS = "jarvis_voice_record", KEY_INDEX = "index";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String> sentences = new ArrayList<>();
    private int index;
    private TextView progress, sentence, status;
    private Button recordButton;
    private volatile boolean recording;
    private Thread recorder;
    private MediaPlayer player;
    private boolean wasListening;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        loadSentences();
        index = Math.min(getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_INDEX, 0), Math.max(0, sentences.size() - 1));
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(BG);
        int p = dp(20);
        l.setPadding(p, p, p, p);
        TextView title = text("Record my voice", 24, TEXT); title.setTypeface(Typeface.DEFAULT_BOLD); l.addView(title);
        l.addView(text("Read each sentence clearly in your normal voice, in a quiet room, with the phone about 20 cm from your mouth. "
                + "Tap Record, read, then tap Stop. About 30 minutes for all. You can stop any time and continue later. "
                + "Recordings stay on this phone until you tap Export.", 14, MUTED));
        progress = text("", 15, ACCENT); progress.setPadding(0, dp(16), 0, 0); l.addView(progress);
        sentence = text("", 26, TEXT); sentence.setTypeface(Typeface.DEFAULT_BOLD); sentence.setPadding(0, dp(20), 0, dp(20)); l.addView(sentence);
        status = text("", 14, MUTED); l.addView(status);
        recordButton = button("Record", v -> toggle());
        l.addView(recordButton);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(button("◀ Back", v -> move(-1)), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(button("▶ Play", v -> play()), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(button("Next ▶", v -> move(1)), new LinearLayout.LayoutParams(0, -2, 1));
        l.addView(row);
        l.addView(button("Go to first sentence not recorded", v -> { for (int i = 0; i < sentences.size(); i++) if (!wav(i).exists()) { index = i; break; } show(); }));
        TextView gap = text("When finished", 15, ACCENT); gap.setPadding(0, dp(24), 0, 0); l.addView(gap);
        l.addView(button("Export recordings to Downloads", v -> export()));
        l.addView(button("Delete all recordings", v -> deleteAll()));
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.addView(l);
        setContentView(sv);
        show();
    }

    @Override protected void onResume() {
        super.onResume();
        // Free the microphone while this screen is open.
        if (WakeWordService.running) { wasListening = true; stopService(new android.content.Intent(this, WakeWordService.class)); }
    }

    @Override protected void onPause() {
        stopRecording();
        if (player != null) { player.release(); player = null; }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_INDEX, index).apply();
        if (wasListening || WakeWordService.isEnabled(this)) WakeWordService.start(this, null);
        wasListening = false;
        super.onPause();
    }

    private void loadSentences() {
        try (InputStream in = getAssets().open("voice/sentences.txt")) {
            for (String s : new String(readAll(in), StandardCharsets.UTF_8).split("\n")) if (!s.trim().isEmpty()) sentences.add(s.trim());
        } catch (Exception e) { sentences.add("Hello Boss, how can I help you today?"); }
    }

    static File dir(android.content.Context c) { File d = new File(c.getExternalFilesDir(null), "voice_data/wavs"); d.mkdirs(); return d; }

    private File wav(int i) { return new File(dir(this), String.format(Locale.ROOT, "s%04d.wav", i + 1)); }

    private int recordedCount() { int n = 0; for (int i = 0; i < sentences.size(); i++) if (wav(i).exists()) n++; return n; }

    private void show() {
        progress.setText(String.format(Locale.US, "Sentence %d of %d · %d recorded", index + 1, sentences.size(), recordedCount()));
        sentence.setText(sentences.get(index));
        boolean done = wav(index).exists();
        status.setText(done ? "✓ Recorded. Tap Play to check, Record to redo, or Next." : "Not recorded yet.");
        status.setTextColor(done ? GOOD : MUTED);
    }

    private void move(int d) {
        if (recording) return;
        index = Math.max(0, Math.min(sentences.size() - 1, index + d));
        show();
    }

    private void toggle() {
        if (recording) { stopRecording(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1); return; }
        if (player != null) { player.release(); player = null; }
        final File out = wav(index);
        final int at = index;
        recording = true;
        recordButton.setText("■ Stop");
        status.setText("Recording… read the sentence now.");
        status.setTextColor(BAD);
        recorder = new Thread(() -> record(out, at), "jarvis-voice-record");
        recorder.start();
    }

    private void stopRecording() {
        recording = false;
        Thread t = recorder;
        if (t != null) { try { t.join(2000); } catch (InterruptedException ignored) { } }
        recorder = null;
        if (recordButton != null) recordButton.setText("Record");
    }

    private void record(File out, int at) {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec;
        try { rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE)); }
        catch (Exception e) { ui.post(() -> { recording = false; recordButton.setText("Record"); status.setText("Microphone is busy. Close other apps using it and try again."); }); return; }
        short[] buf = new short[RATE / 10];
        ShortArray all = new ShortArray();
        try {
            rec.startRecording();
            long started = System.currentTimeMillis();
            while (recording && System.currentTimeMillis() - started < 20_000) {
                int n = rec.read(buf, 0, buf.length);
                if (n < 0) break;
                all.add(buf, n);
            }
        } catch (Exception ignored) {
        } finally { try { rec.stop(); } catch (Exception ignored) { } rec.release(); }
        short[] pcm = trim(all.toArray(), RATE);
        final String result;
        if (pcm.length < RATE / 2) result = "Too short or too quiet. Please record again a little closer to the phone.";
        else if (clipped(pcm)) { writeWav(out, pcm); result = "Saved, but it was very loud. Hold the phone a bit further away and redo it if it sounds harsh."; }
        else { writeWav(out, pcm); result = null; }
        ui.post(() -> {
            recording = false;
            recordButton.setText("Record");
            if (result == null && index == at && index < sentences.size() - 1) index++; // saved: go to the next sentence
            show();
            if (result != null) { status.setText(result); status.setTextColor(BAD); }
        });
    }

    /** Cuts silence at the start and end, keeping 0.2 s of padding. Pure Java. */
    static short[] trim(short[] pcm, int rate) {
        int win = rate / 50; // 20 ms
        double peak = 0;
        for (short s : pcm) peak = Math.max(peak, Math.abs(s));
        double thr = Math.max(300, peak * 0.06);
        int first = -1, last = -1;
        for (int i = 0; i + win <= pcm.length; i += win) {
            double sum = 0;
            for (int j = i; j < i + win; j++) sum += (double) pcm[j] * pcm[j];
            if (Math.sqrt(sum / win) > thr) { if (first < 0) first = i; last = i + win; }
        }
        if (first < 0) return new short[0];
        int pad = rate / 5;
        int a = Math.max(0, first - pad), b = Math.min(pcm.length, last + pad);
        short[] out = new short[b - a];
        System.arraycopy(pcm, a, out, 0, out.length);
        return out;
    }

    static boolean clipped(short[] pcm) {
        int n = 0;
        for (short s : pcm) if (s >= 32000 || s <= -32000) n++;
        return n > pcm.length / 500;
    }

    static void writeWav(File f, short[] pcm) {
        int dataLen = pcm.length * 2;
        try (OutputStream o = new FileOutputStream(f)) {
            java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            h.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataLen).put("WAVE".getBytes(StandardCharsets.US_ASCII))
                    .put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2)
                    .putShort((short) 2).putShort((short) 16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataLen);
            o.write(h.array());
            java.nio.ByteBuffer d = java.nio.ByteBuffer.allocate(dataLen).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (short s : pcm) d.putShort(s);
            o.write(d.array());
        } catch (Exception ignored) { }
    }

    private void play() {
        File f = wav(index);
        if (!f.exists() || recording) return;
        try {
            if (player != null) player.release();
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.prepare();
            player.start();
        } catch (Exception e) { Toast.makeText(this, "Couldn't play it", Toast.LENGTH_SHORT).show(); }
    }

    /** Saves jarvis-voice-data.zip (wavs/*.wav + metadata.csv) to Downloads for the Colab training. */
    private void export() {
        if (Build.VERSION.SDK_INT < 29) { Toast.makeText(this, "Export needs Android 10 or newer", Toast.LENGTH_LONG).show(); return; }
        int count = recordedCount();
        if (count < 50) { Toast.makeText(this, "Please record at least 50 sentences first (" + count + " so far). 100+ sounds better.", Toast.LENGTH_LONG).show(); return; }
        status.setText("Exporting…");
        new Thread(() -> {
            String msg;
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, "jarvis-voice-data.zip");
                v.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IllegalStateException("no Downloads folder");
                StringBuilder meta = new StringBuilder();
                try (OutputStream os = getContentResolver().openOutputStream(uri); ZipOutputStream z = new ZipOutputStream(os)) {
                    for (int i = 0; i < sentences.size(); i++) {
                        File f = wav(i);
                        if (!f.exists()) continue;
                        String id = f.getName().replace(".wav", "");
                        meta.append(id).append('|').append(sentences.get(i)).append('\n');
                        z.putNextEntry(new ZipEntry("wavs/" + f.getName()));
                        try (InputStream in = new FileInputStream(f)) { z.write(readAll(in)); }
                        z.closeEntry();
                    }
                    z.putNextEntry(new ZipEntry("metadata.csv"));
                    z.write(meta.toString().getBytes(StandardCharsets.UTF_8));
                    z.closeEntry();
                }
                msg = "Saved \"jarvis-voice-data.zip\" (" + count + " sentences) in Downloads. Next: upload it to your Google Drive.";
            } catch (Exception e) { msg = "Export failed: " + e.getMessage(); }
            final String m = msg;
            ui.post(() -> { status.setText(m); status.setTextColor(ACCENT); });
        }, "jarvis-voice-export").start();
    }

    private void deleteAll() {
        if (recording) return;
        new android.app.AlertDialog.Builder(this).setMessage("Delete all your voice recordings from this app?")
                .setPositiveButton("Delete", (d, w) -> {
                    File[] fs = dir(this).listFiles();
                    if (fs != null) for (File f : fs) f.delete();
                    index = 0;
                    show();
                }).setNegativeButton("Cancel", null).show();
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[65536]; int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        return out.toByteArray();
    }

    private static final class ShortArray {
        short[] a = new short[RATE * 4]; int n;
        void add(short[] b, int len) { if (n + len > a.length) a = java.util.Arrays.copyOf(a, Math.max(a.length * 2, n + len)); System.arraycopy(b, 0, a, n, len); n += len; }
        short[] toArray() { return java.util.Arrays.copyOf(a, n); }
    }

    private Button button(String label, android.view.View.OnClickListener l) { Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setOnClickListener(l); return b; }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, int color) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setGravity(Gravity.START); return t; }
}
