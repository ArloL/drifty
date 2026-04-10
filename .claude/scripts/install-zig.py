#!/usr/bin/env python3
"""Install Zig compiler to $ZIG_HOME.

Reads the zig version from .tool-versions (e.g. "zig 0.14.0"),
fetches the matching release from ziglang.org/download/index.json,
verifies the SHA-256 checksum, and extracts the archive.
"""

import hashlib
import json
import os
import platform
import shutil
import sys
import tarfile
import tempfile
import urllib.request

TOOL_VERSIONS = os.path.join(os.environ["CLAUDE_PROJECT_DIR"], ".tool-versions")
INDEX_URL = "https://ziglang.org/download/index.json"

# ── Read version from .tool-versions ──────────────────────────────────────

zig_version = None
with open(TOOL_VERSIONS) as f:
    for line in f:
        parts = line.split()
        if len(parts) == 2 and parts[0] == "zig":
            zig_version = parts[1]
            break

if not zig_version:
    print(f"ERROR: no zig entry found in {TOOL_VERSIONS}", file=sys.stderr)
    sys.exit(1)

# ── Install path ───────────────────────────────────────────────────────────

install_dir = os.environ.get("ZIG_HOME")
if not install_dir:
    print("ERROR: ZIG_HOME environment variable not set", file=sys.stderr)
    sys.exit(1)

if os.path.isdir(install_dir):
    print(f"Zig {zig_version} already installed at {install_dir}, skipping.")
    sys.exit(0)

# ── Platform detection ─────────────────────────────────────────────────────
# Zig's download index uses keys like "x86_64-linux", "aarch64-macos",
# "x86_64-windows", "aarch64-linux", etc.

_machine = platform.machine().lower()
_system = platform.system().lower()

_arch_map = {
    "x86_64": "x86_64",
    "amd64":  "x86_64",
    "aarch64": "aarch64",
    "arm64":   "aarch64",
}
_os_map = {
    "linux":  "linux",
    "darwin": "macos",
    "windows": "windows",
}

arch = _arch_map.get(_machine)
os_name = _os_map.get(_system)

if not arch or not os_name:
    print(
        f"ERROR: unsupported platform machine={_machine!r} system={_system!r}",
        file=sys.stderr,
    )
    sys.exit(1)

platform_key = f"{arch}-{os_name}"

# ── Fetch download index ───────────────────────────────────────────────────
# urllib picks up HTTPS_PROXY automatically from the environment.

print(f"Fetching Zig download index from {INDEX_URL} ...")
req = urllib.request.Request(INDEX_URL, headers={"User-Agent": "install-zig.py"})
with urllib.request.urlopen(req) as resp:
    index = json.load(resp)

if zig_version not in index:
    available = [k for k in index if k != "master"]
    print(
        f"ERROR: version {zig_version!r} not in index. "
        f"Available releases: {', '.join(sorted(available, reverse=True))}",
        file=sys.stderr,
    )
    sys.exit(1)

release = index[zig_version]

if platform_key not in release:
    available_platforms = [k for k in release if k not in ("date", "notes", "docs", "stdDocs")]
    print(
        f"ERROR: platform {platform_key!r} not found for Zig {zig_version}. "
        f"Available: {', '.join(sorted(available_platforms))}",
        file=sys.stderr,
    )
    sys.exit(1)

entry = release[platform_key]
tarball_url = entry["tarball"]
expected_sha = entry.get("shasum")

# ── Download ───────────────────────────────────────────────────────────────

archive_name = tarball_url.split("/")[-1]
print(f"Downloading Zig {zig_version} from {tarball_url} ...")

with tempfile.TemporaryDirectory() as tmp:
    archive_path = os.path.join(tmp, archive_name)
    urllib.request.urlretrieve(tarball_url, archive_path)

    # ── SHA-256 verification ───────────────────────────────────────────────
    if expected_sha:
        sha256 = hashlib.sha256()
        with open(archive_path, "rb") as f:
            while chunk := f.read(65536):
                sha256.update(chunk)
        actual_sha = sha256.hexdigest()
        if actual_sha != expected_sha:
            print(
                f"ERROR: SHA-256 mismatch for {archive_name}\n"
                f"  expected: {expected_sha}\n"
                f"  actual:   {actual_sha}",
                file=sys.stderr,
            )
            sys.exit(1)
        print("SHA-256 verified.")

    # ── Extract ────────────────────────────────────────────────────────────
    print("Extracting...")
    with tarfile.open(archive_path) as tf:
        tf.extractall(tmp)

    # The archive contains exactly one top-level directory, e.g.
    # "zig-x86_64-linux-0.14.0/". Find it and move it into place.
    candidates = [
        d for d in os.listdir(tmp)
        if os.path.isdir(os.path.join(tmp, d)) and d != os.path.basename(archive_path)
    ]
    if len(candidates) != 1:
        print(
            f"ERROR: expected one extracted directory in {tmp}, found: {candidates}",
            file=sys.stderr,
        )
        sys.exit(1)

    os.makedirs(os.path.dirname(install_dir), exist_ok=True)
    shutil.move(os.path.join(tmp, candidates[0]), install_dir)

print(f"Zig {zig_version} installed to {install_dir}")
