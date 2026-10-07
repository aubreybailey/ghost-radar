package com.ghost.bleradar;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Pedestrian dead reckoning: relative heading from the game rotation vector
 * (gyro-based, so indoor magnetic junk doesn't matter), steps from the step
 * detector or an accelerometer-peak fallback. Positions are metres from where
 * tracking started; heading is radians clockwise from the arbitrary +y axis.
 *
 * The step detector delivers in batches (about once a second on the Fairphone 6),
 * so each step is applied with the heading and tilt from its own sensor
 * timestamp, looked up in a short orientation history, not from delivery time.
 */
class Pdr implements SensorEventListener {
    static final float STEP_M = 0.6f; // a slow, careful step
    // Walking with the phone held out keeps its tilt steady even through turns;
    // waving it around (or lying in bed) doesn't. Steps taken while the tilt
    // swung more than this in the last STEADY_MS are not counted.
    static final float MAX_TILT_SWING_DEG = 20f;
    static final long STEADY_NS = 800_000_000L;
    static final long HISTORY_NS = 3_000_000_000L; // covers batching latency + STEADY_NS

    final SensorManager sm;
    float heading, x, y;
    int steps, rejectedSteps;
    boolean hasHeading;
    Runnable onStep;               // accepted steps only
    StepAttempt onStepAttempt;     // every step the detector reports, for recording

    interface StepAttempt { void on(StepInfo s); }

    /** What a single step-detector event was judged as. */
    static final class StepInfo {
        boolean accepted;
        float headingRad, swingDeg;
        float[] gravity;  // world up in device coordinates at the step
        long lagMs;       // delivery delay vs. when the step happened
    }

    /** Orientation sample, timestamped in the sensor clock (ns). */
    private static final class Orient {
        final long t;
        final float heading, gx, gy, gz;
        Orient(long t, float heading, float gx, float gy, float gz) {
            this.t = t; this.heading = heading; this.gx = gx; this.gy = gy; this.gz = gz;
        }
    }

    private final float[] rot = new float[9];
    private float accBase = SensorManager.GRAVITY_EARTH;
    private boolean accHigh;
    private long lastAccStepNs;
    private final ArrayDeque<Orient> history = new ArrayDeque<>();

    Pdr(Context c) { sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE); }

    void start(boolean allowStepDetector) {
        Sensor r = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (r == null) r = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (r != null) sm.registerListener(this, r, SensorManager.SENSOR_DELAY_GAME);
        Sensor st = allowStepDetector ? sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) : null;
        if (st == null) st = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (st != null) sm.registerListener(this, st, SensorManager.SENSOR_DELAY_GAME);
    }

    void stop() { sm.unregisterListener(this); }

    void reset() { x = y = 0; steps = rejectedSteps = 0; }

    @Override public void onSensorChanged(SensorEvent e) {
        switch (e.sensor.getType()) {
            case Sensor.TYPE_GAME_ROTATION_VECTOR:
            case Sensor.TYPE_ROTATION_VECTOR: {
                SensorManager.getRotationMatrixFromVector(rot, e.values);
                // "Forward" = horizontal part of the device's top edge (+Y) plus its
                // back (-Z): the first dominates when the phone is held flat, the
                // second when it's upright, and they agree in between.
                float fx = rot[1] - rot[2], fy = rot[4] - rot[5];
                if (fx * fx + fy * fy > 0.01f) {
                    heading = (float) Math.atan2(fx, fy);
                    hasHeading = true;
                }
                // World "up" seen from the device (bottom row of R) - independent of heading.
                history.addLast(new Orient(e.timestamp, heading, rot[6], rot[7], rot[8]));
                while (e.timestamp - history.peekFirst().t > HISTORY_NS) history.removeFirst();
                break;
            }
            case Sensor.TYPE_STEP_DETECTOR:
                step(e.timestamp);
                break;
            case Sensor.TYPE_ACCELEROMETER: {
                float[] v = e.values;
                float mag = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
                accBase += 0.02f * (mag - accBase);
                float dyn = mag - accBase;
                if (!accHigh && dyn > 1.2f && e.timestamp - lastAccStepNs > 350_000_000L) {
                    accHigh = true;
                    lastAccStepNs = e.timestamp;
                    step(e.timestamp);
                } else if (accHigh && dyn < 0.2f) {
                    accHigh = false;
                }
                break;
            }
        }
    }

    /** Latest world "up" in device coordinates {gx, gy, gz}, or null before the first reading. */
    float[] gravity() {
        Orient o = history.peekLast();
        return o == null ? null : new float[]{o.gx, o.gy, o.gz};
    }

    /** Tilt swing ending now. */
    float tiltSwing() {
        Orient o = history.peekLast();
        return o == null ? 0 : tiltSwingAt(o);
    }

    /** Orientation sample closest to sensor time t (null if no history). */
    private Orient at(long t) {
        Orient best = null;
        for (Orient o : history)
            if (best == null || Math.abs(o.t - t) < Math.abs(best.t - t)) best = o;
        return best;
    }

    /** Largest angle (degrees) between the tilt at ref and any tilt in the STEADY_NS before it. */
    private float tiltSwingAt(Orient ref) {
        float minDot = 1;
        for (Iterator<Orient> it = history.descendingIterator(); it.hasNext(); ) {
            Orient o = it.next();
            if (o.t > ref.t) continue;
            if (ref.t - o.t > STEADY_NS) break;
            minDot = Math.min(minDot, ref.gx * o.gx + ref.gy * o.gy + ref.gz * o.gz);
        }
        return (float) Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, minDot))));
    }

    private void step(long t) {
        Orient o = at(t);
        if (o == null) return;
        StepInfo s = new StepInfo();
        s.headingRad = o.heading;
        s.swingDeg = tiltSwingAt(o);
        s.gravity = new float[]{o.gx, o.gy, o.gz};
        s.accepted = s.swingDeg <= MAX_TILT_SWING_DEG;
        s.lagMs = (SystemClock.elapsedRealtimeNanos() - t) / 1_000_000L;
        if (onStepAttempt != null) onStepAttempt.on(s);
        if (!s.accepted) { rejectedSteps++; return; }
        x += STEP_M * (float) Math.sin(o.heading);
        y += STEP_M * (float) Math.cos(o.heading);
        steps++;
        if (onStep != null) onStep.run();
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }
}
