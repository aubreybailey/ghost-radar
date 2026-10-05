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

    final SensorManager sm;
    float heading, x, y;
    int steps;
    boolean hasHeading;
    Runnable onStep;

    private final float[] rot = new float[9];
    private float accBase = SensorManager.GRAVITY_EARTH;
    private boolean accHigh;
    private long lastStepAt;

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

    void reset() { x = y = 0; steps = 0; }

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

    private void step() {
        if (!hasHeading) return;
        x += STEP_M * (float) Math.sin(heading);
        y += STEP_M * (float) Math.cos(heading);
        steps++;
        if (onStep != null) onStep.run();
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }
}
