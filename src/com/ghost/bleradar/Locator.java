package com.ghost.bleradar;

import java.util.ArrayList;

/**
 * Guesses where a transmitter is from RSSI readings taken at dead-reckoned
 * positions. Readings are averaged per step, then every cell of a grid around
 * the walked path is scored against a log-distance path-loss model
 * (rssi = A - 10 n log10 d), with the unknown 1 m power A fitted per cell.
 * The result is a probability map, not a point: walking a straight line
 * leaves a left/right mirror ambiguity, which shows up as two blobs.
 */
class Locator {
    static final float N = 2.5f;              // path-loss exponent, indoors-ish
    static final float A_MIN = -75, A_MAX = -45; // plausible RSSI at 1 m
    static final float CELL = 0.5f, HALF = 12f;
    static final float NOISE_VAR = 16f, FLOOR_VAR = 25f; // per-sample noise, multipath floor (dB^2)
    static final int MAX_POINTS = 120;

    /** {x, y, rssiSum, count} per step. */
    final ArrayList<float[]> pts = new ArrayList<>();
    private int lastStep = -1;

    // Results of the last solve()
    boolean valid, ambiguous;
    float pathStd;          // how far the path spreads along its main axis (m)
    float bestX, bestY;     // most likely position
    float confidence;       // probability mass within 2 m of the best cell
    int gw;
    float gx0, gy0;
    float[] prob = new float[0];
    float maxProb;

    void reset() { pts.clear(); lastStep = -1; valid = false; }

    void add(int step, float x, float y, int rssi) {
        if (step != lastStep || pts.isEmpty()) {
            pts.add(new float[]{x, y, 0, 0});
            lastStep = step;
            if (pts.size() > MAX_POINTS) pts.remove(0);
        }
        float[] p = pts.get(pts.size() - 1);
        p[2] += rssi;
        p[3]++;
    }

    void solve() {
        valid = false;
        int n = pts.size();
        if (n < 4) { pathStd = 0; return; }

        // Shape of the walked path: principal axes of the point cloud.
        float cx = 0, cy = 0;
        for (float[] p : pts) { cx += p[0]; cy += p[1]; }
        cx /= n; cy /= n;
        float sxx = 0, syy = 0, sxy = 0;
        for (float[] p : pts) {
            float dx = p[0] - cx, dy = p[1] - cy;
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy;
        }
        sxx /= n; syy /= n; sxy /= n;
        float tr = sxx + syy, det = sxx * syy - sxy * sxy;
        float disc = (float) Math.sqrt(Math.max(0, tr * tr / 4 - det));
        float major = tr / 2 + disc, minor = Math.max(0, tr / 2 - disc);
        pathStd = (float) Math.sqrt(major);
        if (pathStd < 0.75f) return; // haven't moved enough to say anything
        ambiguous = Math.sqrt(minor) < 0.25f * pathStd;

        float[] r = new float[n], w = new float[n], px = new float[n], py = new float[n];
        for (int i = 0; i < n; i++) {
            float[] p = pts.get(i);
            px[i] = p[0]; py[i] = p[1];
            r[i] = p[2] / p[3];
            w[i] = 1f / (NOISE_VAR / p[3] + FLOOR_VAR);
        }

        gw = (int) (2 * HALF / CELL) + 1;
        gx0 = cx - HALF; gy0 = cy - HALF;
        if (prob.length != gw * gw) prob = new float[gw * gw];
        float[] loss = new float[n];
        float minChi = Float.MAX_VALUE;
        for (int gy = 0; gy < gw; gy++) {
            for (int gx = 0; gx < gw; gx++) {
                float x = gx0 + gx * CELL, y = gy0 + gy * CELL;
                float sw = 0, swa = 0;
                for (int i = 0; i < n; i++) {
                    float dx = x - px[i], dy = y - py[i];
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    loss[i] = 10 * N * (float) Math.log10(Math.max(d, 0.5f));
                    sw += w[i];
                    swa += w[i] * (r[i] + loss[i]);
                }
                float a = Math.min(A_MAX, Math.max(A_MIN, swa / sw));
                float chi = 0;
                for (int i = 0; i < n; i++) {
                    float e = r[i] + loss[i] - a;
                    chi += w[i] * e * e;
                }
                prob[gy * gw + gx] = chi;
                if (chi < minChi) minChi = chi;
            }
        }

        float sum = 0;
        int best = 0;
        for (int i = 0; i < prob.length; i++) {
            prob[i] = (float) Math.exp(-(prob[i] - minChi) / 2);
            sum += prob[i];
            if (prob[i] > prob[best]) best = i;
        }
        maxProb = prob[best] / sum;
        bestX = gx0 + (best % gw) * CELL;
        bestY = gy0 + (best / gw) * CELL;
        confidence = 0;
        for (int i = 0; i < prob.length; i++) {
            prob[i] /= sum;
            float dx = gx0 + (i % gw) * CELL - bestX, dy = gy0 + (i / gw) * CELL - bestY;
            if (dx * dx + dy * dy <= 4) confidence += prob[i];
        }
        valid = true;
    }
}
