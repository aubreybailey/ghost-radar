package com.ghost.bleradar;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

/**
 * Records a session to Download/GhostRadar/survey-*.csv for offline mapping:
 * every advertisement from every device, every step attempt, ~5 Hz orientation,
 * and user marks. Raw sensor data rather than derived positions, so the path can
 * be re-integrated offline with different parameters.
 *
 * Columns: ms,event,addr,rssi,heading_deg,gx,gy,gz,tilt_swing_deg,accepted,info
 *   start      info = app version / phone model
 *   dev        addr; info = name|kind|txPower|connectable|payload hex (first sighting and payload changes)
 *   adv        addr, rssi
 *   pose       heading, gravity (world up in device coords), tilt swing
 *   step       heading/gravity/tilt swing at the step's own time, accepted (0/1); info = lag_ms=delivery delay
 *   mark       info = mark number (user tapped Mark)
 *   hunt_start / hunt_end / hunt_reset   addr
 *   est        addr; info = best_x;best_y;confidence;ambiguous (hunt-local frame)
 * Best-effort: any I/O failure just stops recording.
 */
class Recorder {
    static final int MAX_PAYLOADS_PER_DEV = 20;

    private BufferedWriter out;
    private long t0;
    final String name;
    int rows, marks;
    private final HashMap<String, String> lastPayload = new HashMap<>();
    private final HashMap<String, Integer> payloadCount = new HashMap<>();

    private Recorder(String name) { this.name = name; }

    boolean active() { return out != null; }

    static Recorder start(Context c, String info) {
        Recorder r = new Recorder("survey-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".csv");
        if (Build.VERSION.SDK_INT < 29) return r;
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, r.name);
            v.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
            v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/GhostRadar");
            Uri u = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) return r;
            r.out = new BufferedWriter(new OutputStreamWriter(c.getContentResolver().openOutputStream(u), "UTF-8"));
            r.t0 = SystemClock.elapsedRealtime();
            r.out.write("ms,event,addr,rssi,heading_deg,gx,gy,gz,tilt_swing_deg,accepted,info\n");
            r.event("start", "", info);
        } catch (Exception e) {
            r.out = null;
        }
        return r;
    }

    void event(String event, String addr, String info) {
        write(event, addr, "", "", null, "", "", info);
    }

    void adv(MainActivity.Dev d, int rssi, byte[] payload) {
        if (out == null) return;
        if (payload != null) {
            String hex = hex(payload);
            int n = payloadCount.containsKey(d.addr) ? payloadCount.get(d.addr) : 0;
            if (!hex.equals(lastPayload.get(d.addr)) && n < MAX_PAYLOADS_PER_DEV) {
                lastPayload.put(d.addr, hex);
                payloadCount.put(d.addr, n + 1);
                event("dev", d.addr, (d.name == null ? "" : d.name) + "|" + d.kind + "|"
                        + (d.txPower == Integer.MIN_VALUE ? "" : d.txPower) + "|" + (d.connectable ? 1 : 0) + "|" + hex);
            }
        }
        write("adv", d.addr, String.valueOf(rssi), "", null, "", "", "");
    }

    void pose(Pdr pdr) {
        float[] g = pdr.gravity();
        if (g == null) return;
        write("pose", "", "", deg(pdr), g, f0(pdr.tiltSwing()), "", "");
    }

    void step(Pdr.StepInfo s) {
        write("step", "", "", String.format(Locale.US, "%.1f", Math.toDegrees(s.headingRad)), s.gravity,
                f0(s.swingDeg), s.accepted ? "1" : "0", "lag_ms=" + s.lagMs);
    }

    void estimate(String addr, Locator loc) {
        if (!loc.valid) return;
        event("est", addr, String.format(Locale.US, "%.2f;%.2f;%.2f;%d",
                loc.bestX, loc.bestY, loc.confidence, loc.ambiguous ? 1 : 0));
    }

    int mark() {
        event("mark", "", String.valueOf(++marks));
        return marks;
    }

    void flush() {
        try { if (out != null) out.flush(); } catch (Exception e) { out = null; }
    }

    void stop() {
        try { if (out != null) { event("stop", "", ""); out.close(); } } catch (Exception ignored) { }
        out = null;
    }

    private void write(String event, String addr, String rssi, String heading, float[] g,
                       String swing, String accepted, String info) {
        if (out == null) return;
        try {
            StringBuilder b = new StringBuilder(96);
            b.append(SystemClock.elapsedRealtime() - t0).append(',').append(event).append(',')
                    .append(addr).append(',').append(rssi).append(',').append(heading).append(',');
            if (g != null) b.append(String.format(Locale.US, "%.3f,%.3f,%.3f", g[0], g[1], g[2]));
            else b.append(",,");
            b.append(',').append(swing).append(',').append(accepted).append(',').append(csv(info)).append('\n');
            out.write(b.toString());
            rows++;
        } catch (Exception e) {
            out = null;
        }
    }

    private static String deg(Pdr pdr) {
        return pdr.hasHeading ? String.format(Locale.US, "%.1f", Math.toDegrees(pdr.heading)) : "";
    }

    private static String f0(float v) { return String.format(Locale.US, "%.0f", v); }

    private static String csv(String s) {
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0) return s;
        return '"' + s.replace("\"", "\"\"").replace('\n', ' ') + '"';
    }

    private static String hex(byte[] a) {
        StringBuilder b = new StringBuilder(a.length * 2);
        for (byte x : a) b.append(String.format("%02x", x & 0xff));
        return b.toString();
    }
}
