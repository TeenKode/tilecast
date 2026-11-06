# Optional Android / DEXP / YaOS power adapter

This fork integrates a Shizuku-backed Android Power Assist adapter. It is
**opt-in per TV**, preserves the stock power implementation by default, and
uses Tilecast's existing server commands and Active Hours schedule.

## Why this requires a Player APK change

Tilecast Store plugins do **not** receive Android shell/native permissions.
Android does not currently report external plugin execution or Display Control
capabilities, so a Store-only plugin cannot replace the Player's power call.
Only the trusted Android platform executor can call this adapter. The normal
server container is unchanged.

## Setup on DEXP 43UCY1 (YaOS / Android 11)

1. Install and start [Shizuku](https://shizuku.rikka.app/) as ADB shell (UID
   2000), not root. Non-root Shizuku is normally **stopped after reboot** and
   must be restarted; this is not an unattended reboot solution.
2. Build/install this modified Tilecast Android Player, preserving the
   existing package/pairing data. **APK signature must match** the existing
   installed Player, or an in-place update will fail.
3. Open Tilecast maintenance with **Back, Back, Up, Down, OK**; unlock using
   the local administrator PIN.
4. Choose **Connect Shizuku**, grant Tilecast access and choose
   **Refresh status**. Expect "Ready: ADB shell (UID 2000)".
5. Select **Enable Shizuku power**. Test a Power Assist sleep and wake
   separately from Studio; then enable off-hours sleep in Active Hours and
   test a schedule. Disable the switch to restore stock Android behavior.

If Shizuku is not available, the player logs `fallback_not_ready` and
attempts the stock Android power path. This fallback may show black content
without turning off the physical TV.

## Fixed command contract

The Shizuku UserService accepts only a boolean awake choice, runs as UID
2000 (refuses UID 0/root), and checks a 9-second elapsedRealtime deadline.
There is **no arbitrary shell command** exposed to Tilecast or plugins.

On CVTE firmware with `fake_standby_switch=true` or `1`, the service first
reads `IS_FAKE_STANDBY_STATUS` (0=awake, 1=standby). It sends Android
`input keyevent 26` only when that status differs, and verifies the value
afterward. If the status is unknown it **refuses a blind toggle**.

On other firmware the fixed commands are `input keyevent 223` (sleep) and
`input keyevent 224` (wake). Their success proves only that the command
process returned zero, not that a physical TV powered on/off.

`shizuku_sleep_queued` and `shizuku_wake_queued` are dispatch results
returned to Studio. Completion is logged to `TilecastShizukuPower` and in
the local maintenance screen; Tilecast must not claim that a queued result
confirms the panel power state.

## Important operational limitations

- Physical sleep/wake on DEXP 43UCY1 has **not yet been verified**.
- If TV deep standby stops Android, Shizuku or the alarm scheduler, remote
  wake may fail even when manual power-off succeeds.
- Reboot and Shizuku loss must be tested before school-wide deployment.
- Android Display Control (scheduled CEC/DDC actions) is not enabled by this
  adapter. It only affects **Power Assist** and **Active Hours**.
- Only the forked Player APK includes the adapter; replacing it with an
  official Player build removes it.
- No change to regular Tilecast Server Docker deployment is required.

Diagnostics:

```sh
adb shell settings get global fake_standby_switch
adb shell settings get global IS_FAKE_STANDBY_STATUS
adb logcat -s TilecastShizukuPower
```

Build and test per `docs/android-development.md`; use Android SDK, JDK 17,
pinned NDK, Rust/cargo-ndk and the shared Player Runtime. Run
`cd apps/player-android && ./gradlew :app:testDebugUnitTest`.
The project uses strict Gradle dependency verification: adding new Shizuku
dependencies requires generating and reviewing SHA-256 metadata before CI
builds pass; do not disable verification or trust unverified downloads.
