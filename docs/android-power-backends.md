# Configurable Android TV power methods

Tilecast's Android APK contains a fixed set of power providers. The **Server
continues to schedule and send normal Power Assist commands**; the Android
Player chooses a platform implementation from the screen's effective policy.
There is no Shizuku, root requirement, external plugin execution or command
runner. Other Player platforms ignore these Android-only settings.

## Studio

Go to the screen's **Player policy / Power** settings. Optionally override
the organization/group policy using two independent values:

- **Android sleep method**: `auto` (default), `device_policy`,
  `accessibility`, `black_screen`, or `cvte`.
- **Android wake method**: `auto` (default), `activity`, `wake_lock`,
  or `cvte`.

The normal Tilecast schedule controls when these methods run. Enable
"Request sleep outside active hours" to actually request standby when
leaving active hours. For a CVTE TV, select `cvte` for **both** sleep and wake.

**Auto** preserves stock Android behavior. Unsupported explicit methods
return a failure instead of silently using another method. Choosing
`black_screen` intentionally leaves the panel powered on.

## CVTE fake standby

The verified source for this provider is the user's
`cvte-root-toolkit/integrations/sleep-timer/LocalAdb.java`.
That source was tested on a DEXP 43UCY1 (YaOS / Android 11). It runs a
minimal legacy ADB protocol client over `127.0.0.1:5555` and requires
that the TV already has **native fake standby enabled**:

```sh
adb shell settings get global fake_standby_switch
adb shell settings get global IS_FAKE_STANDBY_STATUS
```

Expected first result: `true`. The second is `0` (awake) or `1`
(fake standby). The provider reads both, sends only the fixed
`input keyevent 26` when they differ from the requested state, then
checks the status again. It does **not** write the firmware state variable,
enable native standby, accept network addresses or arbitrary shell commands,
or attempt an unsafe blind toggle when the state is unknown.

Local ADB must be enabled and accepting non-authenticated loopback
connections, as on the tested television. If ADB requests authorization,
the provider fails closed. This is a **device-specific security requirement**
and should not be enabled unnecessarily on other TVs. The method
does not need Shizuku or a root shell. There is no remote ADB connection
to the television from the Tilecast Server.

When CVTE fake standby succeeds, the panel backlight turns off while
Android, the network and ADB continue to run. This is not a complete
hardware shutdown or deep standby. Hardware behavior must be qualified
per firmware.

## Runtime and diagnostics

- Selecting `cvte` changes Power Assist and Active Hours only; Linux
  Display Control (CEC/DDC) remains independent.
- Operations are serialized with a bounded request lifetime so old
  power commands cannot inadvertently toggle the television later.
- `cvte_sleep_queued` / `cvte_wake_queued` mean dispatch accepted,
  **not that the physical panel has confirmed the transition**.
- The result after the local ADB check appears in Android private
  preferences as `last-cvte-result`, and in the
  `TilecastCvtePower` logcat tag.
- A scheduled CVTE wake reopens the existing Tilecast Activity using
  `NEW_TASK | CLEAR_TOP | SINGLE_TOP`, without spawning new tasks.
- Android standby depends on firmware delivering the alarm. The
  Player cannot turn on a fully unplugged TV.
- If accessibility is used, YaOS may independently reset that service;
  protect its configuration through approved device administration.
- After power-method policy changes, the Player applies the effective
  configuration from the Server. Old Android APKs ignore the new settings.

Debug:

```sh
adb logcat -s TilecastCvtePower
adb shell settings get global fake_standby_switch
adb shell settings get global IS_FAKE_STANDBY_STATUS
```

Test both manual Studio Power Assist commands and the configured
off-hours sleep/wake sequence, including after a complete reboot.

## Compatibility

No changes to the Docker-based Tilecast Server installation are needed
apart from updating Server/Studio to a build that contains the policy keys.
No external APK is required beyond the updated Tilecast Player. Deploy
using the existing Player update flow with a compatible APK signature.
For an isolated test APK, use a different package ID so the production
Player and pairing data remain untouched.
