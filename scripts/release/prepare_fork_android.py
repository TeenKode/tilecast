#!/usr/bin/env python3
"""Retain the package identity of the installed test Player."""
from pathlib import Path

path = Path("apps/player-android/app/build.gradle.kts")
source = path.read_text()
before = "    buildTypes {\n        release {"
after = '''    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".test"
        }
        release {'''
if source.count(before) != 1:
    raise SystemExit("Unexpected Android buildTypes; refusing to change package identity")
path.write_text(source.replace(before, after))
