# Tilecast Player for Android TV

Native player for Fire TV, Google TV, and Android TV. Shared Player Core (Rust) owns pairing, content selection, offline scheduling, presence, and recovery. The shared Player Runtime in a trusted WebView plays the content. Kotlin and Jetpack Compose provide the OS integration and the Tilecast-owned setup, pairing, maintenance, and recovery screens.

See [`docs/android-development.md`](../../docs/android-development.md) for build and emulator instructions.

## Native Player Core host

The APK embeds the Rust crate `tilecast-player-android-native` from
`native/`. It owns one process-level Player Core host, a narrow JNI bridge,
Android storage locations, and Android platform TLS trust. Kotlin owns the
host lifetime through the application-scoped `PlayerCoreHost`.

An APK build needs the Rust toolchain from `rust-toolchain.toml`,
`cargo-ndk`, and the pinned NDK from `tilecastNdkVersion` in
`app/build.gradle.kts`. JVM unit tests do not need these tools. See
[`docs/android-development.md`](../../docs/android-development.md) for the
install commands, the host tests, and the 16 KB alignment gate.

## Architecture

```text
Player Core (Rust, process-level host: PlayerCoreHost)
    -> Android renderer adapter (WebViewCoreRenderer, owned by ProcessRenderer)
    -> trusted shared Player Runtime WebView
    -> image / video / Layout / Widget / website surfaces

Compose (MainActivity)
    -> setup, pairing, maintenance, update, recovery,
       and the status surface over the WebView
```

Player Core decides what plays. The shared `@tilecast/player-runtime`
(`docs/player-runtime.md`) draws it inside one trusted WebView served from the
app-owned `https://appassets.androidplatform.net` origin. The `runtime/`
package holds the Android `TilecastRuntimeHostV1` host: the packaged-runtime
manifest verifier, the typed bridge protocol, ordered state replay,
host-authorized `tcmedia:` media lookup, and renderer-generation crash
recovery. Compose draws only Tilecast-owned native screens. The Player has no
Compose renderer for content and no Media3 dependency.

### Renderer ownership

- `ProcessRenderer` owns one `WebViewCoreRenderer` for the process. The
  Activity never creates, numbers, connects, or closes it. It attaches the view
  and its capture hooks, and detaches them in `onDestroy`.
- Renderer generations come from `RendererGenerations`, one counter for the
  process. A generation is never reused, so a late `disconnected` from an old
  renderer cannot disconnect a newer one.
- `RendererHandshake` registers each generation with Core. It waits for Core to
  open, retries a refused `connected` within bounds, keeps an early runtime
  `ready` until registration, and fails visibly with a reason code.
- `MainActivity` covers the WebView with a native status surface until the
  runtime is registered and ready. A renderer that cannot recover shows
  **Display unavailable** with a reason code, never an unexplained black screen.

Read [`docs/android-development.md`](../../docs/android-development.md) before
you change any of this. It defines the handshake, the log events (logcat tag
`TilecastRenderer`), and the hardware qualification procedure.

### Capabilities and typography

`renderer-profile.json` defines the renderer features and schemas. The
contracts generator writes the native packaged profile and the Kotlin ready
report from it, so preparation and the live renderer cannot disagree. Run
`npm run player-contracts:generate` after you change it.

Native text uses Geist through `ui/theme/Type.kt`. The `syncGeistFont` Gradle
task packages the vendored font. Do not set a font family in a composable.

### USB media cache (Android TV)

The Android Player can store **verified media CAS objects and resumable downloads** on
a mounted, writable USB volume exposed by Android as an app-specific external files
directory. SQLite state, pairing and configuration always stay in internal storage.

Open local administrator maintenance (Back, Back, Up, Down, OK), unlock with the
local PIN, and select **Media cache storage**:

- Internal memory (default).
- Automatic (first writable removable volume, falling back to internal).
- A specific USB volume (falls back to internal if it is absent).

Reboot the TV or fully force-stop and reopen the Player after changing the
selection. The maintenance `Restart Tilecast` button only restarts the Activity,
not the process-scoped native Core. The maintenance panel shows the
**actually open CAS path from native Core status** separately from the configured
selection, so a USB-open failure and internal fallback are visible.

Existing media cache objects are not automatically migrated. When native Core
confirms it is currently running from a mounted USB drive, maintenance offers
**Clear inactive internal media cache**. It requires a separate irreversible
confirmation and removes only `filesDir/player-core/cas` and
`filesDir/player-core/partial`, never `state.db`, pairing credentials,
configuration or the active USB CAS. Cleanup is refused when Core is still using
internal storage, the USB has disappeared, or an unexpected filesystem layout
or symbolic link is encountered. Clearing the old internal cache frees TV space
but reduces offline fallback: if USB is later unavailable, content must be
downloaded again while online.

The CAS reconciles its index at startup and fetches missing media again while
online. Reinsert a missing USB drive and fully restart to use it again.
Unplugging the drive while playing can interrupt media hosted there. The Player listens for Android's early `MEDIA_EJECT` broadcast and promptly closes open WebView media streams before `vold` unmounts the volume, avoiding the system's SIGINT of processes still holding USB descriptors on affected TVs. Abrupt removal without an early broadcast, or other native readers holding descriptors, still requires device validation.
Supported storage uses regular filesystem paths via `getExternalFilesDirs`;
SAF-only USB storage is not eligible. This feature requires no root or broad
storage permission. The existing CAS limit and free-space reserve still apply.
