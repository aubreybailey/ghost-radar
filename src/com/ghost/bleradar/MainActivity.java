package com.ghost.bleradar;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    // RSSI range mapped onto the cold..hot scale.
    static final float RSSI_COLD = -95f, RSSI_HOT = -40f;
    static final long STALE_MS = 30_000, LOST_MS = 6_000, HISTORY_MS = 60_000;

    static class Dev {
        String addr, name, kind = "";
        int txPower = Integer.MIN_VALUE;
        boolean connectable;
        float ema = Float.NaN;
        int lastRssi;
        long lastSeen;
        final ArrayDeque<long[]> samples = new ArrayDeque<>(); // {timeMs, rssi, emaRounded}
    }

    final HashMap<String, Dev> devs = new HashMap<>();
    final ArrayList<Dev> shown = new ArrayList<>();
    final Handler ui = new Handler(Looper.getMainLooper());
    SharedPreferences labels;

    BluetoothLeScanner scanner;
    boolean scanning, unknownOnly;
    String status = "";

    // List screen
    View listScreen;
    TextView header;
    DevAdapter adapter;

    // Hunt screen
    Dev target;
    View huntScreen;
    RadarView radar;
    boolean soundOn = true, vibrateOn = false;
    ToneGenerator tone;
    Vibrator vibrator;
    Pdr pdr;
    final Locator locator = new Locator();
    Recorder recorder;
    Button recBtn, markBtn;

    float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        labels = getSharedPreferences("labels", MODE_PRIVATE);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        pdr = new Pdr(this);
        pdr.onStep = () -> {
            locator.solve();
            if (recording() && target != null) recorder.estimate(target.addr, locator);
        };
        pdr.onStepAttempt = st -> { if (recording()) recorder.step(st); };
        try { tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 80); } catch (RuntimeException e) { tone = null; }
        buildListScreen();
        setContentView(listScreen);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (hasPerms()) startScan(); else requestPermissions(neededPerms(), 1);
        ui.post(refresher);
        if (target != null) ui.post(ticker);
        if (target != null || recording()) startTracking();
        if (recording()) ui.post(poseLogger);
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopScan();
        ui.removeCallbacks(refresher);
        ui.removeCallbacks(ticker);
        pdr.stop();
        ui.removeCallbacks(poseLogger);
        if (recording()) recorder.flush();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recording()) recorder.stop();
        if (tone != null) tone.release();
    }

    @Override
    public void onBackPressed() {
        if (target != null) exitHunt(); else super.onBackPressed();
    }

    // ---------------------------------------------------------------- permissions / scanning

    String[] neededPerms() {
        if (Build.VERSION.SDK_INT >= 31)
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION};
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    boolean hasPerms() {
        for (String p : neededPerms())
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == 2) { if (target != null) startTracking(); return; }
        if (hasPerms()) startScan();
        else status = "Permissions denied - grant Nearby devices + Location in app settings";
    }

    void startScan() {
        if (scanning) return;
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) { status = "Bluetooth is off - turn it on and reopen"; return; }
        scanner = ad.getBluetoothLeScanner();
        if (scanner == null) { status = "No BLE scanner available"; return; }
        ScanSettings s = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .setLegacy(false)
                .build();
        try {
            scanner.startScan(null, s, scanCb);
            scanning = true;
            status = "";
        } catch (SecurityException e) {
            status = "Scan not permitted: " + e.getMessage();
        }
    }

    void stopScan() {
        if (!scanning || scanner == null) return;
        try { scanner.stopScan(scanCb); } catch (Exception ignored) { }
        scanning = false;
    }

    final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) { onResult(r); }
        @Override public void onBatchScanResults(List<ScanResult> rs) { for (ScanResult r : rs) onResult(r); }
        @Override public void onScanFailed(int err) {
            scanning = false;
            status = "Scan failed (error " + err + ")" + (err == 6 ? " - scanning too often, wait 30s" : "");
        }
    };

    void onResult(ScanResult r) {
        String addr = r.getDevice().getAddress();
        Dev d = devs.get(addr);
        if (d == null) { d = new Dev(); d.addr = addr; devs.put(addr, d); }
        long now = SystemClock.elapsedRealtime();
        int rssi = r.getRssi();
        d.lastRssi = rssi;
        d.lastSeen = now;
        d.connectable = r.isConnectable();
        // Smooth more aggressively when samples arrive fast; RSSI is very noisy.
        d.ema = Float.isNaN(d.ema) ? rssi : d.ema + 0.25f * (rssi - d.ema);
        d.samples.addLast(new long[]{now, rssi, Math.round(d.ema * 10)});
        while (!d.samples.isEmpty() && now - d.samples.peekFirst()[0] > HISTORY_MS) d.samples.removeFirst();

        ScanRecord rec = r.getScanRecord();
        if (rec != null) {
            if (rec.getDeviceName() != null) d.name = rec.getDeviceName();
            if (rec.getTxPowerLevel() != Integer.MIN_VALUE) d.txPower = rec.getTxPowerLevel();
            String k = Ident.kind(rec);
            if (!k.isEmpty()) d.kind = k;
        }
        if (recording()) recorder.adv(d, rssi, rec == null ? null : trimZeros(rec.getBytes()));
        if (d == target) {
            locator.add(pdr.steps, pdr.x, pdr.y, rssi);
        }
    }

    // ---------------------------------------------------------------- shared helpers

    String label(Dev d) { return labels.getString(d.addr, null); }

    String title(Dev d) {
        String l = label(d);
        if (l != null) return "✓ " + l;
        if (d.name != null) return d.name;
        return d.kind.isEmpty() ? "Unknown device" : d.kind;
    }

    static float heat(float rssi) {
        float h = (rssi - RSSI_COLD) / (RSSI_HOT - RSSI_COLD);
        return h < 0 ? 0 : h > 1 ? 1 : h;
    }

    /** blue (cold) -> cyan -> green -> yellow -> red (hot) */
    static int heatColor(float h, int alpha) {
        return Color.HSVToColor(alpha, new float[]{230f * (1 - h), 0.9f, 1f});
    }

    /** Rough log-distance estimate; walls and bodies make this +/- a lot. */
    static float distanceM(Dev d) {
        float at1m = d.txPower != Integer.MIN_VALUE ? d.txPower - 41 : -59;
        return (float) Math.pow(10, (at1m - d.ema) / (10 * 2.5));
    }

    /** dB change: mean of last 2s minus mean of the 2-6s before. NaN if not enough data. */
    static float trend(Dev d, long now) {
        float a = 0, b = 0; int na = 0, nb = 0;
        for (long[] s : d.samples) {
            long age = now - s[0];
            if (age <= 2000) { a += s[1]; na++; }
            else if (age <= 6000) { b += s[1]; nb++; }
        }
        if (na < 2 || nb < 3) return Float.NaN;
        return a / na - b / nb;
    }

    void editLabel(final Dev d) {
        final EditText et = new EditText(this);
        et.setHint("e.g. Living room TV");
        String cur = label(d);
        if (cur != null) et.setText(cur);
        new AlertDialog.Builder(this)
                .setTitle("Label " + d.addr)
                .setMessage("Labeled devices are marked as known. Leave empty to clear.")
                .setView(et)
                .setPositiveButton("Save", (dlg, w) -> {
                    String v = et.getText().toString().trim();
                    if (v.isEmpty()) labels.edit().remove(d.addr).apply();
                    else labels.edit().putString(d.addr, v).apply();
                    refreshList();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    final Runnable refresher = new Runnable() {
        @Override public void run() {
            if (target == null) refreshList();
            else {
                locator.solve();
                if (recording()) recorder.estimate(target.addr, locator);
            }
            if (recording()) {
                recorder.flush();
                if (target == null) header.setText(String.format(Locale.US,
                        "\u25CF REC  %d devices \u00b7 %d rows \u00b7 mark %d", devs.size(), recorder.rows, recorder.marks));
            }
            ui.postDelayed(this, 1000);
        }
    };

    // ---------------------------------------------------------------- list screen

    void buildListScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF05080A);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) dp(12);
        bar.setPadding(pad, pad, pad, pad);
        header = new TextView(this);
        header.setTextColor(0xFF7CFFB2);
        header.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        header.setTextSize(15);
        bar.addView(header, new LinearLayout.LayoutParams(0, -2, 1));
        final Button filter = new Button(this);
        filter.setText("All");
        filter.setOnClickListener(v -> {
            unknownOnly = !unknownOnly;
            filter.setText(unknownOnly ? "Unknown" : "All");
            refreshList();
        });
        bar.addView(filter);
        recBtn = new Button(this);
        recBtn.setText("Rec");
        recBtn.setOnClickListener(v -> toggleRecording());
        bar.addView(recBtn);
        markBtn = new Button(this);
        markBtn.setText("Mark");
        markBtn.setVisibility(View.GONE);
        markBtn.setOnClickListener(v -> {
            int n = recorder.mark();
            if (vibrator != null) vibrator.vibrate(VibrationEffect.createOneShot(60, 200));
            android.widget.Toast.makeText(this, "Mark " + n, android.widget.Toast.LENGTH_SHORT).show();
        });
        bar.addView(markBtn);
        root.addView(bar);

        ListView lv = new ListView(this);
        adapter = new DevAdapter();
        lv.setAdapter(adapter);
        lv.setDividerHeight(0);
        lv.setOnItemClickListener((p, v, pos, id) -> enterHunt(shown.get(pos)));
        lv.setOnItemLongClickListener((p, v, pos, id) -> { editLabel(shown.get(pos)); return true; });
        root.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView hint = new TextView(this);
        hint.setText("Tap = hunt it down  ·  Long-press = label as known");
        hint.setTextColor(0xFF5A6B66);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(pad, pad / 2, pad, pad);
        root.addView(hint);
        listScreen = root;
    }

    void refreshList() {
        long now = SystemClock.elapsedRealtime();
        shown.clear();
        int unknown = 0;
        for (Iterator<Dev> it = devs.values().iterator(); it.hasNext(); ) {
            Dev d = it.next();
            if (now - d.lastSeen > 5 * 60_000) { it.remove(); continue; }
            boolean known = label(d) != null;
            if (!known) unknown++;
            if (unknownOnly && known) continue;
            shown.add(d);
        }
        // Live devices first, strongest signal first.
        Collections.sort(shown, (x, y) -> {
            boolean sx = now - x.lastSeen > STALE_MS, sy = now - y.lastSeen > STALE_MS;
            if (sx != sy) return sx ? 1 : -1;
            return Float.compare(y.ema, x.ema);
        });
        header.setText(status.isEmpty()
                ? String.format(Locale.US, "GHOST RADAR  %d devices · %d unknown%s", devs.size(), unknown, scanning ? "" : " · idle")
                : status);
        adapter.notifyDataSetChanged();
    }

    class DevAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View v, ViewGroup parent) {
            RowView rv = v instanceof RowView ? (RowView) v : new RowView(MainActivity.this);
            rv.dev = shown.get(i);
            rv.invalidate();
            return rv;
        }
    }

    class RowView extends View {
        Dev dev;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        RowView(Context c) {
            super(c);
            setLayoutParams(new ViewGroup.LayoutParams(-1, (int) dp(68)));
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), (int) dp(68));
        }

        @Override protected void onDraw(Canvas c) {
            if (dev == null) return;
            long now = SystemClock.elapsedRealtime();
            boolean stale = now - dev.lastSeen > STALE_MS;
            boolean known = label(dev) != null;
            float w = getWidth(), h = getHeight(), pad = dp(12);
            float ht = heat(dev.ema);

            p.setStyle(Paint.Style.FILL);
            p.setColor(stale ? 0x22FFFFFF : heatColor(ht, 70));
            c.drawRect(0, dp(3), w * Math.max(ht, 0.02f), h - dp(3), p);

            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(dp(16));
            p.setColor(stale ? 0xFF667070 : known ? 0xFF8FD9A8 : 0xFFFFFFFF);
            c.drawText(ellipsize(title(dev), w - dp(100)), pad, dp(28), p);

            p.setTypeface(Typeface.MONOSPACE);
            p.setTextSize(dp(12));
            p.setColor(0xFF8A9A95);
            String sub = dev.addr + (dev.kind.isEmpty() || dev.kind.equals(title(dev)) ? "" : "  " + dev.kind);
            c.drawText(ellipsize(sub, w - dp(100)), pad, dp(50), p);

            p.setTypeface(Typeface.MONOSPACE);
            p.setTextAlign(Paint.Align.RIGHT);
            p.setTextSize(dp(20));
            p.setColor(stale ? 0xFF667070 : heatColor(ht, 255));
            c.drawText(String.valueOf(Math.round(dev.ema)), w - pad, dp(30), p);
            p.setTextSize(dp(11));
            p.setColor(0xFF8A9A95);
            long ago = (now - dev.lastSeen) / 1000;
            c.drawText(ago < 2 ? "dBm · live" : "dBm · " + ago + "s ago", w - pad, dp(50), p);
            p.setTextAlign(Paint.Align.LEFT);
        }

        String ellipsize(String s, float max) {
            if (p.measureText(s) <= max) return s;
            while (s.length() > 1 && p.measureText(s + "…") > max) s = s.substring(0, s.length() - 1);
            return s + "…";
        }
    }

    // ---------------------------------------------------------------- hunt screen

    void enterHunt(Dev d) {
        target = d;
        if (recording()) recorder.event("hunt_start", d.addr, title(d));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        radar = new RadarView(this);
        root.addView(radar, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout btns = new LinearLayout(this);
        final Button snd = new Button(this), vib = new Button(this), rst = new Button(this),
                lbl = new Button(this), back = new Button(this);
        snd.setText(soundOn ? "Sound" : "Muted");
        snd.setOnClickListener(v -> { soundOn = !soundOn; snd.setText(soundOn ? "Sound" : "Muted"); });
        vib.setText(vibrateOn ? "Buzz" : "No buzz");
        vib.setOnClickListener(v -> { vibrateOn = !vibrateOn; vib.setText(vibrateOn ? "Buzz" : "No buzz"); });
        rst.setText("Reset");
        rst.setOnClickListener(v -> resetTrack());
        lbl.setText("Label");
        lbl.setOnClickListener(v -> editLabel(target));
        back.setText("Back");
        back.setOnClickListener(v -> exitHunt());
        for (Button bt : new Button[]{snd, vib, rst, lbl, back}) {
            bt.setAllCaps(false);
            btns.addView(bt, new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(btns);

        huntScreen = root;
        setContentView(huntScreen);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ui.removeCallbacks(ticker);
        ui.post(ticker);
        resetTrack();
        if (Build.VERSION.SDK_INT >= 29
                && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, 2);
        startTracking();
    }

    /** (Re)start motion sensors; the step detector needs ACTIVITY_RECOGNITION, else use the accelerometer. */
    void startTracking() {
        pdr.stop();
        pdr.start(Build.VERSION.SDK_INT < 29
                || checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED);
    }

    // ---------------------------------------------------------------- survey recording

    String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    boolean recording() { return recorder != null && recorder.active(); }

    void toggleRecording() {
        if (recording()) {
            recorder.stop();
            ui.removeCallbacks(poseLogger);
            if (target == null) pdr.stop();
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            new AlertDialog.Builder(this).setTitle("Recording saved")
                    .setMessage("Download/GhostRadar/" + recorder.name + "\n" + recorder.rows + " rows, " + recorder.marks + " marks")
                    .setPositiveButton("OK", null).show();
        } else {
            recorder = Recorder.start(this, "Ghost Radar " + appVersion() + " on " + Build.MANUFACTURER + " " + Build.MODEL);
            if (!recording()) { status = "Couldn't create recording in Downloads"; refreshList(); return; }
            pdr.reset();
            startTracking();
            ui.post(poseLogger);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        recBtn.setText(recording() ? "Stop" : "Rec");
        markBtn.setVisibility(recording() ? View.VISIBLE : View.GONE);
        refreshList();
    }

    /** Orientation at ~5 Hz while recording. */
    final Runnable poseLogger = new Runnable() {
        @Override public void run() {
            if (!recording()) return;
            recorder.pose(pdr);
            ui.postDelayed(this, 200);
        }
    };

    static byte[] trimZeros(byte[] a) {
        if (a == null) return null;
        int n = a.length;
        while (n > 0 && a[n - 1] == 0) n--;
        return java.util.Arrays.copyOf(a, n);
    }

    void resetTrack() {
        pdr.reset();
        locator.reset();
        if (recording() && target != null) recorder.event("hunt_reset", target.addr, "");
    }

    void exitHunt() {
        target = null;
        radar = null;
        if (recording()) recorder.event("hunt_end", "", "");
        else pdr.stop();
        ui.removeCallbacks(ticker);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(listScreen);
        refreshList();
    }

    /** Geiger-counter clicks: the hotter the signal, the faster it ticks. */
    final Runnable ticker = new Runnable() {
        @Override public void run() {
            Dev d = target;
            if (d == null) return;
            long now = SystemClock.elapsedRealtime();
            long next = 400;
            if (now - d.lastSeen < LOST_MS) {
                float h = heat(d.ema);
                next = (long) (1400 - 1330 * h);  // ~1.4s when cold, ~70ms when on top of it
                if (soundOn && tone != null) tone.startTone(ToneGenerator.TONE_PROP_BEEP, 20);
                if (vibrateOn && vibrator != null) vibrator.vibrate(VibrationEffect.createOneShot(15, 40 + (int) (200 * h)));
                if (radar != null) radar.pingAt = now;
            }
            ui.postDelayed(this, next);
        }
    };

    class RadarView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        long pingAt;
        float shownHeat = 0, shownRange = 6;

        RadarView(Context c) { super(c); }

        @Override protected void onDraw(Canvas c) {
            Dev d = target;
            if (d == null) return;
            long now = SystemClock.elapsedRealtime();
            boolean lost = now - d.lastSeen > LOST_MS;
            float w = getWidth(), h = getHeight();
            float cx = w / 2, cy = h * 0.42f, R = Math.min(w, h) * 0.38f;
            // Ease the displayed heat so the blob glides instead of jittering.
            shownHeat += (heat(d.ema) - shownHeat) * 0.08f;
            float ht = lost ? 0 : shownHeat;
            int col = lost ? 0xFF556060 : heatColor(ht, 255);

            // Rings
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(0x3366FFAA);
            for (int i = 1; i <= 4; i++) c.drawCircle(cx, cy, R * i / 4, p);
            c.drawLine(cx - R, cy, cx + R, cy, p);
            c.drawLine(cx, cy - R, cx, cy + R, p);

            // Sweep (purely for atmosphere)
            double ang = (now % 3000) / 3000.0 * Math.PI * 2;
            for (int i = 0; i < 24; i++) {
                double a = ang - i * 0.035;
                p.setColor(Color.argb(140 - i * 5, 80, 255, 160));
                p.setStrokeWidth(dp(2));
                c.drawLine(cx, cy, cx + (float) Math.cos(a) * R, cy + (float) Math.sin(a) * R, p);
            }

            // Once you've walked enough, the radar becomes a heading-up map.
            boolean mapped = locator.valid && !lost;
            if (mapped) drawMap(c, cx, cy, R);

            // Signal blob: grows and glows as you get hotter (small once the map takes over)
            float glow = R * (mapped ? 0.06f + 0.12f * ht : 0.15f + 0.8f * ht);
            p.setStyle(Paint.Style.FILL);
            for (int i = 6; i >= 1; i--) {
                p.setColor(heatColor(ht, lost ? 10 : 18 + i * 4));
                c.drawCircle(cx, cy, glow * (1 + i * 0.12f), p);
            }
            p.setColor(col);
            c.drawCircle(cx, cy, glow * 0.45f, p);

            // Ping ripple on every tick
            float pt = (now - pingAt) / 600f;
            if (pt < 1 && !lost) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(3) * (1 - pt));
                p.setColor(heatColor(ht, (int) (220 * (1 - pt))));
                c.drawCircle(cx, cy, glow * 0.45f + R * 0.6f * pt, p);
            }
            if (mapped) drawYou(c, cx, cy);

            // Text
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(dp(18));
            p.setColor(Color.WHITE);
            c.drawText(title(d), cx, dp(34), p);
            p.setTypeface(Typeface.MONOSPACE);
            p.setTextSize(dp(12));
            p.setColor(0xFF8A9A95);
            c.drawText(d.addr + (d.kind.isEmpty() ? "" : "  ·  " + d.kind), cx, dp(54), p);

            float ty = cy + R + dp(56);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(40));
            p.setColor(col);
            c.drawText(lost ? "SIGNAL LOST" : heatWord(ht), cx, ty, p);

            p.setTextSize(dp(22));
            String tr;
            int trCol;
            float t = trend(d, now);
            if (lost) {
                tr = "address may have rotated";
                trCol = 0xFF8A9A95;
                p.setTextSize(dp(15));
            } else if (Float.isNaN(t)) { tr = "listening…"; trCol = 0xFF8A9A95; }
            else if (t > 2) { tr = "▲ WARMER"; trCol = 0xFFFF6B3D; }
            else if (t < -2) { tr = "▼ COLDER"; trCol = 0xFF4DA3FF; }
            else { tr = "• steady"; trCol = 0xFFCCCCCC; }
            p.setColor(trCol);
            c.drawText(tr, cx, ty + dp(36), p);

            p.setTextSize(dp(14));
            p.setColor(0xFFB8C4C0);
            long ago = (now - d.lastSeen) / 1000;
            c.drawText(String.format(Locale.US, "%.0f dBm  ·  ~%.1f m  ·  seen %ds ago",
                    d.ema, distanceM(d), ago), cx, ty + dp(64), p);

            p.setTextSize(dp(15));
            p.setColor(0xFFD9B8FF);
            if (mapped) {
                float[] rel = relative(locator.bestX, locator.bestY);
                float dist = (float) Math.hypot(rel[0], rel[1]);
                c.drawText(String.format(Locale.US, "\uD83D\uDC7B ~%.0f m %s  \u00b7  %d%% sure", dist,
                        direction(rel[0], rel[1]), Math.round(100 * locator.confidence)), cx, ty + dp(90), p);
                if (locator.ambiguous) {
                    p.setTextSize(dp(12));
                    p.setColor(0xFF8A9A95);
                    c.drawText("left/right may be mirrored \u2014 turn 90\u00b0 and walk a few steps", cx, ty + dp(110), p);
                }
            } else if (!lost) {
                p.setColor(0xFF8A9A95);
                c.drawText(pdr.hasHeading
                        ? "walk slowly, phone held steady  (" + pdr.steps + " steps"
                                + (pdr.rejectedSteps > 0 ? ", " + pdr.rejectedSteps + " ignored)" : ")")
                        : "no motion sensors \u2014 warmer/colder only", cx, ty + dp(90), p);
            }
            p.setTextAlign(Paint.Align.LEFT);

            drawHistory(c, d, now, dp(16), h - dp(90), w - dp(32), dp(70));
            if (recording()) {
                p.setTextAlign(Paint.Align.LEFT);
                p.setTextSize(dp(12));
                p.setColor(0xFFFF4040);
                c.drawText("\u25CF REC", dp(10), dp(16), p);
            }
            postInvalidateOnAnimation();
        }

        /** World point -> {metres to your right, metres ahead}, using the current heading. */
        float[] relative(float wx, float wy) {
            float rx = wx - pdr.x, ry = wy - pdr.y;
            float cos = (float) Math.cos(pdr.heading), sin = (float) Math.sin(pdr.heading);
            return new float[]{rx * cos - ry * sin, rx * sin + ry * cos};
        }

        String direction(float right, float ahead) {
            double b = Math.toDegrees(Math.atan2(right, ahead)); // 0 = ahead, +90 = right
            String[] names = {"ahead", "ahead-right", "to your right", "behind-right",
                    "behind you", "behind-left", "to your left", "ahead-left"};
            return names[(int) Math.floorMod(Math.round(b / 45), 8)];
        }

        /** Heading-up map: probability heatmap, walked trail coloured by RSSI, ghost at the best guess. */
        void drawMap(Canvas c, float cx, float cy, float R) {
            Locator L = locator;
            float[] best = relative(L.bestX, L.bestY);
            float range = Math.max(4f, Math.min(15f, (float) Math.hypot(best[0], best[1]) * 1.4f));
            shownRange += (range - shownRange) * 0.05f; // ease the zoom
            float s = R / shownRange;

            c.save();
            path.reset();
            path.addCircle(cx, cy, R, Path.Direction.CW);
            c.clipPath(path);

            p.setStyle(Paint.Style.FILL);
            float cellR = Locator.CELL * s * 0.75f;
            for (int i = 0; i < L.prob.length; i++) {
                float q = L.prob[i] / L.maxProb;
                if (q < 0.05f) continue;
                float[] r = relative(L.gx0 + (i % L.gw) * Locator.CELL, L.gy0 + (i / L.gw) * Locator.CELL);
                p.setColor(Color.argb((int) (160 * q), 190, 110, 255));
                c.drawCircle(cx + r[0] * s, cy - r[1] * s, cellR, p);
            }
            for (float[] pt : L.pts) {
                float[] r = relative(pt[0], pt[1]);
                p.setColor(heatColor(heat(pt[2] / pt[3]), 230));
                c.drawCircle(cx + r[0] * s, cy - r[1] * s, dp(3), p);
            }
            c.restore();

            // Ring distances
            p.setTextSize(dp(10));
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(0xFF5A8A72);
            for (int i = 1; i <= 4; i++)
                c.drawText(String.format(Locale.US, "%.0fm", shownRange * i / 4), cx + dp(3), cy - R * i / 4 - dp(2), p);

            // Ghost at the best guess, pinned to the rim if it's off the map.
            float gx = best[0] * s, gy = -best[1] * s, gd = (float) Math.hypot(gx, gy);
            if (gd > R * 0.92f) { gx *= R * 0.92f / gd; gy *= R * 0.92f / gd; }
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(dp(20 + 16 * L.confidence));
            p.setColor(Color.WHITE);
            c.drawText("\uD83D\uDC7B", cx + gx, cy + gy + p.getTextSize() / 3, p);
        }

        /** You: an arrow at the centre pointing the way you're facing (always up). */
        void drawYou(Canvas c, float cx, float cy) {
            path.reset();
            path.moveTo(cx, cy - dp(11));
            path.lineTo(cx + dp(7), cy + dp(7));
            path.lineTo(cx, cy + dp(3));
            path.lineTo(cx - dp(7), cy + dp(7));
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            c.drawPath(path, p);
        }

        String heatWord(float h) {
            if (h > 0.85f) return "ON TOP OF IT";
            if (h > 0.68f) return "HOT";
            if (h > 0.5f) return "WARM";
            if (h > 0.32f) return "COOL";
            if (h > 0.15f) return "COLD";
            return "FREEZING";
        }

        /** Last 30s of smoothed RSSI as a sparkline, colored by heat. */
        void drawHistory(Canvas c, Dev d, long now, float x, float y, float w, float h) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(0x2266FFAA);
            c.drawRect(x, y, x + w, y + h, p);
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(dp(10));
            p.setColor(0xFF5A6B66);
            c.drawText("last 30s", x + dp(4), y - dp(4), p);

            path.reset();
            boolean first = true;
            float lastX = 0, lastY = 0;
            for (long[] s : d.samples) {
                long age = now - s[0];
                if (age > 30_000) continue;
                float px = x + w * (1 - age / 30_000f);
                float py = y + h * (1 - heat(s[2] / 10f));
                if (first) { path.moveTo(px, py); first = false; } else path.lineTo(px, py);
                lastX = px; lastY = py;
            }
            if (first) return;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(heatColor(heat(d.ema), 255));
            c.drawPath(path, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(lastX, lastY, dp(3), p);
        }
    }

    // ---------------------------------------------------------------- identification

    static class Ident {
        static final Map<Integer, String> COMPANIES = new HashMap<>();
        static final Map<Integer, String> SERVICES = new HashMap<>();
        static {
            COMPANIES.put(0x0006, "Microsoft");
            COMPANIES.put(0x004C, "Apple");
            COMPANIES.put(0x0075, "Samsung");
            COMPANIES.put(0x00E0, "Google");
            COMPANIES.put(0x0087, "Garmin");
            COMPANIES.put(0x0157, "Huami/Amazfit");
            COMPANIES.put(0x038F, "Xiaomi");
            COMPANIES.put(0x0059, "Nordic (generic chip)");
            COMPANIES.put(0x02E5, "Espressif (ESP32)");
            COMPANIES.put(0x000D, "Texas Instruments");
            COMPANIES.put(0x0171, "Amazon");
            COMPANIES.put(0x0499, "Ruuvi");

            SERVICES.put(0xFEED, "Tile tracker");
            SERVICES.put(0xFEEC, "Tile tracker");
            SERVICES.put(0xFD84, "Tile tracker");
            SERVICES.put(0xFD5A, "Samsung SmartTag");
            SERVICES.put(0xFE2C, "Google Fast Pair");
            SERVICES.put(0xFEAA, "Eddystone beacon");
            SERVICES.put(0xFD6F, "Exposure Notif.");
            SERVICES.put(0xFE9F, "Google");
            SERVICES.put(0xFE07, "Sonos");
            SERVICES.put(0xFEBE, "Bose");
            SERVICES.put(0xFE95, "Xiaomi");
            SERVICES.put(0x180D, "Heart-rate sensor");
            SERVICES.put(0x1812, "HID (keyboard/mouse)");
        }

        static int uuid16(ParcelUuid u) {
            String s = u.getUuid().toString();
            return s.endsWith("-0000-1000-8000-00805f9b34fb") ? Integer.parseInt(s.substring(4, 8), 16) : -1;
        }

        static String kind(ScanRecord rec) {
            // Service UUIDs are the most specific signal for trackers.
            List<ParcelUuid> uuids = rec.getServiceUuids();
            if (uuids != null) for (ParcelUuid u : uuids) {
                String s = SERVICES.get(uuid16(u));
                if (s != null) return s;
            }
            Map<ParcelUuid, byte[]> sd = rec.getServiceData();
            if (sd != null) for (ParcelUuid u : sd.keySet()) {
                String s = SERVICES.get(uuid16(u));
                if (s != null) return s;
            }
            SparseArray<byte[]> m = rec.getManufacturerSpecificData();
            if (m != null && m.size() > 0) {
                int id = m.keyAt(0);
                byte[] data = m.valueAt(0);
                if (id == 0x004C && data != null && data.length > 0) {
                    switch (data[0]) {
                        case 0x12: return "Apple Find My (AirTag / offline device)";
                        case 0x07: return "Apple AirPods/Beats";
                        case 0x02: return "iBeacon";
                        case 0x10: return "Apple device (iPhone/Mac/Watch)";
                        case 0x09: return "Apple AirPlay target";
                        case 0x0C: return "Apple Handoff";
                        default: return "Apple";
                    }
                }
                if (id == 0x0006) return "Microsoft (Windows PC / Swift Pair)";
                String c = COMPANIES.get(id);
                return c != null ? c : String.format(Locale.US, "Mfr 0x%04X", id);
            }
            return "";
        }
    }
}
