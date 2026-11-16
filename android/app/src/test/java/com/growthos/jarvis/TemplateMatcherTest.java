package com.growthos.jarvis;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;

public class TemplateMatcherTest {
    private static final Random R = new Random(1);

    /** A "phrase": a fixed path through feature space, with small per-take noise and speed changes. */
    private static float[][] take(float[][] path, int stretch, float noise) {
        List<float[]> out = new ArrayList<>();
        for (float[] p : path) for (int k = 0; k < stretch; k++) {
            float[] f = new float[TemplateMatcher.DIM];
            for (int i = 0; i < f.length; i++) f[i] = p[i] + (float) R.nextGaussian() * noise;
            out.add(f);
        }
        return out.toArray(new float[0][]);
    }

    private static float[][] randomPath(int n) {
        float[][] p = new float[n][TemplateMatcher.DIM];
        for (float[] r : p) for (int i = 0; i < r.length; i++) r[i] = (float) R.nextGaussian();
        return p;
    }

    @Test public void matchesSamePhraseNotOthers() {
        float[][] phrase = randomPath(6);
        List<float[][]> examples = new ArrayList<>();
        for (int i = 0; i < 6; i++) examples.add(take(phrase, 1 + i % 2, 0.2f));
        List<float[][]> others = new ArrayList<>();
        others.add(take(randomPath(30), 1, 0.2f));
        TemplateMatcher m = TemplateMatcher.build(examples, others);
        assertTrue(m.threshold > 0);
        // A new take of the phrase, at the end of some other audio, matches.
        float[][] lead = take(randomPath(5), 1, 0.2f), fresh = take(phrase, 2, 0.2f);
        float[][] stream = new float[lead.length + fresh.length][];
        System.arraycopy(lead, 0, stream, 0, lead.length);
        System.arraycopy(fresh, 0, stream, lead.length, fresh.length);
        assertTrue(m.distance(stream) <= m.threshold);
        // Other speech does not.
        assertTrue(m.distance(take(randomPath(12), 1, 0.2f)) > m.threshold);
    }

    @Test public void speechSpanFindsLoudPart() {
        float[] rms = {90, 95, 88, 92, 1500, 2000, 1800, 300, 90, 91, 89, 90};
        int[] s = VoiceTrainer.speechSpan(rms);
        assertNotNull(s);
        assertEquals(4, s[0]);
        assertEquals(6, s[1]);
        assertNull(VoiceTrainer.speechSpan(new float[]{90, 95, 88, 92, 91, 90, 89}));
    }
}
