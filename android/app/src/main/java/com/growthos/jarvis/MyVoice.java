package com.growthos.jarvis;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * "Use my voice": Jarvis speaks with the user's own voice model (a Piper VITS voice trained for free in
 * Google Colab), fully offline, using sherpa-onnx (Apache-2.0). The voice zip holds model.onnx, tokens.txt and
 * espeak-ng-data/. It is copied into this app's private folder; nothing is uploaded.
 */
final class MyVoice {
    private MyVoice() { }

    static final String PREFS = "jarvis_settings", KEY_ON = "my_voice_on";
    private static final Object lock = new Object();
    private static OfflineTts engine;
    private static volatile int generation;
    private static AudioTrack track;
    private static final Handler main = new Handler(Looper.getMainLooper());

    static File dir(Context c) { return new File(c.getFilesDir(), "my_voice"); }

    static boolean installed(Context c) {
        File d = dir(c);
        return new File(d, "model.onnx").isFile() && new File(d, "tokens.txt").isFile() && new File(d, "espeak-ng-data").isDirectory();
    }

    static boolean on(Context c) { return installed(c) && c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, true); }

    static void setOn(Context c, boolean on) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply(); }

    /** Name of the voice file the user imported (for the setup screen). */
    static String label(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("my_voice_label", "your voice"); }

    /**
     * Copies a voice zip into the app. Accepts any .onnx (renamed model.onnx), its tokens.txt and the
     * espeak-ng-data folder, at any depth. Refuses paths that try to leave the folder. Blocking.
     * Returns null on success, else a short reason.
     */
    static String importZip(Context c, Uri zip, String name) {
        File tmp = new File(c.getFilesDir(), "my_voice_tmp");
        deleteTree(tmp);
        tmp.mkdirs();
        boolean model = false, tokens = false, data = false;
        try (InputStream in = c.getContentResolver().openInputStream(zip); ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = z.getNextEntry()) != null) {
                String path = e.getName().replace('\\', '/');
                if (e.isDirectory() || path.contains("../") || path.startsWith("/") || path.contains("__MACOSX")) continue;
                String rel;
                int at = path.indexOf("espeak-ng-data/");
                if (at >= 0) { rel = path.substring(at); data = true; }
                else if (path.endsWith(".onnx")) { rel = "model.onnx"; model = true; }
                else if (path.endsWith("tokens.txt")) { rel = "tokens.txt"; tokens = true; }
                else continue;
                File out = new File(tmp, rel);
                if (!out.getCanonicalPath().startsWith(tmp.getCanonicalPath() + File.separator)) continue;
                out.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(out)) { int n; while ((n = z.read(buf)) > 0) o.write(buf, 0, n); }
            }
        } catch (Exception ex) { deleteTree(tmp); return "couldn't read the file (" + ex.getMessage() + ")"; }
        if (!model || !tokens || !data) {
            deleteTree(tmp);
            return "this is not a Jarvis voice file (it needs a .onnx model, tokens.txt and espeak-ng-data). Use the zip the Colab notebook made.";
        }
        release();
        File d = dir(c);
        deleteTree(d);
        if (!tmp.renameTo(d)) { deleteTree(tmp); return "couldn't save the voice"; }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, true).putString("my_voice_label", name == null ? "your voice" : name).apply();
        return null;
    }

    static void remove(Context c) { release(); deleteTree(dir(c)); setOn(c, false); }

    /** Loads the voice (about 1 to 3 seconds the first time). Blocking. Null if it can't load. */
    private static OfflineTts engine(Context c) {
        synchronized (lock) {
            if (engine != null) return engine;
            File d = dir(c);
            OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
            vits.setModel(new File(d, "model.onnx").getAbsolutePath());
            vits.setTokens(new File(d, "tokens.txt").getAbsolutePath());
            vits.setDataDir(new File(d, "espeak-ng-data").getAbsolutePath());
            OfflineTtsModelConfig model = new OfflineTtsModelConfig();
            model.setVits(vits);
            model.setNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
            model.setProvider("cpu");
            OfflineTtsConfig cfg = new OfflineTtsConfig();
            cfg.setModel(model);
            cfg.setMaxNumSentences(1);
            try { engine = new OfflineTts(null, cfg); } catch (Throwable t) { engine = null; }
            return engine;
        }
    }

    /** Loads the voice in the background so the first reply is quicker. */
    static void warmUp(Context c) {
        if (!on(c)) return;
        Context app = c.getApplicationContext();
        new Thread(() -> engine(app), "jarvis-myvoice-load").start();
    }

    static void release() {
        stop();
        synchronized (lock) { if (engine != null) { try { engine.release(); } catch (Throwable ignored) { } engine = null; } }
    }

    /** Stops what is being spoken now. */
    static void stop() {
        generation++;
        AudioTrack t = track;
        if (t != null) { try { t.pause(); t.flush(); } catch (Exception ignored) { } }
    }

    /**
     * Speaks text in the user's voice. Sentences are played as soon as each is made, so long replies start
     * quickly. done runs on the main thread with true when finished, or false if the voice couldn't speak
     * (the caller then uses the normal phone voice). rate 1.0 = normal speed.
     */
    static void speak(Context c, String text, float rate, java.util.function.Consumer<Boolean> done) {
        final int my = ++generation;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            boolean ok = false;
            try {
                OfflineTts tts = engine(app);
                if (tts != null) {
                    int sr = tts.sampleRate();
                    AudioTrack t = trackFor(sr);
                    final boolean[] started = {false};
                    final long[] written = {0};
                    tts.generateWithCallback(text, 0, Math.max(0.6f, Math.min(2.0f, rate)), samples -> {
                        if (generation != my) return 0;
                        if (!started[0]) { started[0] = true; t.play(); }
                        short[] pcm = new short[samples.length];
                        for (int i = 0; i < samples.length; i++) pcm[i] = (short) Math.max(-32768, Math.min(32767, Math.round(samples[i] * 32767f)));
                        int off = 0;
                        while (off < pcm.length && generation == my) { int n = t.write(pcm, off, pcm.length - off); if (n <= 0) break; off += n; }
                        written[0] += off;
                        return generation == my ? 1 : 0;
                    });
                    if (generation == my && started[0]) {
                        // Let the last part play out before saying we're done.
                        long until = System.currentTimeMillis() + 3000 + written[0] * 1000L / sr;
                        while (generation == my && (t.getPlaybackHeadPosition() & 0xffffffffL) < written[0] && System.currentTimeMillis() < until) Thread.sleep(30);
                        if (generation == my) t.pause();
                    }
                    ok = started[0] || text.trim().isEmpty();
                }
            } catch (Throwable ignored) { }
            final boolean result = ok;
            if (generation == my) main.post(() -> done.accept(result));
        }, "jarvis-myvoice").start();
    }

    private static synchronized AudioTrack trackFor(int sr) {
        if (track != null && track.getSampleRate() == sr) {
            try { track.pause(); track.flush(); } catch (Exception ignored) { }
            return track;
        }
        if (track != null) { try { track.release(); } catch (Exception ignored) { } }
        int min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(min, sr)) // about half a second
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        return track;
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
