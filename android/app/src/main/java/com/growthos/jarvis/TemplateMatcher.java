package com.growthos.jarvis;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Personal wake phrase check by example: the user says the phrase a few times, and each
 * recording is kept as a short sequence of 96-number audio features (not audio). Later audio
 * matches when its newest features line up with one of those examples (dynamic time warping
 * with cosine distance). Works for any phrase and any accent. Pure Java, unit-tested.
 */
public final class TemplateMatcher {
    public static final int DIM = 96;
    final List<float[][]> templates;
    float threshold;

    TemplateMatcher(List<float[][]> templates, float threshold) { this.templates = templates; this.threshold = threshold; }

    /** Longest template, i.e. how many newest feature frames {@link #distance} needs. */
    public int span() { int m = 0; for (float[][] t : templates) m = Math.max(m, t.length); return m * 2; }

    static float cosDist(float[] a, float[] b) {
        double d = 0, na = 0, nb = 0;
        for (int i = 0; i < DIM; i++) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return (float) (1 - d / Math.sqrt(na * nb + 1e-9));
    }

    /**
     * Smallest average distance between any template and a stretch of {@code frames} that ends at
     * the newest frame (the start is free). Lower = more alike.
     */
    public float distance(float[][] frames) {
        // While listening this runs every 80 ms on the newest frames, and all but one of them were
        // already compared with every template row last time. Keep those distances (keyed by the
        // frame object, which never changes once made) and only compute the new frame's.
        // Same numbers as before, roughly span-times less work.
        IdentityHashMap<float[], float[]> next = new IdentityHashMap<>(frames.length * 2);
        float[][] cols = new float[frames.length][];
        for (int j = 0; j < frames.length; j++) {
            float[] col = next.get(frames[j]);
            if (col == null) col = known.get(frames[j]);
            if (col == null) col = column(frames[j]);
            next.put(frames[j], col);
            cols[j] = col;
        }
        known = next;
        float best = Float.MAX_VALUE;
        int offset = 0;
        for (float[][] t : templates) { best = Math.min(best, dtwEndAligned(t.length, cols, offset)); offset += t.length; }
        return best;
    }

    /** Distances from one frame to every row of every template, in template order. */
    private float[] column(float[] frame) {
        int rows = 0;
        for (float[][] t : templates) rows += t.length;
        float[] col = new float[rows];
        int k = 0;
        for (float[][] t : templates) for (float[] r : t) col[k++] = cosDist(r, frame);
        return col;
    }

    private IdentityHashMap<float[], float[]> known = new IdentityHashMap<>();
    private float[] costBuf = new float[0];
    private int[] lenBuf = new int[0];

    private float dtwEndAligned(int n, float[][] cols, int offset) {
        int m = cols.length;
        if (n == 0 || m == 0) return Float.MAX_VALUE;
        if (costBuf.length < n * m) { costBuf = new float[n * m]; lenBuf = new int[n * m]; }
        float[] cost = costBuf;
        int[] len = lenBuf;
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) {
            float c = cols[j][offset + i];
            int at = i * m + j;
            if (i == 0) { cost[at] = c; len[at] = 1; continue; } // template may start anywhere in x
            float best = Float.MAX_VALUE; int bl = 1;
            if (j > 0 && cost[at - m - 1] < best) { best = cost[at - m - 1]; bl = len[at - m - 1]; }
            if (cost[at - m] < best) { best = cost[at - m]; bl = len[at - m]; }
            if (j > 0 && cost[at - 1] < best && len[at - 1] < 2 * n) { best = cost[at - 1]; bl = len[at - 1]; }
            cost[at] = best + c; len[at] = bl + 1;
        }
        int last = (n - 1) * m + m - 1;
        return cost[last] / len[last];
    }

    /** The plain version of the comparison above, kept so tests can check both agree. */
    static float dtwEndAligned(float[][] t, float[][] x) {
        int n = t.length, m = x.length;
        if (n == 0 || m == 0) return Float.MAX_VALUE;
        float[][] cost = new float[n][m];
        int[][] len = new int[n][m];
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) {
            float c = cosDist(t[i], x[j]);
            if (i == 0) { cost[i][j] = c; len[i][j] = 1; continue; } // template may start anywhere in x
            float best = Float.MAX_VALUE; int bl = 1;
            if (j > 0 && cost[i - 1][j - 1] < best) { best = cost[i - 1][j - 1]; bl = len[i - 1][j - 1]; }
            if (cost[i - 1][j] < best) { best = cost[i - 1][j]; bl = len[i - 1][j]; }
            if (j > 0 && cost[i][j - 1] < best && len[i][j - 1] < 2 * n) { best = cost[i][j - 1]; bl = len[i][j - 1]; }
            cost[i][j] = best + c; len[i][j] = bl + 1;
        }
        return cost[n - 1][m - 1] / len[n - 1][m - 1];
    }

    /**
     * Builds the matcher and picks a threshold between how close the examples are to each other
     * and how close other speech gets. {@code others} are feature sequences of non-wake audio.
     */
    public static TemplateMatcher build(List<float[][]> examples, List<float[][]> others) {
        if (examples.size() < 2) throw new IllegalArgumentException("need at least 2 examples");
        TemplateMatcher all = new TemplateMatcher(examples, 0);
        // How far each example is from the rest (leave one out).
        List<Float> pos = new ArrayList<>();
        for (int k = 0; k < examples.size(); k++) {
            List<float[][]> rest = new ArrayList<>(examples); float[][] me = rest.remove(k);
            pos.add(new TemplateMatcher(rest, 0).distance(me));
        }
        // How close other speech gets, at every position.
        float negMin = Float.MAX_VALUE;
        int window = all.span();
        for (float[][] seq : others)
            for (int end = 4; end <= seq.length; end++) {
                int start = Math.max(0, end - window);
                float[][] sub = java.util.Arrays.copyOfRange(seq, start, end);
                negMin = Math.min(negMin, all.distance(sub));
            }
        java.util.Collections.sort(pos);
        float posHigh = pos.get(Math.min(pos.size() - 1, (int) Math.ceil(pos.size() * 0.8) - 1));
        float thr = negMin == Float.MAX_VALUE ? posHigh * 1.15f : Math.min(posHigh * 1.15f, (posHigh + negMin) / 2f);
        return new TemplateMatcher(examples, thr);
    }

    public void save(File f) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(f))) {
            out.writeInt(2); out.writeFloat(threshold); out.writeInt(templates.size());
            for (float[][] t : templates) { out.writeInt(t.length); for (float[] r : t) for (float v : r) out.writeFloat(v); }
        }
    }

    public static TemplateMatcher load(File f) {
        if (f == null || !f.exists()) return null;
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            if (in.readInt() != 2) return null;
            float thr = in.readFloat(); int n = in.readInt();
            List<float[][]> ts = new ArrayList<>();
            for (int k = 0; k < n; k++) { int len = in.readInt(); float[][] t = new float[len][DIM]; for (float[] r : t) for (int i = 0; i < DIM; i++) r[i] = in.readFloat(); ts.add(t); }
            return new TemplateMatcher(ts, thr);
        } catch (IOException e) { return null; }
    }

    static File file(android.content.Context c) { return new File(c.getFilesDir(), "wake_templates.bin"); }
}
