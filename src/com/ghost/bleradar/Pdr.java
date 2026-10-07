package com.ghost.bleradar;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;

/**
 * Pedestrian dead reckoning: relative heading from the game rotation vector
 * (gyro-based, so indoor magnetic junk doesn't matter), steps from the step
 * detector or an accelerometer-peak fallback. Positions are metres from where
 * tracking started; heading is radians clockwise from the arbitrary +y axis.
 */
class Pdr implements SensorEventListener {
    static final float STEP_M = 0.6f; // a slow, careful step
    // Walking with the phone held out keeps its tilt steady even through turns;
    // waving it around (or lying in bed) doesn't. Steps taken while the tilt
    // swung more than this in the last STEADY_MS are not counted.
    static final float MAX_TILT_SWING_DEG = 20f;
    static final long STEADY_MS = 800;

    final SensorManager sm;
    float heading, x, y;
    int steps, rejectedSteps;
    boolean hasHeading;
    Runnable onStep;               // accepted steps only
    StepAttempt onStepAttempt;     // every step the detector reports, for recording

    interface StepAttempt { void on(boolean accepted, float tiltSwingDeg); }

    private final float[] rot = new float[9];
    private float accBase = SensorManager.GRAVITY_EARTH;
    private boolean accHigh;
    private long lastStepAt;
    /** Recent gravity directions in device coordinates: {t, gx, gy, gz}. */
    private final java.util.ArrayDeque<float[]> tilts = new java.util.ArrayDeque<>();

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
                long now = SystemClock.elapsedRealtime();
                tilts.addLast(new float[]{now, rot[6], rot[7], rot[8]});
                while (now - (long) tilts.peekFirst()[0] > STEADY_MS) tilts.removeFirst();
                break;
            }
            case Sensor.TYPE_STEP_DETECTOR:
                step();
                break;
            case Sensor.TYPE_ACCELEROMETER: {
                float[] v = e.values;
                float mag = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
                accBase += 0.02f * (mag - accBase);
                float dyn = mag - accBase;
                long now = SystemClock.elapsedRealtime();
                if (!accHigh && dyn > 1.2f && now - lastStepAt > 350) {
                    accHigh = true;
                    lastStepAt = now;
                    step();
                } else if (accHigh && dyn < 0.2f) {
                    accHigh = false;
                }
                break;
            }
        }
    }

    /** Latest world "up" in device coordinates {t, gx, gy, gz}, or null before the first reading. */
    float[] gravity() { return tilts.peekLast(); }

    /** Largest angle (degrees) between the current tilt and any tilt in the last STEADY_MS. */
    float tiltSwing() {
        float[] cur = tilts.peekLast();
        if (cur == null) return 0;
        float minDot = 1;
        for (float[] t : tilts) minDot = Math.min(minDot, cur[1] * t[1] + cur[2] * t[2] + cur[3] * t[3]);
        return (float) Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, minDot))));
    }

    private void step() {
        if (!hasHeading) return;
        float swing = tiltSwing();
        boolean ok = swing <= MAX_TILT_SWING_DEG;
        if (onStepAttempt != null) onStepAttempt.on(ok, swing);
        if (!ok) { rejectedSteps++; return; }
        x += STEP_M * (float) Math.sin(heading);
        y += STEP_M * (float) Math.cos(heading);
        steps++;
        if (onStep != null) onStep.run();
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }
}
