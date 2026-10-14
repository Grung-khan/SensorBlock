# SensorBlock

A personal, **root-free** Android app that blocks the device's **sensors**, **camera**
and **microphone** — for every app and for the system itself — through **Shizuku**
(ADB shell, uid 2000). Every block is a toggle, and turning a toggle off undoes
exactly what turning it on did.

> Target: **Android 13 (API 33)**. No root. The only elevation is Shizuku.

---

## What it is

SensorBlock reaches the lowest enforcement point available without root: the
system's `sensor_privacy` service, which only the `shell` uid is allowed to drive.
It exposes that as a small, honest UI:

- a **master kill switch** that blocks the camera, the microphone **and** every
  hardware sensor at once;
- a **Quick Settings tile** that toggles that same master block from the shade,
  without opening the app (with an in-app "Add to Quick Settings" prompt);
- independent **Camera** and **Microphone** switches;
- a **Sensors** tab that lists every sensor the device exposes;
- a per-app matrix (AppOps / `set-uid-state`) for camera, mic, body sensors and
  activity recognition;
- a **Diagnostics** tab with a live self-test that *attempts* to read each target
  to prove the block actually works.

## How it works

```
Kotlin app (Jetpack Compose, Material 3)
 └─ PolicyEngine  reconcile(desiredState) → diffs → verify
      └─ ShizukuBackend  (uid 2000)
           ├─ ISensorPrivacyManager  (all-sensor + camera/mic toggles)
           ├─ cmd sensorservice set-uid-state   (per-app)
           └─ appops set <pkg> ... ignore       (per-app)
```

The core contract is **`reconcile(desiredState)`**, never fire-and-forget:

1. read what the OS currently does (`ObservedState`);
2. compute the diff and issue only what changed;
3. re-read to verify, and surface anything that did not take.

Because every step is a diff against observed reality, enforcement is
**idempotent** (running it twice changes nothing) and **exactly reversible**
(turning a toggle off produces the inverse diff).

### The primitives

| Toggle | Android primitive | Scope |
|---|---|---|
| All sensors, camera, microphone | `ISensorPrivacyManager.setSensorPrivacy(true)` | device-wide |
| Camera | per-sensor privacy toggle (`Sensors.CAMERA`) | device-wide, all lenses |
| Microphone | per-sensor privacy toggle (`Sensors.MICROPHONE`) | device-wide |
| Per-app | `appops set <pkg> CAMERA\|RECORD_AUDIO\|BODY_SENSORS\|ACTIVITY_RECOGNITION ignore` | per-app |
| Per-app sensors | `cmd sensorservice set-uid-state <pkg> idle` | per-app |

The **master switch is a superset**: it drives the camera and microphone privacy
toggles as well as the all-sensor switch. On some OEM ROMs the all-sensor switch
does **not** cover the camera (verified on an OEM ROM), so the camera and
microphone toggles are engaged explicitly — one master toggle then blocks
everything, on every ROM.

### Persistence

Sensor-privacy state and AppOps are persisted by the OS (`/data/system/sensor_privacy*.xml`,
AppOps XML) and survive reboot. Volatile primitives (`set-uid-state`) are
re-asserted on boot / package replace / Shizuku (re)bind by a foreground service
and a `BOOT_COMPLETED` receiver.

## Hard limits (by design, not bugs)

- **No per-sensor control.** Android 13 exposes a per-sensor privacy toggle for the
  **camera and microphone only**. Every other sensor (accelerometer, gyroscope,
  magnetometer, proximity, light, …) can only be blocked **together** through the
  all-sensor switch. Per-sensor granularity needs root or a custom ROM.
- **All lenses together.** The camera is blocked as a whole; there is no per-lens
  switch.
- **No device-owner tricks.** `DevicePolicyManager` was evaluated and dropped: the
  `disable-camera` policy requires device/profile **owner** on API 31+, and device
  ownership is unreachable on a device with multiple users/accounts. SensorBlock
  is deliberately **Shizuku-only**.
- The UI never fakes granularity: grouped sensor rows say so explicitly.

## Requirements

- **Android 13 (API 33)** — behaviour is built and verified for it (min SDK 31).
- **[Shizuku](https://shizuku.rikka.app/)** installed, with its server started.
  - Start via ADB: `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`
  - Or on Android 11+, enable Wireless debugging and start it from the Shizuku app.
- **No root.**
- The app must be granted Shizuku permission on first launch.

## Build

The Gradle wrapper is committed; the toolchain is pinned:

- JDK 21, Gradle **8.11.1**, AGP **8.7.2**, Kotlin **2.0.20**
- `compileSdk 35`, `minSdk 31`, `targetSdk 33`

```sh
./gradlew :app:assembleDebug     # → app/build/outputs/apk/debug/app-debug.apk
```

Install:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds get an **`applicationIdSuffix = ".debug"`**, so the installed package
is **`com.sensorblock.app.debug`** (release: `com.sensorblock.app`).

A release build (`assembleRelease`) is not yet configured with a signing key —
debug builds only, for personal use.

### Debug harness

Debug builds include an adb-driven harness (`app/src/debug/**`, never shipped in
release) that runs the real `PolicyEngine` and self-test without the UI:

```sh
PKG=com.sensorblock.app.debug
D=$PKG/com.sensorblock.app.debug.DebugActivity
adb shell am start -n $D -a $PKG.CAPS                          # capabilities
adb shell am start -n $D -a $PKG.POLICY --ez blockAll true     # apply a policy
adb shell am start -n $D -a $PKG.OBSERVE                       # read it back
adb shell am start -n $D -a $PKG.SELFTEST                      # diagnostics
adb shell run-as $PKG cat files/debug.log                      # results
```

## Run

Start Shizuku, open SensorBlock, grant permission, and flip a switch.

## Project layout

```
app/src/main/
├─ java/com/sensorblock/app/
│  ├─ ui/        Compose UI (screens, components, theme), MainViewModel, AppActions
│  ├─ domain/    Models, PolicyEngine, PolicyStore, SensorInventory, AppInventory, SelfTest
│  ├─ backend/   PrivilegedBackend, ShizukuBackend, SensorPrivacyClient, ShellServiceClient
│  └─ service/   ReassertService (re-assert on boot / reconnect), BootReceiver
└─ aidl/         hand-mirrored sensor_privacy + Shizuku shell AIDL
app/src/debug/   adb debug harness (debug builds only)
```

See [`AGENTS.md`](AGENTS.md) for the full verified research, architecture and
decisions, and [`ROADMAP.md`](ROADMAP.md) for status and the verification matrix.

## Status

Working and verified on a physical Android 13 device:

- [x] All-sensor kill switch (hardware sensors + microphone silencing)
- [x] Camera privacy toggle
- [x] Microphone privacy toggle
- [x] Master switch blocks sensors **and** camera **and** microphone
- [x] Reversible / idempotent reconcile
- [x] Quick Settings master tile (toggle verified on device via `cmd statusbar click-tile`)
- [ ] Per-app AppOps / `set-uid-state` and reboot persistence (not yet verified)
- [ ] Release signing

## Acknowledgements

This app leans entirely on work done by others, and they deserve the credit:

- **opencode** — the open-source AI coding agent by **Anomaly**
  (github.com/anomalyco/opencode) that designed, wrote and reviewed most of the
  code here. Thank you to Anomaly and its ~950 contributors.
- **The model behind this app** — `opencode/big-pickle`, provided **free of cost**
  through opencode's model offering. The biggest thanks go to whoever builds and
  funds capable models that anyone can use for nothing.
- **Shizuku** by **Rikka** (github.com/RikkaApps/Shizuku) — the only non-root
  identity allowed to drive the sensor-privacy service; SensorBlock would not
  exist without it.
- **hiddenapibypass** by **LSPosed** — lets a normal app reach the mirrored
  `ISensorPrivacyManager` AIDL.

And to the maintainers of every dependency in `app/build.gradle.kts`.

## Disclaimer

Personal project. It changes device-wide privacy state; while enabled your device
loses auto-rotate, auto-brightness, proximity-during-call, step counting, camera
and microphone until you turn the switches back off. Provided as-is, with no
warranty. Not affiliated with Shizuku or any OEM.
