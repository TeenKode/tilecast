#!/usr/bin/env python3
"""Resolve only a published Dev release's immutable server image."""
import json
import re
import urllib.request

request = urllib.request.Request(
    "https://api.github.com/repos/TeenKode/tilecast/releases?per_page=100",
    headers={"Accept": "application/vnd.github+json", "User-Agent": "tilecast-test-updater"},
)
with urllib.request.urlopen(request, timeout=30) as response:
    releases = json.load(response)
releases = [r for r in releases if not r["draft"] and r["prerelease"] and re.fullmatch(r"v\d+\.\d+\.\d+-dev\.[1-9][0-9]?", r["tag_name"])]
if not releases:
    raise SystemExit("No published fork Dev release")
release = max(releases, key=lambda r: r["published_at"])
asset = next(a for a in release["assets"] if a["name"] == "tilecast-server-image.txt")
url = asset["browser_download_url"]
if not url.startswith("https://github.com/TeenKode/tilecast/releases/download/"):
    raise SystemExit("Unexpected image metadata URL")
with urllib.request.urlopen(url, timeout=30) as response:
    image = response.read(512).decode().strip()
if not re.fullmatch(r"ghcr\.io/teenkode/tilecast-server@sha256:[0-9a-f]{64}", image):
    raise SystemExit("Release does not contain an immutable fork image digest")
print(image)
