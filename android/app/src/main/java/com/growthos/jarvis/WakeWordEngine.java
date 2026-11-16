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
    private final ArrayDeque<float[]> melFrames = new ArrayDeque<>();
    private final ArrayDeque<float[]> features = new ArrayDeque<>();
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

    /** Chunks quieter than this (about -47 dBFS) count as silence. */
    private static final float QUIET_RMS = 150f;
    /** After this many quiet chunks in a row (0.8 s), embeddings of silence are reused instead of recomputed. */
    private static final int QUIET_CHUNKS_BEFORE_SKIP = 10;

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
        melFrames.clear();
        features.clear();
        predictions = 0;
        for (int i = 0; i < MEL_WINDOW; i++) { float[] f = new float[MEL_BINS]; java.util.Arrays.fill(f, 1f); melFrames.add(f); }
        Random r = new Random(7);
        float[] noise = new float[16000 * 4];
        for (int i = 0; i < noise.length; i++) noise[i] = r.nextInt(2001) - 1000;
        float[][] spec = melspectrogram(noise);
        for (int start = 0; start + MEL_WINDOW <= spec.length; start += 8) features.add(embedding(spec, start));
        trim(features, FEATURE_MAX);
    }

    /** Processes one 80 ms chunk and returns the wake score (0..1). */
    public float process(short[] pcm) throws OrtException {
        if (pcm.length != CHUNK) throw new IllegalArgumentException("chunk must be " + CHUNK + " samples");
        int keep = Math.min(rawFilled, MEL_CONTEXT - CHUNK);
        System.arraycopy(raw, rawFilled - keep, raw, 0, keep);
        for (int i = 0; i < CHUNK; i++) raw[keep + i] = pcm[i];
        rawFilled = keep + CHUNK;
        float[] input = new float[rawFilled];
        System.arraycopy(raw, 0, input, 0, rawFilled);
        Collections.addAll(melFrames, melspectrogram(input));
        trim(melFrames, MEL_MAX);

        double sum = 0;
        for (short v : pcm) sum += (double) v * v;
        lastRms = (float) Math.sqrt(sum / CHUNK);
        quietChunks = lastRms < QUIET_RMS ? quietChunks + 1 : 0;
        totalChunks++;
        if (skipSilence && quietChunks > QUIET_CHUNKS_BEFORE_SKIP && !features.isEmpty()) {
            // Nothing but near-silence for the whole embedding window: its embedding would be the
            // same as the last one, so reuse it and skip the expensive models. Saves battery.
            features.add(features.peekLast());
            trim(features, FEATURE_MAX);
            skippedChunks++;
            predictions++;
            lastSkipped = true;
            return 0f;
        }

        float[][] last = new float[MEL_WINDOW][];
        int skip = melFrames.size() - MEL_WINDOW, i = 0, j = 0;
        for (float[] f : melFrames) { if (i++ >= skip) last[j++] = f; }
        features.add(embedding(last, 0));
        trim(features, FEATURE_MAX);
        lastSkipped = false;

        float score = predict();
        return predictions++ < WARMUP_PREDICTIONS ? 0f : score;
    }

    private float[][] melspectrogram(float[] samples) throws OrtException {
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), new long[]{1, samples.length});
             OrtSession.Result r = mel.run(Collections.singletonMap(melIn, t))) {
            float[][] spec = ((float[][][][]) r.get(0).getValue())[0][0];
            for (float[] row : spec) for (int k = 0; k < row.length; k++) row[k] = row[k] / 10f + 2f;
            return spec;
        }
    }

    private float[] embedding(float[][] spec, int start) throws OrtException {
        float[] in = new float[MEL_WINDOW * MEL_BINS];
        for (int f = 0; f < MEL_WINDOW; f++) System.arraycopy(spec[start + f], 0, in, f * MEL_BINS, MEL_BINS);
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(in), new long[]{1, MEL_WINDOW, MEL_BINS, 1});
             OrtSession.Result r = emb.run(Collections.singletonMap(embIn, t))) {
            return ((float[][][][]) r.get(0).getValue())[0][0][0];
        }
    }

    private float predict() throws OrtException {
        float[] in = new float[FEATURE_WINDOW * FEATURES];
        int skip = features.size() - FEATURE_WINDOW, i = 0, j = 0;
        for (float[] f : features) { if (i++ >= skip) System.arraycopy(f, 0, in, (j++) * FEATURES, FEATURES); }
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(in), new long[]{1, FEATURE_WINDOW, FEATURES});
             OrtSession.Result r = wake.run(Collections.singletonMap(wakeIn, t))) {
            return ((float[][]) r.get(0).getValue())[0][0];
        }
    }

    /** The newest 16 x 96 feature window, flattened (input of the wake model and of VoiceVerifier). */
    public float[] lastWindow() {
        float[] out = new float[FEATURE_WINDOW * FEATURES];
        int skip = features.size() - FEATURE_WINDOW, i = 0, j = 0;
        for (float[] f : features) { if (i++ >= skip) System.arraycopy(f, 0, out, (j++) * FEATURES, FEATURES); }
        return out;
    }

    /** The newest feature frame (96 numbers). */
    public float[] lastFrame() { return features.peekLast().clone(); }

    /** The newest {@code n} feature frames, oldest first. */
    public float[][] lastFrames(int n) {
        n = Math.min(n, features.size());
        float[][] out = new float[n][];
        int skip = features.size() - n, i = 0, j = 0;
        for (float[] f : features) { if (i++ >= skip) out[j++] = f; }
        return out;
    }

    /** Share of chunks skipped as silence, 0..1. */
    public float skippedShare() { return totalChunks == 0 ? 0f : (float) skippedChunks / totalChunks; }

    private static void trim(ArrayDeque<float[]> q, int max) { while (q.size() > max) q.removeFirst(); }

    @Override public void close() {
        for (OrtSession s : new OrtSession[]{mel, emb, wake}) { try { s.close(); } catch (Exception ignored) { } }
    }
}
