#!/usr/bin/env python3
"""Choose the next ordered Dev version from the fork's own release history."""
import argparse
import json
import re

import release_version as rv

DEV = re.compile(r"\d+\.\d+\.\d+-dev\.([1-9][0-9]?)")


def resolve(minimum, releases, commit):
    floor = rv.parse_tag("v" + minimum)
    if not DEV.fullmatch(floor.name):
        raise ValueError("the fork minimum must use X.Y.Z-dev.N")
    used = []
    for release in releases:
        tag = release.get("tag_name", "")
        parsed = rv.parse_version(tag[1:]) if tag.startswith("v") else None
        if parsed is None or not parsed.unified:
            continue
        if DEV.fullmatch(parsed.name) and release.get("target_commitish") == commit:
            if release.get("draft"):
                raise ValueError("this commit already has a draft; finish its verified publication before rebuilding")
            return parsed.name, True
        used.append(parsed)
    highest = max(used, key=lambda version: version.code, default=None)
    if highest is None or highest.code < floor.code:
        return floor.name, False
    match = DEV.fullmatch(highest.name)
    if match and int(match.group(1)) < 98:
        candidate = ".".join(map(str, highest.core)) + f"-dev.{int(match.group(1)) + 1}"
    else:
        major, minor, patch = highest.core
        next_core = major * 1_000_000 + minor * 1000 + patch + 1
        major, remainder = divmod(next_core, 1_000_000)
        minor, patch = divmod(remainder, 1000)
        candidate = f"{major}.{minor}.{patch}-dev.1"
    parsed = rv.parse_version(candidate)
    if parsed is None or parsed.code <= highest.code:
        raise ValueError("the next fork version exceeds the supported version-code range")
    return candidate, False


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minimum", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--releases", required=True)
    args = parser.parse_args()
    with open(args.releases, encoding="utf-8") as handle:
        releases = json.load(handle)
    if releases and isinstance(releases[0], list):
        releases = [release for page in releases for release in page]
    try:
        version, published = resolve(args.minimum, releases, args.commit)
    except ValueError as error:
        parser.error(str(error))
    print(f"version={version}")
    print(f"published={str(published).lower()}")
