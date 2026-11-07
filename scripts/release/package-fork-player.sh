#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
output=release-output
mkdir -p "$output"
apk=apps/player-android/app/build/outputs/apk/release/app-release.apk
tools="$ANDROID_HOME/build-tools/35.0.0"
test -x "$tools/apksigner"
verify="$("$tools/apksigner" verify --verbose --print-certs "$apk" 2>&1)"
cert="$(printf '%s\n' "$verify" | bash scripts/extract-apksigner-sha256.sh)"
test "$cert" = '91549d13cdb06604cdcb8adfa6fc9e787366bd199e3c9047abfa114a03e0c17f'
metadata="$("$tools/aapt" dump badging "$apk")"
manifest="$("$tools/aapt" dump xmltree "$apk" AndroidManifest.xml)"
if grep -E 'android:debuggable.*0xffffffff' <<< "$manifest"; then
  echo 'The distributed fork APK must be an optimized, non-debuggable release.' >&2
  exit 1
fi
# Release optimization shortens resource filenames; resolve the named resource
# from the compiled table instead of assuming its source path survives in APK.
network_path="$("$tools/aapt" dump --values resources "$apk" | python3 scripts/release/android_network_resource.py)"
network="$("$tools/aapt" dump xmltree "$apk" "$network_path")"
grep -E 'cleartextTrafficPermitted=.*0xffffffff' <<< "$network" >/dev/null
version="${TILECAST_FORK_VERSION:-$(cat deploy/fork/VERSION)}"
code="$(python3 scripts/release/release_version.py code "$version")"
grep -F "package: name='org.tilecast.player.test' versionCode='$code' versionName='$version'" <<< "$metadata"
python3 apps/player-android/ci/check-native-alignment.py "$apk"
cp "$apk" "$output/tilecast-player.apk"
sha="$(sha256sum "$apk" | cut -d' ' -f1)"
size="$(wc -c < "$apk")"
jq -n --arg version "$version" --argjson code "$code" --arg sha "$sha" --argjson size "$size" --arg cert "$cert" '{schemaVersion:1,product:"tilecast-player",applicationId:"org.tilecast.player.test",versionName:$version,versionCode:$code,channel:"beta",minimumSdk:23,apkAssetName:"tilecast-player.apk",apkSizeBytes:$size,apkSha256:$sha,signingCertificateSha256:$cert,releaseNotes:"Tilecast TEST fork development build"}' > "$output/tilecast-player-update.json"
openssl pkeyutl -sign -rawin -inkey "${TILECAST_FORK_MANIFEST_KEY:?}" -in "$output/tilecast-player-update.json" -out "$output/signature.bin"
openssl pkeyutl -verify -rawin -pubin -inkey deploy/fork/update-public-key.pem -in "$output/tilecast-player-update.json" -sigfile "$output/signature.bin"
openssl base64 -A -in "$output/signature.bin" > "$output/tilecast-player-update.json.sig"
rm "$output/signature.bin"
(cd "$output" && sha256sum tilecast-player* > SHA256SUMS)
