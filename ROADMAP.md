# SensorBlock — Roadmap & Status

> Companion to `AGENTS.md`. That file is the **why** (verified AOSP behaviour, hard
> limits, locked decisions); this file is the **where we are and what is next**.
> Update this file whenever a milestone or task changes state.

Target: **Android 13 (API 33), non-rooted**. Scope: **sensors + camera + microphone**
only (§8.5). Identity: package `com.sensorblock.app`, name **SensorBlock**.

---

## Legend

- `[x]` done and verified · `[~]` written but **not yet verified** · `[ ]` not started
- "Verified" = observed on a real device or via a green build, not just code present.

---

## Current status snapshot

The full Kotlin app is written (§5.1 architecture) — UI, domain, both privileged
backends, persistence, boot re-assertion and diagnostics. **It compiles**
(Milestone 1) and **it now runs on the real device** (Milestone 2, in progress).

Verified on the target device (**an OEM device, Android 13, non-rooted, Shizuku
shell**) via the debug harness (`app/src/debug/**`, see `AGENTS.md` §6.5):

- ✅ **All-sensor kill switch works** through the app → Shizuku → `sensor_privacy`.
  It blocks hardware sensors and silences the microphone.
- ✅ **Camera privacy toggle works** and blocks the camera ("disabled by policy").
- ✅ **Microphone privacy toggle works** and silences recordings.
- ✅ All three are **reversible** (toggle-off restores the exact prior state).
- ✅ **The master kill switch blocks everything.** Because the all-sensor switch
  ignores the camera on this ROM, the master switch now also drives the camera and
  microphone privacy toggles (`DesiredPolicy.effectiveBlockCamera/Microphone`).
  A single master ON ⇒ sensors + camera + mic all BLOCKED; master OFF ⇒ all clear.

Device-specific corrections to the research (see `AGENTS.md` §3.1 addendum):

- The **all-sensor switch does NOT block the camera on this ROM** (it does on
  AOSP). Camera blocking here relies on the camera privacy toggle.
- The per-sensor toggle is read via **`isCombinedToggleSensorPrivacyEnabled`**;
  a software-only read returns false even when the block is active.
- **`supportsSensorToggle` returns false** even though the toggle works, so it is
  no longer used to gate camera/mic capability.

Still outstanding:

1. Per-app `appops` / `set-uid-state` and persistence-on-reboot
   are not yet verified on device (§7).
2. The Rust crates (`crates/`) do not exist yet (§5.5).

Current layout, verified files and the compile fixes that must not regress are
recorded in `AGENTS.md` §5.3 and §6.1.

---

## Milestone 0 — Research & decisions ✅

- [x] Verify every non-root enforcement primitive against AOSP `android13-release`
      (`AGENTS.md` §3, §9).
- [x] Establish the **hard limit**: non-privacy sensors have no per-sensor switch;
      only the device-wide all-sensor block exists (`AGENTS.md` §3.5).
- [x] Lock the design decisions (`AGENTS.md` §8): grouped block + independent
      Camera/Mic; Compose/M3; **Shizuku-only** backend (device owner later dropped);
      Rust CLI + native self-test; package `com.sensorblock.app`.

## Milestone 1 — Make it compile ✅

`./gradlew :app:assembleDebug` is **green** and produces
`app/build/outputs/apk/debug/app-debug.apk`. Three fixes were required (keep them
regressed-proof; detail in `AGENTS.md` §6.1):

- [x] **AIDL enabled.** `android.buildFeatures { aidl = true }` — AGP 8 disables
      AIDL by default; without it every AIDL type cascades into `backend/`.
- [x] **AIDL all-or-none method ids.** `IShellService.aidl` assigns ids to all
      methods (`runCommand=1`, `runCommandStdout=2`, `uid=3`, `destroy=16777114`),
      and `ShellUserService` implements all four (including `destroy()`).
- [x] **Hidden `@hide` APIs → public equivalents.** `Sensor.getId()` /
      `getMaximumRange()`, `UserHandle.USER_CURRENT` instead of `myUserId()`, and
      a named `"no_camera"` constant for `UserManager.DISALLOW_CAMERA`
      (see Decision D1).

Definition of done: ✅ APK builds with zero errors.

## Milestone 2 — On-device verification 🟠 in progress

Install and provision (see `AGENTS.md` §6.2–§6.4; remember debug → package
`com.sensorblock.app.debug`). Driven from `adb` with the debug harness
(`AGENTS.md` §6.5), since the ROM's `uiautomator` is broken.

- [x] Shizuku backend: all-sensor kill switch flips (hardware sensors + mic).
      **Not** the camera on this ROM — see the §3.1 addendum.
- [x] Camera privacy toggle blocks the camera (`CameraAccessException:
      CAMERA_DISABLED — Camera "0" disabled by policy`).
- [x] Microphone privacy toggle silences recordings.
- [x] ~~Device-owner backend~~ **dropped** — unreachable on this device (multiple users and accounts → `dpm set-device-owner`
      returns `STATUS_NONSYSTEM_USER_EXISTS`) and redundant with Shizuku. The DPM
      code, device-admin receiver and `device_admin.xml` were removed; the app is
      now Shizuku-only.
- [ ] Per-app `appops` and `cmd sensorservice set-uid-state` block only the target.
- [x] **Reversibility**: every toggle-off restores the exact prior OS state
      (camera toggle, mic toggle, all-sensor all verified on↔off).
- [ ] **Persistence**: state survives reboot; volatile primitives are re-asserted
      by `ReassertService` / `BootReceiver`.
- [x] Diagnostics self-test reports `BLOCKED` for each blocked target (camera,
      microphone, sensor data).
- [ ] Run the §7 bypass probes and record honest results.

Fixes found during verification (all committed):

- Camera self-test threw `IllegalArgumentException: No handler given` — the probe
  passed a `null` `Handler` to `openCamera` while on a non-Looper thread. Fixed
  with a dedicated `HandlerThread`.
- One failing probe aborted the whole `runAll()`; each probe is now isolated.
- Mic self-test false-positived "BLOCKED" on a cold `AudioRecord`; it now
  discards warm-up buffers and reads over a 1.5 s window.
- Camera/mic reads used the software toggle type; switched to the combined query.
- The device-policy backend (DPM) is **removed** — Shizuku alone covers every
  block, so the device-admin receiver and owner-only paths were deleted.

Definition of done: the §7 verification matrix is filled in with real results.

## Milestone 2b — Quick Settings master tile 🟠 built, not device-verified

- [x] `service/MasterTileService`: a QS tile that toggles the **master block**
      (all sensors + camera + mic) by writing the same `blockAllSensors` policy as
      the Home switch and running `PolicyEngine.apply()`. The tile shows
      `STATE_ACTIVE`/`STATE_INACTIVE` and `STATE_UNAVAILABLE` when Shizuku is
      unreachable; it makes no UI calls, so it needs no unlock.
- [x] In-app **"Add master switch to Quick Settings"** button on Home, wired to
      `StatusBarManager.requestAddTileService(...)` (public API 33; below 33 it
      shows the manual-add hint).
- [x] Long-press on the tile opens the app (`QS_TILE_PREFERENCES` on `MainActivity`).
- [x] On-device: the tile registers in Quick Settings (`cmd statusbar add-tile`) and
      `cmd statusbar click-tile` flips the all-sensor / camera / mic switches
      together (verified via `service call sensor_privacy 6` / `7 i32 2` / `7 i32 1`).
      It writes the same policy as the Home switch, so the two stay in sync.
- [ ] On-device: the locked-keyguard tap, the UNAVAILABLE state when Shizuku is
      down, and the in-app `requestAddTileService` prompt are not yet exercised.

Definition of done: the tile toggles enforcement on a real device exactly like the
Home master switch.

## Milestone 3 — Rust tooling 🔴

`buildRustNative` is wired in `app/build.gradle.kts` but the crates are missing.
Note `cargo-ndk` is **not installed** (`cargo install cargo-ndk` first).

- [ ] `crates/sensorblock-native`: NDK `ASensorManager` `.so` proving sensors
      return no data when blocked; load it from `SelfTest` (currently absent —
      there is no `System.loadLibrary` call yet).
- [ ] `crates/sensorblockctl`: host-side `adb` provisioning CLI (install, set
      device owner, start/verify Shizuku, apply + verify each block, dump state).

## Milestone 4 — Polish & release 🔴

- [ ] Beautiful/clear UI pass (Material 3 dynamic color, confirm dialogs for
      device-wide actions) — review against §5.4.
- [ ] Unit tests for `PolicyEngine` diffing + reversibility (§7) — no test source
      set exists yet.
- [ ] Release signing (open decision §8.8; default debug only).
- [ ] Optional: extend scope beyond sensors/camera/mic (§8.5, explicitly deferred).

---

## Verification matrix (`AGENTS.md` §7)

Fill in as Milestone 2 runs. "Zero events" / "silence" observed via the built-in
self-test and an external sensor-test app.

| Primitive | Device-wide? | Expected when blocked | Verified? |
|---|---|---|---|
| All-sensor switch | yes | no sensor events; camera refused; mic silenced | ✅ sensors + mic blocked; **camera NOT blocked on this ROM** |
| Camera privacy toggle | yes (all lenses) | camera will not open | ✅ `CAMERA_DISABLED — disabled by policy` |
| Mic privacy toggle | yes | recordings silent / muted | ✅ silent capture (peak 0) |
| `appops … ignore` | per-app | only that app is refused | ☐ not yet tested |
| `sensorservice set-uid-state idle` | per-app | that app sees no sensors | ☐ not yet tested |

Removed (Shizuku-only now): `DevicePolicyManager.setCameraDisabled`,
`DISALLOW_CAMERA` and `DISALLOW_UNMUTE_MICROPHONE` are no longer used — they need
device ownership, which is unreachable here (`AGENTS.md` §3.3, §8).

Test setup: an OEM device, Android 13, non-rooted; Shizuku v13.6.0 server as uid 2000;
SensorBlock debug build, hidden-API exemptions enabled. Ground truth read with
`adb shell service call sensor_privacy …` (tx6/7/8) and `dumpsys media.camera`.

---

## Known limits (do not re-litigate — `AGENTS.md` §3.5)

- **No per-sensor granularity without root.** The only device-wide primitive is
  the all-sensor switch; individual non-privacy sensors collapse onto it. The UI
  must stay honest about this.
- **No per-lens camera control.** Camera blocking is whole-subsystem.
- Individual-sensor / per-lens control would need root, Xposed or a custom ROM —
  explicitly out of scope.

---

## Decision log (project-specific, beyond `AGENTS.md` §8)

**D1 — Resolve hidden-API references with public equivalents (applied in Milestone 1).**
- `Sensor`: use public `getId()` (stable handle) and `getMaximumRange()`.
- `UserHandle.myUserId()`: the `sensor_privacy` service resolves
  `UserHandle.USER_CURRENT` (`-2`) to the active user, so pass that instead of
  querying the hidden `myUserId()`.
- `UserManager.DISALLOW_CAMERA` is `@hide`; its value is the documented platform
  contract `"no_camera"`. Reference it via a named constant with an AOSP citation.

Rationale: keeps the AIDL/Binder core untouched, needs no `framework.jar`, and
avoids runtime hidden-API enforcement. Revisit if a real platform jar becomes
available.

---

## Next actions (ordered)

1. ~~Add `.gitignore`, initialise git, commit the current state~~ ✅ done.
2. ~~`aidl = true` + the three hidden-API fixes → green build (Milestone 1)~~ ✅ done.
3. ~~Install on the Android 13 device; drive the app via the debug harness; verify
   the all-sensor switch, camera toggle and mic toggle end-to-end~~ ✅ done.
4. **Next:** verify per-app `appops` + `set-uid-state`, and persistence across
   reboot (Milestone 2).
5. Scaffold `crates/` once enforcement is proven (Milestone 3).
