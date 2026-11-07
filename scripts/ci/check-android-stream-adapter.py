#!/usr/bin/env python3
"""Keep native media decoding in the one Android host surface adapter."""
import pathlib
import re
import sys

root = pathlib.Path(__file__).resolve().parents[2]
android = root / "apps/player-android"
adapter = android / "app/src/main/java/org/tilecast/player/runtime/NativeStreamPlayerView.kt"
gradle = android / "app/build.gradle.kts"
violations = []
for path in [*sorted((android / "app/src/main").rglob("*.kt")), gradle]:
    text = path.read_text(encoding="utf-8")
    if path not in (adapter, gradle) and re.search(r"androidx\.media3|ExoPlayer", text):
        violations.append(str(path.relative_to(root)))
if violations:
    sys.exit("Media3 outside the native stream host adapter: " + ", ".join(violations))
print("Android stream adapter boundary passed")
