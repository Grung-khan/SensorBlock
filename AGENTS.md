# SensorBlock — Agent & Developer Guide

> Personal-use, privacy-first Android app that blocks sensors, camera and microphone
> **at the lowest level reachable without root**, with fully reversible toggles.
> Target device: **Android 13 (API 33)**, non-rooted.

This file is the single source of truth for agents and humans working in this repo.
Read it fully before changing code. It records **verified AOSP behaviour**, the
**hard limits** of a non-root blocker, the chosen architecture, and open decisions.

---

## 1. Goal

- Block **every sensor** the device exposes, plus **camera (all lenses)** and
  **microphone**, for **all apps and system services** — ideally unbypassable.
- **No root.** Device-owner privileges and ADB-based setup are allowed.
- Every block is **reversible** through a toggle; turning a toggle off must undo
  exactly what turning it on did (idempotent `apply(desiredState)` model, not
  fire-and-forget).
- Personal app, but UI must be **beautiful, clear, and obvious** (Compose + Material 3).
- Must work on **Android 13**.

## 2. Hard constraints & environment

- **No root.** This is non-negotiable.
- Allowed elevation: **Shizuku** (ADB shell uid 2000), **Dhizuku** (device owner
  delegation), **`dpm set-device-owner`** via ADB.
- Machine toolchain (verified):
  - JDK 21 (`/usr/lib/jvm/java-21-openjdk-amd64`)
  - Gradle 8.11.1 (cached dist: `~/.gradle/wrapper/dists/gradle-8.11.1-all/*/gradle-8.11.1/bin/gradle`)
  - AGP 8.7.2, Kotlin 2.0.20, `androidx.core:core-ktx:1.13.1` already in `~/.gradle/caches`
  - Android SDK: platforms `android-35`, `android-36`; build-tools `34.0.0`, `35.0.0`
  - NDK `27.3.13750724`; Rust 1.97.1 with target `aarch64-linux-android` installed
  - `cargo-ndk` is **not installed** — the `buildRustNative` Gradle task (§6.1) will
    fail until `cargo install cargo-ndk` is run and the crate exists.
  - `adb` at `~/Android/Sdk/platform-tools/adb`
- Internet access available (Maven/Google repos reachable).

---

## 3. VERIFIED RESEARCH — how blocking actually works on Android 13 (no root)

All findings below were confirmed by reading AOSP `android13-release` sources
(see §9 References for exact files).

### 3.1 The `sensor_privacy` service (`SensorPrivacyService`)

A system service (`android.hardware.SensorPrivacyManager` → Binder interface
`ISensorPrivacyManager`) owns the device's sensor-privacy state. It has **two
independent mechanisms**:

1. **All-sensor privacy** — a single global boolean (`setSensorPrivacy(boolean)`).
   - Persisted **device-wide** in `/data/system/sensor_privacy.xml`
     (tag `all-sensor-privacy`, attribute `enabled`). Survives reboot.
   - When enabled, `SensorService` calls `disableAllSensors()` natively → **every
     physical sensor stops delivering data to every app** (accelerometer, gyro,
     magnetometer, proximity, light, pressure, humidity, step counter, etc.).
   - **It also blocks the camera**: `CameraService.cpp:1292-1298` refuses camera
     access with *"cannot use the camera when sensor privacy is enabled"*
     whenever `SensorPrivacyPolicy::isSensorPrivacyEnabled()` (the all-sensor
     state) is true.
   - **It also silences all microphone recordings**:
     `AudioPolicyService::SensorPrivacyPolicy::registerSelf()` subscribes with the
     **all-sensor** listener and `updateUidStates()` calls
     `silenceAllRecordings_l()` — every non-virtual `AudioRecord` client is forced
     to `APP_STATE_IDLE` (`AudioPolicyService.cpp:858-862, 1101-1108, 1802-1822`).
     (Telephony during an active call is unaffected — it does not route through
     `AudioPolicyService`.)
   - **Net effect: the all-sensor switch is a true universal kill switch —
     all physical sensors + camera + microphone.**
2. **Per-sensor privacy toggles** — currently only **`MICROPHONE`** and **`CAMERA`**
   exist. Per-user, persisted via `PersistedState` (`/data/system/sensor_privacy_persisted.xml`).
   - `CAMERA` toggle → blocks camera only.
   - `MICROPHONE` toggle → AudioService mutes the mic
     (`mMicMuteFromPrivacyToggle` → `setMicrophoneMuteNoCallerCheck`).

Permission gate: `android.permission.MANAGE_SENSOR_PRIVACY`, protection level
`internal|role|installer`. A normal app cannot be granted it, **but `shell`
(uid 2000) holds it** (declared in `packages/Shell/AndroidManifest.xml`) → Shizuku
can drive it directly.

Shell entry points (verified in `SensorPrivacyService.onShellCommand`):

```sh
# gated on MANAGE_SENSOR_PRIVACY; arg order is USER_ID then SENSOR
adb shell cmd sensor_privacy enable  0 microphone   # mic privacy ON  (block)
adb shell cmd sensor_privacy enable  0 camera       # camera privacy ON (block)
adb shell cmd sensor_privacy disable 0 microphone   # mic privacy OFF (allow)

# all-sensor privacy (transaction #9 in ISensorPrivacyManager.aidl) — the nuclear switch
adb shell service call sensor_privacy 9 i32 1       # all sensors + camera OFF
adb shell service call sensor_privacy 9 i32 0       # restore
```

> `cmd sensor_privacy` only accepts `microphone`/`camera`; there is **no** shell
> command for the all-sensor switch — use the Binder transaction (#9) or, from
> Shizuku, the `ISensorPrivacyManager.setSensorPrivacy` AIDL method directly.
> Do **not** hard-code transaction #9 blindly: resolve it from the AIDL, since
> the index can shift across OS versions.

### 3.2 `SensorService` (`sensorservice`) shell commands

`SensorService::shellCommand` (gated on `android.permission.MANAGE_SENSORS`,
also held by shell) supports **per-UID** control:

```sh
adb shell cmd sensorservice set-uid-state   <package> idle    # kill sensors for that app
adb shell cmd sensorservice set-uid-state   <package> active  # restore
adb shell cmd sensorservice reset-uid-state <package>
adb shell cmd sensorservice restrict-ht / unrestrict-ht      # head-tracker only
```

`dumpsys sensorservice` (needs `DUMP`, held by shell) additionally supports
`restrict <pkg>` (a volatile CTS allowlist mode: all sensors off for everyone
except one package) and `enable`. These are **not persisted**.

### 3.3 Device-owner APIs (persistent, enforced in system services)

Reachable via Dhizuku (delegated) or by installing our own app as device owner
with `adb shell dpm set-device-owner <pkg>/<receiver>` (device must have **no**
accounts / not be provisioned):

- `DevicePolicyManager.setCameraDisabled(admin, true)` → hard camera block
  (deprecated but functional on 13).
- `DevicePolicyManager.addUserRestriction(admin, UserManager.DISALLOW_CAMERA)`
  (`"no_camera"`) → hard camera restriction.
- `DevicePolicyManager.addUserRestriction(admin, UserManager.DISALLOW_UNMUTE_MICROPHONE)`
  (`"no_unmute_microphone"`) → forces the microphone muted.
- `DevicePolicyManager.setScreenCaptureDisabled(...)`, app suspension, etc.

**A device owner does NOT get `MANAGE_SENSOR_PRIVACY`**, so the all-sensor switch
must be driven through **Shizuku** (or the shell uid), not through DPM.

### 3.4 Per-app AppOps (Shizuku / shell)

```sh
adb shell appops set <pkg> CAMERA             ignore   # 24 camera
adb shell appops set <pkg> RECORD_AUDIO       ignore   # 27 mic
adb shell appops set <pkg> BODY_SENSORS       ignore   # 92 body sensors
adb shell appops set <pkg> ACTIVITY_RECOGNITION ignore # 104 step/activity
adb shell appops set <pkg> FINE_LOCATION      ignore   # GNSS
```
Grants/what's reachable depends on `MANAGE_APP_OPS_MODES` (shell has broad access
via `appops` command; verify on device). Reversible with `default`/`allow`.

### 3.5 THE HARD LIMIT — individual non-privacy sensors are impossible without root

- Android exposes per-sensor privacy **only** for `MICROPHONE` and `CAMERA`.
  Verified in `SensorPrivacyManager.Sensors` on **android13, android14, android15
  and android16** — still only those two.
- There is **no** framework/hidden-API/ADB mechanism to disable a *single*
  non-privacy sensor (e.g. only the accelerometer) device-wide. The only
  device-wide primitive is the **all-sensor** switch, which is all-or-nothing.
- There is **no** per-lens (front vs back) camera blocking: `setCameraDisabled`,
  `DISALLOW_CAMERA` and the camera privacy toggle all act on the whole camera
  subsystem. (Blocking "front and back" = blocking the camera, which we do.)
- Individual-sensor granularity therefore **requires root** (sysfs/HAL) or a
  custom ROM. This is documented, not a bug in our app.

**Consequence for the UI model (see §5):** each non-privacy sensor row's "off"
state collapses to the all-sensor kill switch; only Camera and Microphone have
true independent controls.

### 3.6 Persistence summary

| Primitive | Storage | Survives reboot? | Survives Shizuku restart? |
|---|---|---|---|
| all-sensor privacy | `/data/system/sensor_privacy.xml` | yes | yes |
| camera/mic privacy | `sensor_privacy_persisted.xml` | yes | yes |
| device-owner restrictions | system DB | yes | yes |
| appops | appops XML | yes | yes |
| `sensorservice set-uid-state` | in-memory | no | no |
| `dumpsys sensorservice restrict` | in-memory | no | no |

Because most state persists and the source of truth is the OS, the app can be
**stateless-ish**: it stores the user's *desired* policy and re-asserts it on
launch/boot/reconnect, rather than relying on a resident daemon. A foreground
service + `BOOT_COMPLETED` receiver should still re-assert volatile primitives.

---

## 4. Enforcement capability matrix (what we can build)

| Toggle (UI) | Backend primitive(s) | Scope | Independent? |
|---|---|---|---|
| **Master: block all sensors** | `ISensorPrivacyManager.setSensorPrivacy(true)` (all) | device-wide; **all sensors + camera + mic (records silenced)** | yes |
| **Camera** | camera privacy toggle; **+** device-owner `setCameraDisabled` / `DISALLOW_CAMERA`; **+** optional per-app `CAMERA ignore` | device-wide / per-app | yes |
| **Microphone** | mic privacy toggle; **+** `DISALLOW_UNMUTE_MICROPHONE` | device-wide | yes |
| Accelerometer / Gyro / Magnetometer / Proximity / Light / Pressure / Humidity / Step / etc. | **only** the all-sensor switch (group block) | device-wide, all together | **no** |
| GNSS / location | `appops FINE_LOCATION ignore` (per-app) | per-app only | per-app |
| Per-app (any sensor) | `cmd sensorservice set-uid-state <pkg> idle` | per-app, all sensors | per-app |
| Per-app camera/mic/body/activity | appops | per-app | per-app |

Layering rule: for a given target, apply **every** available primitive that does
not conflict (e.g. camera = privacy toggle + DPM restriction + appops), so the
block survives if one layer is bypassed. State is tracked per-layer so each can
be undone independently.

---

## 5. Chosen architecture

### 5.1 Shape

```
Kotlin app (Jetpack Compose, Material 3)
 ├─ UI layer: sensor inventory + toggles + per-app matrix + status/diagnostics
 ├─ Domain layer:
 │    PolicyEngine       – desired state → set of primitives (idempotent reconcile)
 │    SensorInventory    – enumerates sensors via SensorManager
 │    BlockTarget        – CAMERA | MICROPHONE | ALL_SENSORS | SENSOR(type) | APP(pkg)
 ├─ Backend layer (interchangeable, capability-detected):
 │    PrivilegedBackend (interface)
 │      ├─ ShizukuBackend      – uid 2000; SensorPrivacy AIDL, `cmd`, appops, dumpsys
 │      ├─ DeviceOwnerBackend  – Dhizuku-delegated or own-app DPM (camera/mic hard)
 │      └─ NoBackend           – degrades to per-app appops via UI + instructions
 └─ (optional) Rust native module `libsensorblock.so` for self-test/telemetry
```

Key design rules:

- **Reversible & idempotent.** Everything is `reconcile(desiredState)`:
  compare desired vs *observed* OS state, issue only the diffs. A toggle-off
  reverses exactly its own primitive set.
- **Capability detection at runtime.** Detect Shizuku availability/version,
  device-owner status, and `supportsSensorToggle(MICROPHONE|CAMERA)`; hide or
  badge controls that the device/backends cannot honour.
- **Honesty in UI.** Non-privacy sensor rows are grouped and explicitly labelled
  that turning one off engages the device-wide all-sensor block (they cannot be
  blocked individually without root). No fake granularity.
- **No resident daemon required**, but a foreground service re-asserts volatile
  primitives on `BOOT_COMPLETED`, Shizuku (re)bind, and app resume. Respect
  battery/Doze.

### 5.2 Privileged backends

- **Shizuku** (`dev.rikka.shizuku:api` + `:provider`): the primary backend.
  - Binder calls into `sensor_privacy` via the AIDL interface resolved at runtime
    (`ServiceManager.getService("sensor_privacy")`, then the generated
    `ISensorPrivacyManager` stub) — avoid raw transaction indices.
  - Shell commands through Shizuku's process API (`newProcess`).
  - Needs (re)activation after reboot; detect and prompt; support the
    wireless-debugging auto-start path on Android 11+.
- **Device owner** (Dhizuku delegation *or* SensorBlock itself as device owner):
  used for the **hard** camera/mic restrictions that survive independently of
  Shizuku. Dhizuku lets a device owner be configured once without a factory
  reset; installing SensorBlock directly as device owner via ADB is the cleanest
  for a personal device.
- **NoBackend** fallback guides the user through ADB setup and still exposes the
  per-app appops controls the UI can trigger via a one-shot ADB pairing.

### 5.3 Current layout (scaffold exists; verify before trusting)

```
SensorBlock/
├─ AGENTS.md
├─ settings.gradle.kts          (single module `:app`; repos pinned in pluginManagement)
├─ build.gradle.kts             (plugin versions only, `apply false`)
├─ gradle.properties            (config-cache OFF; buildconfig OFF)
├─ gradlew / gradle/wrapper/    (Gradle 8.11.1)
├─ app/
│   ├─ build.gradle.kts
│   └─ src/main/
│      ├─ AndroidManifest.xml
│      ├─ aidl/                 (hand-mirrored AIDL — see §6.1 gotcha #1)
│      │   ├─ android/hardware/ISensorPrivacyManager.aidl, ISensorPrivacyListener.aidl
│      │   └─ com/sensorblock/app/shell/IShellService.aidl
│      ├─ java/com/sensorblock/app/
│      │   ├─ MainActivity.kt, SensorBlockApp.kt
│      │   ├─ ui/               (MainViewModel, AppActions, screens/, components/, theme/)
│      │   ├─ domain/           (Models, PolicyEngine, PolicyStore, SensorInventory, AppInventory, SelfTest)
│      │   ├─ backend/          (PrivilegedBackend, ShizukuBackend, SensorPrivacyClient,
│      │   │                     DevicePolicyBackend, ShellServiceClient, ShellUserService)
│      │   ├─ admin/            (SensorBlockDeviceAdminReceiver)
│      │   └─ service/          (ReassertService, BootReceiver)
│      └─ res/
└─ crates/                      (NOT created yet — the `buildRustNative` task in
                                 app/build.gradle.kts points here but the dirs/crates are missing)
```
Package id: **`com.sensorblock.app`** (confirmed). Display name: **SensorBlock**.
No README, no `opencode.json`, no tests, no CI exist yet.

### 5.4 UI (Compose, Material 3)

- **Home / Status**: backend status cards (Shizuku ✓/✗, Device owner ✓/✗), one big
  **Master kill switch**, and the two independent **Camera** / **Microphone**
  toggles with prominent state.
- **Sensors**: list every sensor detected (name, vendor, type, max range, current
  state). Group: *Independent* (Camera, Microphone) vs *Grouped hardware sensors*
  (everything else). Each grouped row has an individual toggle that maps to the
  all-sensor block, with an inline explanation and a "why" info sheet.
- **Apps** (advanced): per-app matrix for camera / mic / sensors / body /
  activity, backed by appops + `set-uid-state`.
- **Diagnostics**: live self-test ("attempt to read each sensor", "attempt to open
  camera/mic") proving the block works; exportable log.
- Design: Material 3 dynamic color, dark/light, large touch targets, clear
  warning copy for irreversible-ish or device-wide actions, confirm dialogs for
  device-wide blocks.

### 5.5 Rust (confirmed: provisioning CLI + native self-test)

The NDK target `aarch64-linux-android` and NDK 27 are installed. Rust is **not
required** for enforcement (all enforcement is Binder/shell based). It is used for:

1. A **host-side provisioning CLI** (`sensorblockctl`, crate `crates/sensorblockctl`)
   that drives `adb`: install the APK, set device owner, start/verify Shizuku,
   apply and verify each block by attempting a sensor read, and dump device state.
2. An in-app native `.so` (`crates/sensorblock-native`) implementing a
   **self-test / verification** engine using the NDK `ASensorManager` API to prove
   sensors return no data when blocked.
3. Direct Binder calls from native code — **not** used; prefer Shizuku's Java AIDL
   path (more robust across OS versions).

---

## 6. Build, install, provision

### 6.1 Build

The wrapper is committed and Gradle 8.11.1 is cached, so:

```sh
./gradlew :app:assembleDebug
```

(If the wrapper is ever lost, `~/.gradle/wrapper/dists/gradle-8.11.1-all/*/gradle-8.11.1/bin/gradle`
is the cached distribution.)

Pinned versions (match cache): AGP `8.7.2`, Kotlin `2.0.20`, Compose compiler via
the Kotlin 2.0 `org.jetbrains.kotlin.plugin.compose` plugin. `compileSdk 35`,
`minSdk 31`, `targetSdk 33` (Android 13 behaviour).

**Build gotchas (as of the scaffold commit — `assembleDebug` does NOT yet compile):**

1. **AGP 8 disables AIDL by default.** The hand-mirrored AIDL in `app/src/main/aidl/`
   is not compiled unless `android.buildFeatures { aidl = true }` is set in
   `app/build.gradle.kts`. Without it, every AIDL type
   (`ISensorPrivacyManager`, `IShellService`, the `…shell` package) is "unresolved".
2. **Hidden/`@hide` APIs are referenced directly** and the public `android.jar`
   does not contain them. Verified culprits from the last `compileDebugKotlin`:
   `UserManager.DISALLOW_CAMERA`, `Sensor.handle`, `UserHandle.myUserId()`
   (`DISALLOW_UNMUTE_MICROPHONE` *does* resolve — don't "fix" what isn't broken).
   These need a real fix (platform `framework.jar` on the compile classpath via
   `compileOnly`, or reflection) — do not "solve" it by hard-coding values.
   `compileDebugKotlin` currently fails on these; do not assume the block works.
3. `buildRustNative` (§5.5) is a separate task and never runs during
   `assembleDebug`, so a missing Rust crate does not break the Kotlin build.
   Running it needs `cargo install cargo-ndk` and `crates/sensorblock-native/`.

### 6.2 Install

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **Debug builds get `applicationIdSuffix = ".debug"`** (§`app/build.gradle.kts`), so
> the installed package is **`com.sensorblock.app.debug`** — not `com.sensorblock.app`.
> Every adb/`dpm`/Shizuku target below must use the suffixed id for debug installs.

### 6.3 Provision one of the backends

```sh
# A) Device owner (device must have no accounts; run once)
#    debug build → use com.sensorblock.app.debug (see §6.2)
adb shell dpm set-device-owner com.sensorblock.app/.admin.SensorBlockDeviceAdminReceiver

# B) Shizuku (install the Shizuku app, then start its server)
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
#   (Android 11+: enable Wireless debugging and start Shizuku from the app)

# C) No-root fallback: run the documented appops/cmd snippets by hand (self-test)
```

### 6.4 Manual verification snippets

```sh
adb shell cmd sensor_privacy enable 0 camera            # camera privacy on
adb shell service call sensor_privacy 9 i32 1           # all sensors off
adb shell cmd sensorservice set-uid-state com.foo idle  # per-app sensor kill
adb shell appops set com.foo RECORD_AUDIO ignore        # per-app mic block
adb shell dumpsys sensorservice | head -50              # see live sensor list
```

---

## 7. Testing & verification

- **Unit**: `PolicyEngine` diffing (desired vs observed → expected primitive set);
  reversibility (on→off leaves no residue).
- **Instrumented / manual on a real Android 13 device**:
  - After "all sensors off": a sensor-test app shows zero events; camera apps
    fail to open; mic records silence / is muted.
  - After per-app `set-uid-state idle`: that app sees no sensors; others do.
  - Reboot with toggles on → state persists (except volatile primitives, which
    the boot receiver re-asserts).
  - Toggle off restores original capability.
- **Bypass probes** (document honestly): system/privileged apps, camera quick
  toggle, `adb` re-enable, Dynamic System Updates, etc.

## 8. Decisions (locked by the user)

1. **Per-sensor granularity** → **Grouped block + independent Camera/Mic.**
   Non-privacy sensors are all-or-nothing via the master all-sensor switch; the UI
   never fakes granularity. No root.
2. **UI stack** → **Kotlin + Jetpack Compose (Material 3).**
3. **Privileged backend** → **Both, layered:** Shizuku (primary, drives the
   sensor-privacy service + `cmd`/appops) **and** device owner (Dhizuku delegation
   and/or SensorBlock installed via `dpm set-device-owner`) for hard
   CameraService/AudioService restrictions.
4. **Rust** → **Include** the provisioning CLI + native self-test (§5.5).
5. **Scope** → **Sensors + camera + microphone only** (no location/BT/NFC/Wi-Fi/
   screen-capture in v1; the architecture keeps room for them later).
6. **Identity** → package **`com.sensorblock.app`**, display name **SensorBlock**.

**Still open (defaults assumed unless changed):**

7. **Re-assert daemon** — default: **foreground service + `BOOT_COMPLETED`
   receiver** that re-applies *volatile* primitives (`set-uid-state`,
   `dumpsys restrict`) and re-binds Shizuku.
8. **Signing** — default: **debug builds** for personal use; generate a release
   keystore on request.

## 9. References (AOSP `android13-release`, verified)

- `core/java/android/hardware/SensorPrivacyManager.java` — all-sensor + per-sensor API.
- `core/java/android/hardware/ISensorPrivacyManager.aidl` — Binder transaction list
  (`setSensorPrivacy` = tx #9).
- `services/core/java/com/android/server/sensorprivacy/SensorPrivacyService.java`
  — `cmd sensor_privacy enable|disable <USER_ID> <microphone|camera>`,
  `enforceManageSensorPrivacyPermission()`.
- `…/sensorprivacy/AllSensorStateController.java` — persists
  `/data/system/sensor_privacy.xml` (`all-sensor-privacy`).
- `…/sensorprivacy/PersistedState.java` — per-user camera/mic toggle persistence.
- `…/sensorprivacy/SensorPrivacyStateController.java` — state accessors.
- `frameworks/native/services/sensorservice/SensorService.cpp` — `disableAllSensors()`,
  `shellCommand()` (`set-uid-state`), `dump()` (`restrict`), `MANAGE_SENSORS`/`DUMP`.
- `frameworks/av/services/camera/libcameraservice/CameraService.cpp:1292-1296` —
  all-sensor privacy blocks the camera.
- `services/core/java/com/android/server/audio/AudioService.java:1304,8478` —
  mic privacy toggle → mic mute.
- `frameworks/av/services/audiopolicy/service/AudioPolicyService.cpp:858-862,
  1101-1108, 1802-1822` — **all-sensor privacy silences every recording**
  (`silenceAllRecordings_l`).
- `core/res/AndroidManifest.xml` — `MANAGE_SENSOR_PRIVACY` = `internal|role|installer`;
  `MANAGE_SENSORS` = `signature`.
- `packages/Shell/AndroidManifest.xml` — shell holds `MANAGE_SENSOR_PRIVACY`,
  `OBSERVE_SENSOR_PRIVACY`, `MANAGE_SENSORS`, `MANAGE_CAMERA`.
- `core/java/android/os/UserManager.java` — `DISALLOW_CAMERA`,
  `DISALLOW_UNMUTE_MICROPHONE`.
- `core/java/android/app/admin/DevicePolicyManager.java` — `setCameraDisabled`, etc.
- AOSP docs: <https://source.android.com/docs/core/interaction/sensors/sensors-off>

## 10. Existing-app landscape (no-root vs root)

Summary of the survey (full detail in the session research notes):

| Tool | Root? | Camera | Mic | Other sensors | Per-sensor |
|---|---|---|---|---|---|
| AOSP "Sensors off" tile / all-sensor privacy | No | ✅ all | ✅ silence | ✅ all | ❌ |
| AOSP camera/mic privacy toggles | No | ✅ all | ✅ | ❌ | ❌ |
| `appops` / `pm` per-app (shell) | No | per-app | per-app | body/activity per-app | ❌ |
| `cmd sensorservice set-uid-state` (shell) | No | ❌ | ❌ | per-app (all sensors) | ❌ |
| Device owner / Dhizuku (`setCameraDisabled`, restrictions) | No | ✅ | ✅ forced mute | ❌ | ❌ |
| **Shizuku** (uid 2000 backend) | No | ✅ (drives above) | ✅ | ✅ | ❌ |
| **OwnDroid** (DPM UI; DO via Shizuku/Dhizuku/root/ADB) | No | ✅ (DPM) | ❌ | ❌ | ❌ |
| FulGer "Sensors Off" | **Yes** | ✅ | ✅ | ✅ | ❌ |
| Camera Blocker (BytePioneers) | No | ✅ (device admin) | ❌ | ❌ | ❌ |
| **Sensor Disabler** (Xposed) | **Yes** (Xposed) | ❌ | ❌ | ❌ | **✅** |
| XPrivacyLua (Xposed) | **Yes** (Xposed) | per-app fake | per-app | per-app hide | per-app |
| MyAndroidTools | **Yes** | via components | via components | ❌ | ❌ |
| SnoopSnitch | **Yes** (+Qualcomm) | ❌ | ❌ | ❌ | ❌ |
| AFWall+ | **Yes** | ❌ | ❌ | ❌ | ❌ |
| Island / Shelter | No | per-app (profile) | per-app | per-app | ❌ |
| Bouncer | No | per-app (temporary) | per-app | ❌ | ❌ |
| GrapheneOS Sensors permission | No (custom OS) | separate perms | separate perms | ✅ per-app | ❌ (all "other" together) |

**Takeaways.** (1) Only **Xposed/root** tools achieve true *per-sensor* blocking
(Sensor Disabler, XPrivacyLua). (2) **OwnDroid** is the closest no-root analogue
but stops at DevicePolicyManager — it has **no sensor-privacy or mic-toggle
control**. (3) **Shizuku** is the only no-root identity that can reach the sensor
privacy service. SensorBlock's niche = *Shizuku + device-owner layered*, with an
honest grouped-vs-independent UI.


## 11. Roadmap / task list

- [x] Verify AOSP enforcement primitives & hard limits (this doc).
- [x] Confirm §8 decisions with the user.
- [x] Scaffold Gradle project (AGP 8.7.2 / Kotlin 2.0.20 / Compose), wrapper, manifest.
- [x] Write `SensorInventory` + full Compose UI (status, master switch, cam/mic, apps, diagnostics).
- [x] Write `PrivilegedBackend` + `ShizukuBackend` (sensor-privacy AIDL, `cmd`, appops).
- [x] Write device-owner backend (`DevicePolicyBackend`, user restrictions).
- [x] Write `PolicyEngine` reconcile + `PolicyStore` persistence.
- [x] Write boot/foreground re-assertion (`ReassertService`, `BootReceiver`).
- [x] Write diagnostics self-test (`SelfTest`).
- [ ] **Make it compile** — `assembleDebug` fails; fix §6.1 gotcha #1 (`aidl = true`) then #2 (hidden-API access). Nothing is verified on-device yet.
- [ ] Rust provisioning CLI (`sensorblockctl`) + native self-test crate (`crates/` not created).
- [ ] On-device verification matrix (§7).
