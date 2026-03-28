#!/usr/bin/env python3
"""Install Eclipse Temurin JDK to $JAVA_HOME.

Reads the java version from .tool-versions (e.g. "java temurin-25"),
fetches the latest matching release from GitHub, and installs it.
"""

import json
import os
import re
import shutil
import sys
import tarfile
import tempfile
import urllib.request

TOOL_VERSIONS = os.path.join(os.environ["CLAUDE_PROJECT_DIR"], ".tool-versions")

java_version = None
with open(TOOL_VERSIONS) as f:
    for line in f:
        parts = line.split()
        if len(parts) == 2 and parts[0] == "java":
            java_version = parts[1]
            break

if not java_version:
    print(f"ERROR: no java entry found in {TOOL_VERSIONS}", file=sys.stderr)
    sys.exit(1)

# temurin-25 -> 25
PREFIX = "temurin-"
if not java_version.startswith(PREFIX):
    print(f"ERROR: expected java version to start with {PREFIX!r}, got {java_version!r}", file=sys.stderr)
    sys.exit(1)

major_version = java_version[len(PREFIX):]

install_dir = os.environ["JAVA_HOME"]

if os.path.isdir(install_dir):
    print(f"Temurin {java_version} already installed at {install_dir}, skipping.")
    sys.exit(0)

# Fetch latest release from GitHub releases API
api_url = f"https://api.github.com/repos/adoptium/temurin{major_version}-binaries/releases/latest"
print(f"Fetching latest Temurin {major_version} release info from GitHub...")
req = urllib.request.Request(api_url, headers={"User-Agent": "install-temurin.py"})
with urllib.request.urlopen(req) as resp:
    release = json.load(resp)

tag = release["tag_name"]
assets = release["assets"]

# Find the x64 Linux JDK tar.gz
archive_url = None
for asset in assets:
    name = asset["name"]
    if "x64_linux" in name and name.startswith("OpenJDK") and "jdk" in name and name.endswith(".tar.gz"):
        archive_url = asset["browser_download_url"]
        archive_name = name
        break

if not archive_url:
    print(f"ERROR: no x64 Linux JDK asset found in release {tag}", file=sys.stderr)
    sys.exit(1)

print(f"Downloading Temurin {tag} from {archive_url} ...")
with tempfile.TemporaryDirectory() as tmp:
    archive = os.path.join(tmp, archive_name)
    urllib.request.urlretrieve(archive_url, archive)

    print("Extracting...")
    with tarfile.open(archive, "r:gz") as tf:
        tf.extractall(tmp)

    candidates = [
        d for d in os.listdir(tmp)
        if os.path.isdir(os.path.join(tmp, d)) and d != os.path.basename(archive)
    ]
    if len(candidates) != 1:
        print(f"ERROR: expected one extracted dir in {tmp}, found: {candidates}", file=sys.stderr)
        sys.exit(1)

    os.makedirs(os.path.dirname(install_dir), exist_ok=True)
    shutil.move(os.path.join(tmp, candidates[0]), install_dir)

print(f"Eclipse Temurin {tag} installed to {install_dir}")
