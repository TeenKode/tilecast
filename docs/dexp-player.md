# Tilecast Player for DEXP

This fork builds `org.tilecast.player.dexp` from upstream `player-v0.25.0`.
It installs alongside the official Player and leaves existing kiosk apps intact.

The local setup wizard keeps its administrator PIN and presentation checks.
Accessibility Control and permission to install updates may be declined explicitly.
Missing permissions remain reported as missing; completion reports partial readiness,
not a fully verified unattended installation. No Android permission is granted by
this change. A separately configured wake app can open this Player after standby.

The capability buttons use a vertical layout to remain usable on portrait displays.

Run the **DEXP Android Player** workflow on `dexp-optional-accessibility`.
It runs Android unit tests and lint, then builds a signed release APK. Signing uses
the repository secrets `TILECAST_DEXP_KEYSTORE_BASE64` and
`TILECAST_DEXP_KEYSTORE_PASSWORD`; never commit the key or password. The resulting
APK and test reports are attached to the workflow run. Keep the signing key for
future updates to this fork. Official upstream APKs have a different application ID
and signing certificate and do not update this application.
