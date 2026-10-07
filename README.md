# Ghost Radar

A warmer/colder BLE "ghost detector" for Android. Lists nearby Bluetooth LE
devices (identifying AirTags, Tile, SmartTags, Fast Pair, etc. where possible),
lets you label the ones you recognise, and has a hunt mode that guides you to a
chosen device with a heat-coloured radar, ▲ WARMER / ▼ COLDER trend and
Geiger-counter clicks.

## Install

Add this repo's URL to [Obtainium](https://github.com/ImranR98/Obtainium) — it
tracks the GitHub releases and updates the app on every merge to `main`.
Or grab `ghost-radar.apk` from the latest [release](../../releases).

## Build

No Gradle — one Java file, built with the raw SDK tools:

```sh
# Termux
pkg install aapt aapt2 d8 apksigner openjdk-21
bash build.sh
```

On a machine with an Android SDK, `ANDROID_HOME` is picked up automatically.
`android.jar` is downloaded on first build if no SDK platform is found.

## CI / releases

`.github/workflows/build.yml` builds every PR (APK uploaded as a workflow
artifact). Every push to `main` publishes release `v1.0.<run number>` with the
APK, so main is always what's deployed.

Signing uses the `KEYSTORE_B64` secret (base64 of the keystore) and optional
`KEYSTORE_PASS` (default `android`), so CI builds upgrade in place over local
builds signed with the same key.

## Tips

- Move slowly; RSSI is noisy and smoothed, so the readout lags a second or two.
- Your body blocks 2.4 GHz — turn in a circle to find the strongest direction.
- Phones, watches and AirTags rotate their address every ~15 min. If the target
  goes "SIGNAL LOST", look for a new strong entry of the same type.

## Recording a survey (for mapping)

Tap **Rec** on the device list to log everything to
`Download/GhostRadar/survey-<time>.csv`: every advertisement from every device,
every step (kept or ignored), orientation at 5 Hz, and **Mark** taps. Hunts
started while recording are logged into the same file.

A good survey:

1. Hold the phone still in front of you for ~15 s (noise calibration).
2. Tap **Mark** at your starting spot.
3. Walk a slow loop through the room(s), phone held steady, back to the start.
4. Tap **Mark** again on the starting spot, then **Stop**.
