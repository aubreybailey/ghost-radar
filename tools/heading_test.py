#!/usr/bin/env python3
"""Live check of the heading maths in Pdr.java, using Termux:API sensors.

Run, then: hold the phone like you do when hunting, face something, and turn
your whole body slowly to the RIGHT. The heading should go UP (clockwise).
"""
import json, math, subprocess, sys, time

SENSOR = "Game Rotation Vector  Non-wakeup"
DURATION = float(sys.argv[1]) if len(sys.argv) > 1 else 30


def matrix(qx, qy, qz, qw=None):
    """Same as SensorManager.getRotationMatrixFromVector (device -> world)."""
    if qw is None:
        qw = math.sqrt(max(0.0, 1 - qx * qx - qy * qy - qz * qz))
    return [
        1 - 2 * qy * qy - 2 * qz * qz, 2 * qx * qy - 2 * qz * qw, 2 * qx * qz + 2 * qy * qw,
        2 * qx * qy + 2 * qz * qw, 1 - 2 * qx * qx - 2 * qz * qz, 2 * qy * qz - 2 * qx * qw,
        2 * qx * qz - 2 * qy * qw, 2 * qy * qz + 2 * qx * qw, 1 - 2 * qx * qx - 2 * qy * qy,
    ]


proc = subprocess.Popen(["termux-sensor", "-s", SENSOR, "-d", "250"], stdout=subprocess.PIPE, text=True)
buf, start, last_print, h0 = "", time.time(), 0.0, None
try:
    for line in proc.stdout:
        buf += line
        try:
            obj = json.loads(buf)
        except json.JSONDecodeError:
            continue
        buf = ""
        vals = next(iter(obj.values()), {}).get("values")
        if not vals:
            continue
        R = matrix(*vals[:4])
        fx, fy = R[1] - R[2], R[4] - R[5]
        heading = math.degrees(math.atan2(fx, fy))
        if h0 is None:
            h0 = heading
        rel = (heading - h0 + 180) % 360 - 180
        tilt = math.degrees(math.acos(max(-1, min(1, R[8]))))  # 0 = flat, 90 = upright
        now = time.time()
        if now - last_print > 0.5:
            print(f"{now - start:5.1f}s  heading vs start {rel:+6.0f}°   tilt {tilt:3.0f}°", flush=True)
            last_print = now
        if now - start > DURATION:
            break
finally:
    proc.terminate()
    subprocess.run(["termux-sensor", "-c"], stdout=subprocess.DEVNULL)
