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
import re
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


SPEC_ETAG = None


def download_spec(api_version: str) -> dict:
    # Use the dereferenced spec so $ref examples are resolved to actual values
    global SPEC_ETAG
    filename = f"api.github.com.{api_version}.deref.json"
    url = f"{DEREF_BASE_URL}/{filename}"
    print(f"Downloading {url} ...")
    with urllib.request.urlopen(url) as response:
        SPEC_ETAG = response.headers.get("ETag")
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


# ─── Contract mode ────────────────────────────────────────────────────────────
#
# Reduces the spec to what a wire-shape check needs and writes one committed
# file. See docs/superpowers/specs/2026-09-19-github-api-contract-design.md.

CLIENT_SOURCES = Path(__file__).parent / "src/main/java/io/github/arlol/githubcheck/client"
CONTRACT_FILE = Path(__file__).parent / "src/test/resources/github-api-contract.json"
ENDPOINT_LITERAL = re.compile(r'"((?:GET|POST|PUT|PATCH|DELETE) /[^"]*)"')

# Keys worth keeping. Everything else in an OpenAPI node -- descriptions,
# examples, formats, min/max -- describes values, and this check is about
# names, types and enum membership.
KEEP = ("type", "enum", "nullable", "properties", "required", "items")


def scan_endpoints() -> list[str]:
    """Every endpoint literal a @GitHubEndpoint annotation carries.

    Reading Java annotations from Python would mean putting this script in the
    build lifecycle. Scanning the sources loses nothing: GitHubApiContractTest
    fails when an annotated endpoint is missing from the contract file, so a
    typo or a skipped refresh is caught where being wrong would otherwise mean
    checking nothing.
    """
    found = set()
    for java in sorted(CLIENT_SOURCES.glob("*.java")):
        found.update(ENDPOINT_LITERAL.findall(java.read_text()))
    return sorted(found)


def discriminator_of(branches: list) -> str | None:
    """The property every branch pins to one value, or None.

    GitHub writes ruleset rules as a 25-branch oneOf with no `discriminator`
    keyword, each branch pinning `type` to a single-value enum. Merging those
    branches would check every Rule subtype against the union of all rules'
    fields and so find nothing wrong, ever.
    """
    candidates = None
    for branch in branches:
        if not isinstance(branch, dict):
            return None
        pinned = set()
        for name, sub in (branch.get("properties") or {}).items():
            if not isinstance(sub, dict):
                continue
            if "const" in sub:
                pinned.add(name)
            elif isinstance(sub.get("enum"), list) and len(sub["enum"]) == 1:
                pinned.add(name)
        candidates = pinned if candidates is None else candidates & pinned
        if not candidates:
            return None
    return sorted(candidates)[0] if candidates else None


def pinned_value(branch: dict, key: str):
    sub = branch.get("properties", {}).get(key, {})
    return sub["const"] if "const" in sub else sub["enum"][0]


def merge_into(target: dict, addition: dict) -> None:
    for key, value in addition.items():
        if key == "properties":
            target.setdefault("properties", {}).update(value)
        elif key == "required":
            target["required"] = sorted(set(target.get("required", [])) | set(value))
        elif key == "enum":
            target["enum"] = sorted(set(target.get("enum", [])) | set(value))
        elif key == "type" and "type" in target and target["type"] != value:
            # Branches that disagree about the JSON type say nothing about it.
            # `source` on PUT /repos/{owner}/{repo}/pages is a string or an
            # object; keeping whichever branch came first would report the
            # record as the wrong shape.
            target["type"] = None
        else:
            target.setdefault(key, value)
    if target.get("type") is None:
        target.pop("type", None)


def reduce_schema(schema, depth: int = 0):
    """One OpenAPI node, stripped to names, types, enum values and nullability."""
    if not isinstance(schema, dict) or depth > 12:
        return None

    out: dict = {}

    for combinator in ("allOf", "anyOf", "oneOf"):
        branches = schema.get(combinator)
        if not isinstance(branches, list) or not branches:
            continue
        key = None
        if combinator == "oneOf":
            key = (schema.get("discriminator") or {}).get("propertyName") or discriminator_of(branches)
        if key:
            # Discriminated: keep the branches apart, keyed by their value.
            variants = {}
            for branch in branches:
                reduced = reduce_schema(branch, depth + 1)
                if reduced is not None:
                    variants[str(pinned_value(branch, key))] = reduced
            if variants:
                out["oneOf"] = variants
                out["discriminator"] = key
        else:
            # Jackson flattens an undiscriminated union into one record, so
            # the check compares against the union of the branches.
            for branch in branches:
                reduced = reduce_schema(branch, depth + 1)
                if reduced:
                    merge_into(out, reduced)

    for key in KEEP:
        if key not in schema:
            continue
        value = schema[key]
        if key == "type" and isinstance(value, list):
            value = sorted(str(v) for v in value)
            if "null" in value:
                out["nullable"] = True
                value = [v for v in value if v != "null"]
            value = value[0] if len(value) == 1 else value
            out["type"] = value
        elif key == "type":
            out["type"] = value
        elif key == "enum":
            values = sorted({v for v in value if isinstance(v, str)})
            if len(value) != len(values) and any(v is None for v in value):
                out["nullable"] = True
            if values:
                out["enum"] = values
        elif key == "nullable":
            if value:
                out["nullable"] = True
        elif key == "required":
            out["required"] = sorted(str(v) for v in value if isinstance(v, str))
        elif key == "properties" and isinstance(value, dict):
            reduced = {}
            for name, sub in value.items():
                child = reduce_schema(sub, depth + 1)
                reduced[name] = child if child is not None else {}
            out["properties"] = reduced
        elif key == "items":
            child = reduce_schema(value, depth + 1)
            if child is not None:
                out["items"] = child

    return out or None


def json_body(container: dict):
    for media_type, media in (container.get("content") or {}).items():
        if media_type.startswith("application/json") and "schema" in media:
            return media["schema"]
    return None


def build_contract(spec: dict, endpoints: list[str]) -> tuple[dict, list[str]]:
    paths = spec.get("paths", {})
    result: dict = {}
    unknown: list[str] = []
    for endpoint in endpoints:
        method, _, path = endpoint.partition(" ")
        operation = paths.get(path, {}).get(method.lower())
        if not isinstance(operation, dict):
            unknown.append(endpoint)
            continue
        entry = {}
        body = json_body(operation.get("requestBody") or {})
        if body is not None:
            reduced = reduce_schema(body)
            if reduced:
                entry["request"] = reduced
        for status in sorted((operation.get("responses") or {})):
            if not status.startswith("2"):
                continue
            body = json_body(operation["responses"][status] or {})
            if body is None:
                continue
            reduced = reduce_schema(body)
            if reduced:
                entry["response"] = reduced
                break
        if entry:
            result[endpoint] = entry
    return result, unknown


def spec_identity() -> str:
    """What the contract was cut from.

    The raw file's ETag, which the download already carries: asking the API
    for a commit sha costs a second request that an unauthenticated run is
    answered 403 to, and identifies the same thing less directly. Refresh is
    manual, so this line is the only thing that says how old the copy is.
    """
    return (SPEC_ETAG or "unknown").strip('"')


def write_contract(api_version: str) -> None:
    endpoints = scan_endpoints()
    if not endpoints:
        sys.exit(
            "No endpoint literals found. Records name their endpoints with "
            "@GitHubEndpoint; without one there is nothing to extract."
        )
    print(f"Found {len(endpoints)} endpoint(s) named by @GitHubEndpoint annotations.")

    spec = download_spec(api_version)
    contract, unknown = build_contract(spec, endpoints)

    if unknown:
        sys.exit(
            "Not found in the spec:\n  "
            + "\n  ".join(unknown)
            + "\n\nThe path must be spelled as OpenAPI spells it, braces included."
        )

    document = {
        "apiVersion": api_version,
        "specEtag": spec_identity(),
        "endpoints": contract,
    }
    CONTRACT_FILE.parent.mkdir(parents=True, exist_ok=True)
    blob = json.dumps(document, indent=1, sort_keys=True) + "\n"
    CONTRACT_FILE.write_text(blob)

    covered = sum(1 for e in contract.values() if "request" in e)
    print(f"\nWrote {CONTRACT_FILE} — {len(contract)} endpoint(s), "
          f"{covered} with a request body, {len(blob) / 1024:.0f} KB.")

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
    parser.add_argument(
        "--contract",
        action="store_true",
        help="Write the committed wire contract instead of the schemas/ tree",
    )
    args = parser.parse_args()

    if args.contract:
        write_contract(args.api_version)
        return

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
