package com.growthos.jarvis;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the user's training recordings into a {@link TemplateMatcher}.
 * Recordings stay in memory only and are dropped after training.
 */
public final class VoiceTrainer {
    private final WakeWordEngine engine;
    private final List<float[][]> examples = new ArrayList<>(), others = new ArrayList<>();

    public VoiceTrainer(WakeWordEngine engine) { this.engine = engine; }

    /** Feature frame (96 numbers) for every 80 ms chunk; rmsOut gets each chunk's loudness. */
    private float[][] frames(short[] audio, float[] rmsOut) throws Exception {
        engine.reset();
        engine.setSkipSilence(false);
        try {
            int n = audio.length / WakeWordEngine.CHUNK;
            float[][] out = new float[n][];
            short[] chunk = new short[WakeWordEngine.CHUNK];
            for (int i = 0; i < n; i++) {
                System.arraycopy(audio, i * WakeWordEngine.CHUNK, chunk, 0, WakeWordEngine.CHUNK);
                engine.process(chunk);
                if (rmsOut != null) rmsOut[i] = engine.lastRms;
                out[i] = engine.lastFrame();
            }
            return out;
        } finally { engine.setSkipSilence(true); }
    }

    /** First and last chunk with speech, or null when the clip has no speech. Pure, unit-tested. */
    static int[] speechSpan(float[] rms) {
        if (rms.length < 6) return null;
        float[] sorted = rms.clone();
        java.util.Arrays.sort(sorted);
        float noise = sorted[sorted.length / 10];
        float thr = Math.max(noise * 4f, 200f);
        int start = -1, end = -1;
        for (int i = 0; i < rms.length; i++) if (rms[i] > thr) { if (start < 0) start = i; end = i; }
        return start < 0 ? null : new int[]{start, end};
    }

    /** One recording of the wake phrase. Returns false if no speech was heard. */
    public boolean addWakeSample(short[] audio) throws Exception {
        float[] rms = new float[audio.length / WakeWordEngine.CHUNK];
        float[][] f = frames(audio, rms);
        int[] span = speechSpan(rms);
        if (span == null || span[1] - span[0] < 2) return false;
        // Each frame hears the last 0.76 s, so the phrase shows up from its start to a few frames after its end.
        int from = Math.max(0, span[0] + 1), to = Math.min(f.length, span[1] + 4);
        examples.add(java.util.Arrays.copyOfRange(f, from, to));
        return true;
    }

    /** Normal talking or background sound, used to set how strict the match is. */
    public void addOtherSpeech(short[] audio) throws Exception { others.add(frames(audio, null)); }

    public int wakeSamples() { return examples.size(); }

    public TemplateMatcher build() { return TemplateMatcher.build(examples, others); }
}
