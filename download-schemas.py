#!/usr/bin/env python3
"""
Download GitHub REST API schemas and example responses from the official OpenAPI spec.

Source: https://github.com/github/rest-api-description

Usage:
    python3 download-schemas.py [--api-version VERSION] [--output-dir DIR] [--filter PREFIX]

Examples:
    python3 download-schemas.py
    python3 download-schemas.py --api-version 2022-11-28
    python3 download-schemas.py --filter /orgs
    python3 download-schemas.py --output-dir /tmp/schemas
"""

import argparse
import json
import os
import sys
import urllib.request
from pathlib import Path

BASE_URL = "https://raw.githubusercontent.com/github/rest-api-description/main/descriptions/api.github.com"
DEREF_BASE_URL = f"{BASE_URL}/dereferenced"

# Relevant endpoints
DEFAULT_PATH_PREFIXES = [
    "/repos/{owner}/{repo}",
    "/orgs/{org}/repos",
    "/user/repos",
]


def download_spec(api_version: str) -> dict:
    # Use the dereferenced spec so $ref examples are resolved to actual values
    filename = f"api.github.com.{api_version}.deref.json"
    url = f"{DEREF_BASE_URL}/{filename}"
    print(f"Downloading {url} ...")
    with urllib.request.urlopen(url) as response:
        return json.loads(response.read())


def path_to_dir_name(path: str) -> str:
    """Convert an OpenAPI path like /repos/{owner}/{repo} to a filesystem-safe name."""
    # Strip leading slash, replace remaining slashes with os.sep
    return path.lstrip("/").replace("/", os.sep)


def extract_examples(response_content: dict, status_code: str) -> list[tuple[str, dict]]:
    """Extract (filename, data) pairs from a response content object."""
    results = []
    for media_type, media_data in response_content.items():
        if not media_type.startswith("application/json"):
            continue
        if "examples" in media_data:
            for example_name, example_obj in media_data["examples"].items():
                value = example_obj.get("value", example_obj)
                safe_name = example_name.replace("/", "_").replace(" ", "_")
                results.append((f"example-{status_code}-{safe_name}.json", value))
        elif "example" in media_data:
            results.append((f"example-{status_code}.json", media_data["example"]))
    return results


def save_endpoint(path: str, method: str, operation: dict, output_dir: Path) -> None:
    dir_path = output_dir / path_to_dir_name(path) / method.lower()
    dir_path.mkdir(parents=True, exist_ok=True)

    # Save the full endpoint definition (parameters, request body, responses)
    schema_file = dir_path / "schema.json"
    with open(schema_file, "w") as f:
        json.dump(operation, f, indent=2)

    # Save extracted response examples
    for status_code, response_obj in operation.get("responses", {}).items():
        content = response_obj.get("content", {})
        for filename, data in extract_examples(content, status_code):
            with open(dir_path / filename, "w") as f:
                json.dump(data, f, indent=2)


def matches_any_prefix(path: str, prefixes: list[str]) -> bool:
    return any(path.startswith(prefix) for prefix in prefixes)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--api-version", default="2026-03-10", help="API version (default: 2026-03-10)")
    parser.add_argument(
        "--output-dir",
        default=str(Path(__file__).parent / "schemas"),
        help="Output directory (default: schemas/ next to this script)",
    )
    parser.add_argument(
        "--filter",
        action="append",
        metavar="PREFIX",
        dest="filters",
        help="Extra path prefix to include (can be repeated); replaces defaults if provided",
    )
    args = parser.parse_args()

    prefixes = args.filters if args.filters else DEFAULT_PATH_PREFIXES
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    spec = download_spec(args.api_version)

    # Save the full spec for complete reference
    full_spec_path = output_dir / "openapi.json"
    print(f"Saving full spec to {full_spec_path} ...")
    with open(full_spec_path, "w") as f:
        json.dump(spec, f, indent=2)

    paths = spec.get("paths", {})
    matched = [(path, method, operation)
               for path, methods in paths.items()
               for method, operation in methods.items()
               if isinstance(operation, dict) and matches_any_prefix(path, prefixes)]

    print(f"\nFound {len(matched)} endpoint(s) matching prefixes: {prefixes}\n")

    for path, method, operation in sorted(matched):
        save_endpoint(path, method, operation, output_dir)
        summary = operation.get("summary", "")
        print(f"  {method.upper():7} {path}  —  {summary}")

    print(f"\nDone. Schemas saved to: {output_dir}")


if __name__ == "__main__":
    main()
