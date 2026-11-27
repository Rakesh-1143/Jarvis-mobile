package com.growthos.jarvis;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Random;

/**
 * Offline "Hey Jarvis" detector: a Java port of openWakeWord's streaming pipeline
 * (https://github.com/dscripka/openWakeWord, code Apache-2.0, models CC BY-NC-SA 4.0).
 *
 * Audio: 16 kHz mono 16-bit PCM, fed in chunks of {@link #CHUNK} samples (80 ms).
 * 1. melspectrogram.onnx on the newest 1760 samples, scaled x/10 + 2
 * 2. embedding_model.onnx on the newest 76 mel frames -> 96 features
 * 3. wake model on the newest 16 feature rows -> score 0..1
 * Audio never leaves this object; nothing is stored or sent.
 */
public final class WakeWordEngine implements AutoCloseable {
    public static final int CHUNK = 1280;
    private static final int MEL_CONTEXT = CHUNK + 160 * 3, MEL_BINS = 32, MEL_WINDOW = 76, MEL_MAX = 970;
    private static final int FEATURES = 96, FEATURE_WINDOW = 16, FEATURE_MAX = 120, WARMUP_PREDICTIONS = 5;

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession mel, emb, wake;
    private final String melIn, embIn, wakeIn;
    private final float[] raw = new float[MEL_CONTEXT];
    private int rawFilled;
    /** Newest mel frames, by absolute index (a % MEL_MAX); melTotal frames made since reset. */
    private final float[][] melRing = new float[MEL_MAX][];
    private long melTotal;
    /** One slot per chunk. A skipped (quiet) chunk's features are worked out later, only if they are needed. */
    private static final class Slot { float[] f; final long melEnd; Slot(float[] f, long melEnd) { this.f = f; this.melEnd = melEnd; } }
    private final ArrayDeque<Slot> features = new ArrayDeque<>();
    private int predictions;
    private int quietChunks;
    private long skippedChunks, totalChunks;
    /** Which ONNX Runtime backend is in use ("XNNPACK" or "CPU"). */
    public final String backend;
    private boolean skipSilence = true;
    /** Training turns this off so every chunk gets real features. */
    public void setSkipSilence(boolean on) { skipSilence = on; }
    /** True when the last chunk was skipped as silence (no new features). */
    public boolean lastSkipped;
    /** Loudness of the last chunk (RMS of 16-bit samples). */
    public volatile float lastRms;

    /** Chunks quieter than this (about -47 dBFS) always count as quiet. */
    private static final float QUIET_RMS = 150f;
    /**
     * Indoors the Moto G31 hears about -45 dBFS of background hum, so the fixed line above never
     * triggered and the models ran on every chunk. A chunk now also counts as quiet when it is under
     * twice (+6 dB) the room's usual background, capped at about -35 dBFS.
     */
    private static final float ABOVE_BACKGROUND = 2f, MAX_QUIET_RMS = 600f;
    /** After this many quiet chunks in a row (0.8 s), the expensive models are skipped. */
    private static final int QUIET_CHUNKS_BEFORE_SKIP = 10;
    /*
     * Skipped chunks no longer get made-up features (reusing the last one fooled the wake model into
     * waking by itself). Their mel frames are kept, and as soon as sound comes back their real
     * features are worked out before any wake decision, so every decision sees exactly what full
     * listening would have seen. Only quiet stretches that no decision ever looks at are saved.
     */
    private float background = -1f;
    private long melNanos, embNanos, wakeNanos, backfills;

    /** Mel, embedding and wake-model time (ns) and the number of late (backfilled) embeddings since the last call. */
    public long[] takeTimes() { long[] t = {melNanos, embNanos, wakeNanos, backfills}; melNanos = embNanos = wakeNanos = backfills = 0; return t; }

    /** The room's background loudness in dBFS (for the log). */
    public float backgroundDb() { return background <= 1f ? -90f : (float) (20 * Math.log10(background / 32768.0)); }

    private float quietLimit() { return background < 0 ? QUIET_RMS : Math.max(QUIET_RMS, Math.min(background * ABOVE_BACKGROUND, MAX_QUIET_RMS)); }

    /** Falls quickly with quiet chunks, rises slowly (about 40 s), ignores loud bursts such as speech. */
    private void learnBackground(float rms) {
        if (background < 0) background = rms;
        else if (rms < background) background += (rms - background) * 0.05f;
        else if (rms < background * 4f) background += (rms - background) * 0.002f;
    }

    public WakeWordEngine(byte[] melModel, byte[] embeddingModel, byte[] wakeModel) throws OrtException {
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(1);
        o.setInterOpNumThreads(1);
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        OrtSession.SessionOptions fast = new OrtSession.SessionOptions();
        fast.setIntraOpNumThreads(1);
        fast.setInterOpNumThreads(1);
        fast.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        OrtSession e;
        String b;
        try {
            // XNNPACK runs the convolution-heavy embedding model much faster on ARM phones.
            fast.addXnnpack(Collections.singletonMap("intra_op_num_threads", "1"));
            e = env.createSession(embeddingModel, fast);
            b = "XNNPACK";
        } catch (Throwable t) {
            e = env.createSession(embeddingModel, o);
            b = "CPU";
        }
        emb = e;
        backend = b;
        mel = env.createSession(melModel, o);
        wake = env.createSession(wakeModel, o);
        melIn = mel.getInputNames().iterator().next();
        embIn = emb.getInputNames().iterator().next();
        wakeIn = wake.getInputNames().iterator().next();
        reset();
    }

    /** Clears audio history, as openWakeWord does at start (mel buffer of ones, features from noise). */
    public void reset() throws OrtException {
        rawFilled = 0;
        quietChunks = 0;
        java.util.Arrays.fill(melRing, null);
        melTotal = 0;
        features.clear();
        predictions = 0;
        for (int i = 0; i < MEL_WINDOW; i++) { float[] f = new float[MEL_BINS]; java.util.Arrays.fill(f, 1f); addMel(f); }
        if (noiseFeatures == null) {
            // Always the same numbers (fixed seed), so work them out once instead of on every
            // restart of listening (which happens after each command): ~28 model runs saved each time.
            Random r = new Random(7);
            float[] noise = new float[16000 * 4];
            for (int i = 0; i < noise.length; i++) noise[i] = r.nextInt(2001) - 1000;
            float[][] spec = melspectrogram(noise, noise.length);
            ArrayDeque<float[]> f = new ArrayDeque<>();
            for (int start = 0; start + MEL_WINDOW <= spec.length; start += 8) f.add(embedding(spec, start));
            trim(f, FEATURE_MAX);
            noiseFeatures = f.toArray(new float[0][]);
        }
        for (float[] f : noiseFeatures) features.add(new Slot(f, 0));
    }

    private void addMel(float[] f) { melRing[(int) (melTotal % MEL_MAX)] = f; melTotal++; }

    /** Start-up features from noise; the arrays are never changed after they are made. */
    private float[][] noiseFeatures;

    /** Processes one 80 ms chunk and returns the wake score (0..1). */
    public float process(short[] pcm) throws OrtException {
        if (pcm.length != CHUNK) throw new IllegalArgumentException("chunk must be " + CHUNK + " samples");
        int keep = Math.min(rawFilled, MEL_CONTEXT - CHUNK);
        System.arraycopy(raw, rawFilled - keep, raw, 0, keep);
        for (int i = 0; i < CHUNK; i++) raw[keep + i] = pcm[i];
        rawFilled = keep + CHUNK;
        long t0 = System.nanoTime();
        for (float[] f : melspectrogram(raw, rawFilled)) addMel(f);
        melNanos += System.nanoTime() - t0;

        double sum = 0;
        for (short v : pcm) sum += (double) v * v;
        lastRms = (float) Math.sqrt(sum / CHUNK);
        quietChunks = lastRms < (skipSilence ? quietLimit() : QUIET_RMS) ? quietChunks + 1 : 0;
        if (skipSilence) learnBackground(lastRms);
        totalChunks++;
        if (skipSilence && quietChunks > QUIET_CHUNKS_BEFORE_SKIP && !features.isEmpty()) {
            // Quiet for a while: no wake decision now. Keep a slot whose features are worked out
            // only if a later decision needs them (see ensureReal).
            features.add(new Slot(null, melTotal));
            trimFeatures();
            skippedChunks++;
            predictions++;
            lastSkipped = true;
            return 0f;
        }

        long t1 = System.nanoTime();
        features.add(new Slot(embeddingAt(melTotal), melTotal));
        trimFeatures();
        lastSkipped = false;
        embNanos += System.nanoTime() - t1;

        float score = predict();
        return predictions++ < WARMUP_PREDICTIONS ? 0f : score;
    }

    /** Mel frames of the first {@code count} samples. Reads the output as one flat buffer instead of nested Java arrays. */
    private float[][] melspectrogram(float[] samples, int count) throws OrtException {
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(samples, 0, count), new long[]{1, count});
             OrtSession.Result r = mel.run(Collections.singletonMap(melIn, t))) {
            OnnxTensor out = (OnnxTensor) r.get(0);
            long[] shape = out.getInfo().getShape(); // [1, 1, frames, 32]
            int frames = (int) shape[2], bins = (int) shape[3];
            FloatBuffer b = out.getFloatBuffer();
            float[][] spec = new float[frames][bins];
            for (float[] row : spec) { b.get(row); for (int k = 0; k < bins; k++) row[k] = row[k] / 10f + 2f; }
            return spec;
        }
    }

    /** Embedding of the 76 mel frames that end just before absolute index melEnd. */
    private float[] embeddingAt(long melEnd) throws OrtException {
        float[][] win = new float[MEL_WINDOW][];
        for (int k = 0; k < MEL_WINDOW; k++) win[k] = melRing[(int) ((melEnd - MEL_WINDOW + k) % MEL_MAX)];
        return embedding(win, 0);
    }

    /** True while the mel frames for that slot are still kept (the ring holds the newest MEL_MAX, about 10 s). */
    private boolean melKept(long melEnd) { return melEnd - MEL_WINDOW >= Math.max(0, melTotal - MEL_MAX); }

    /** Works out the real features of any skipped slot among the newest n. */
    private void ensureReal(int n) {
        int skip = features.size() - n, i = 0;
        float[] before = null;
        for (Slot s : features) {
            if (i++ >= skip && s.f == null) {
                try {
                    if (melKept(s.melEnd)) { long t = System.nanoTime(); s.f = embeddingAt(s.melEnd); embNanos += System.nanoTime() - t; backfills++; }
                } catch (OrtException e) { /* falls back below */ }
                // Should not happen (frames are kept far longer than any window); old behaviour as a fallback.
                if (s.f == null) s.f = before != null ? before : new float[FEATURES];
            }
            if (s.f != null) before = s.f;
        }
    }

    private void trimFeatures() { while (features.size() > FEATURE_MAX) features.removeFirst(); }

    private float[] embedding(float[][] spec, int start) throws OrtException {
        float[] in = new float[MEL_WINDOW * MEL_BINS];
        for (int f = 0; f < MEL_WINDOW; f++) System.arraycopy(spec[start + f], 0, in, f * MEL_BINS, MEL_BINS);
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(in), new long[]{1, MEL_WINDOW, MEL_BINS, 1});
             OrtSession.Result r = emb.run(Collections.singletonMap(embIn, t))) {
            FloatBuffer b = ((OnnxTensor) r.get(0)).getFloatBuffer(); // [1, 1, 1, 96]
            float[] f = new float[b.remaining()];
            b.get(f);
            return f;
        }
    }

    private float predict() throws OrtException {
        float[] in = lastWindow();
        long t0 = System.nanoTime();
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(in), new long[]{1, FEATURE_WINDOW, FEATURES});
             OrtSession.Result r = wake.run(Collections.singletonMap(wakeIn, t))) {
            return ((OnnxTensor) r.get(0)).getFloatBuffer().get(0); // [1, 1]
        } finally { wakeNanos += System.nanoTime() - t0; }
    }

    /** The newest 16 x 96 feature window, flattened (input of the wake model and of VoiceVerifier). */
    public float[] lastWindow() {
        ensureReal(FEATURE_WINDOW);
        float[] out = new float[FEATURE_WINDOW * FEATURES];
        int skip = features.size() - FEATURE_WINDOW, i = 0, j = 0;
        for (Slot s : features) { if (i++ >= skip) System.arraycopy(s.f, 0, out, (j++) * FEATURES, FEATURES); }
        return out;
    }

    /** The newest feature frame (96 numbers). */
    public float[] lastFrame() { ensureReal(1); return features.peekLast().f.clone(); }

    /** The newest {@code n} feature frames, oldest first. */
    public float[][] lastFrames(int n) {
        n = Math.min(n, features.size());
        ensureReal(n);
        float[][] out = new float[n][];
        int skip = features.size() - n, i = 0, j = 0;
        for (Slot s : features) { if (i++ >= skip) out[j++] = s.f; }
        return out;
    }

    /** Share of chunks skipped as silence, 0..1. */
    public float skippedShare() { return totalChunks == 0 ? 0f : (float) skippedChunks / totalChunks; }

    private static void trim(ArrayDeque<float[]> q, int max) { while (q.size() > max) q.removeFirst(); }

    @Override public void close() {
        for (OrtSession s : new OrtSession[]{mel, emb, wake}) { try { s.close(); } catch (Exception ignored) { } }
    }
}
