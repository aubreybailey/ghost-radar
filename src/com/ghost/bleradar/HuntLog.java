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
import java.util.Locale;

/**
 * Records a hunt to Download/GhostRadar/*.csv so a walk can be replayed and
 * the dead reckoning / locator debugged offline. Best-effort: any I/O failure
 * just disables logging.
 */
class HuntLog {
    private BufferedWriter out;
    private long t0;

    static HuntLog open(Context c, String addr) {
        HuntLog log = new HuntLog();
        if (Build.VERSION.SDK_INT < 29) return log;
        try {
            String name = "hunt-" + addr.replace(":", "") + "-"
                    + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".csv";
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
            v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/GhostRadar");
            Uri u = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) return log;
            log.out = new BufferedWriter(new OutputStreamWriter(c.getContentResolver().openOutputStream(u), "UTF-8"));
            log.t0 = SystemClock.elapsedRealtime();
            log.out.write("ms,event,steps,x,y,heading_deg,rssi,best_x,best_y,confidence,ambiguous,rejected_steps,tilt_swing_deg\n");
        } catch (Exception e) {
            log.out = null;
        }
        return log;
    }

    void row(String event, Pdr pdr, int rssi, Locator loc) {
        if (out == null) return;
        try {
            out.write(String.format(Locale.US, "%d,%s,%d,%.2f,%.2f,%.1f,%s,%s,%s,%s,%s,%d,%.0f\n",
                    SystemClock.elapsedRealtime() - t0, event, pdr.steps, pdr.x, pdr.y,
                    Math.toDegrees(pdr.heading), rssi == Integer.MIN_VALUE ? "" : String.valueOf(rssi),
                    loc.valid ? String.format(Locale.US, "%.2f", loc.bestX) : "",
                    loc.valid ? String.format(Locale.US, "%.2f", loc.bestY) : "",
                    loc.valid ? String.format(Locale.US, "%.2f", loc.confidence) : "",
                    loc.valid ? (loc.ambiguous ? "1" : "0") : "",
                    pdr.rejectedSteps, pdr.tiltSwing()));
        } catch (Exception e) {
            out = null;
        }
    }

    void flush() {
        try { if (out != null) out.flush(); } catch (Exception e) { out = null; }
    }

    void close() {
        try { if (out != null) out.close(); } catch (Exception ignored) { }
        out = null;
    }
}
