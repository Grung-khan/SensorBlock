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
backends, persistence, boot re-assertion and diagnostics. It is **not yet a
working artifact**:

1. `./gradlew :app:assembleDebug` **does not compile** (see Milestone 1).
2. Nothing has been run on a device; all backend behaviour is unverified (§7).
3. The Rust crates (`crates/`) do not exist yet (§5.5).

Current layout, verified files and the two compile blockers are recorded in
`AGENTS.md` §5.3 and §6.1. Do **not** claim the block works until Milestone 2.

---

## Milestone 0 — Research & decisions ✅

- [x] Verify every non-root enforcement primitive against AOSP `android13-release`
      (`AGENTS.md` §3, §9).
- [x] Establish the **hard limit**: non-privacy sensors have no per-sensor switch;
      only the device-wide all-sensor block exists (`AGENTS.md` §3.5).
- [x] Lock the design decisions (`AGENTS.md` §8): grouped block + independent
      Camera/Mic; Compose/M3; layered Shizuku **and** device-owner; Rust CLI +
      native self-test; package `com.sensorblock.app`.

## Milestone 1 — Make it compile 🚧 (in progress)

The scaffold exists but `compileDebugKotlin` fails. Two independent root causes
(detailed in `AGENTS.md` §6.1):

- [ ] **Gotcha #1 — AIDL disabled.** AGP 8 turns AIDL off by default, so the
      hand-mirrored AIDL under `app/src/main/aidl/` is never compiled. Fix:
      `android.buildFeatures { aidl = true }` in `app/build.gradle.kts`.
      Until then every AIDL type (`ISensorPrivacyManager`, `IShellService`, the
      `…shell` package) is "unresolved" and the whole `backend/` package cascades.
- [ ] **Gotcha #2 — hidden `@hide` APIs.** The public `android.jar` lacks symbols
      the code references directly: `UserManager.DISALLOW_CAMERA`,
      `Sensor.handle`, `UserHandle.myUserId()`, plus a wrong public method name
      (`Sensor.getMaxRange` → the real public API is `getMaximumRange`).
      Fix by using public equivalents / public-constant values (see Decision D1
      below) — **not** by hard-coding Binder transaction indices.

Definition of done: `./gradlew :app:assembleDebug` produces
`app/build/outputs/apk/debug/app-debug.apk` with zero errors.

## Milestone 2 — On-device verification 🔴

Install and provision (see `AGENTS.md` §6.2–§6.4; remember debug → package
`com.sensorblock.app.debug`) and prove each primitive actually works:

- [ ] Shizuku backend: all-sensor kill switch flips (sensors + camera + mic).
- [ ] Camera privacy toggle blocks every lens.
- [ ] Microphone privacy toggle mutes / silences recordings.
- [ ] Device-owner backend: `setCameraDisabled` + `no_unmute_microphone` survive
      with Shizuku down.
- [ ] Per-app `appops` and `cmd sensorservice set-uid-state` block only the target.
- [ ] **Reversibility**: every toggle-off restores the exact prior OS state.
- [ ] **Persistence**: state survives reboot; volatile primitives are re-asserted
      by `ReassertService` / `BootReceiver`.
- [ ] Diagnostics self-test reports `BLOCKED` for each blocked target.
- [ ] Run the §7 bypass probes and record honest results.

Definition of done: the §7 verification matrix is filled in with real results.

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
| All-sensor switch | yes | no sensor events; camera refused; mic silenced | [ ] |
| Camera privacy toggle | yes (all lenses) | camera will not open | [ ] |
| Mic privacy toggle | yes | recordings silent / muted | [ ] |
| `setCameraDisabled` / `DISALLOW_CAMERA` | yes | camera refused at CameraService | [ ] |
| `no_unmute_microphone` | yes | mic forced muted | [ ] |
| `appops … ignore` | per-app | only that app is refused | [ ] |
| `sensorservice set-uid-state idle` | per-app | that app sees no sensors | [ ] |

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

**D1 — Resolve the hidden-API blocker with public APIs, not literals-as-hacks.**
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

1. Add `.gitignore`, initialise git, commit the current state (checkpoint).
2. `aidl = true` + the three hidden-API fixes → get a green build (Milestone 1).
3. Install on the Android 13 device; run `SelfTest`; fill the matrix (Milestone 2).
4. Scaffold `crates/` once enforcement is proven (Milestone 3).
