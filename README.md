# drifty

A tool to detect and fix drift in GitHub organization and repository settings

## Usage

`drifty --help` lists every flag; `drifty` on its own reports what has drifted
from `./drifty.pkl` and `drifty --fix` applies what it can. An argument drifty
does not recognise is refused rather than ignored, so a typo cannot quietly
turn a fix into a check. See [SPEC.md](SPEC.md#cli-interface).

## Export

Starting a config by hand means guessing at every field GitHub already has an
opinion on. `--export` writes one from what is actually there instead:

```bash
export DRIFTY_GITHUB_TOKEN=ghp_...
drifty --export acme
```

This writes `export.pkl` in the working directory, amending the project's
schema, with only the settings that differ from its defaults — an
organization or repository with nothing unusual configured contributes
nothing to the file:

```pkl
/// Exported by drifty 1.2.3 from acme on 2026-09-07T12:00:00Z.
/// Only settings that differ from the schema defaults are listed; everything
/// absent is at GitHub's default.
amends "https://raw.githubusercontent.com/ArloL/drifty/refs/heads/main/config/drifty.pkl"

organizations {
  ["acme"] {
    description = "Widgets, Inc."
    repositories {
      new {
        name = "widget"
        deleteBranchOnMerge = true
      }
    }
  }
}
```

A setting drifty can see but never writes — a repository's `visibility`, ten
check-only organization settings — is exported as a field with a `//` note
beside it, so the file still matches GitHub without promising a `--fix` that
can never happen.

A group the token could not read at all is named in that entry's `managed`
block and gets a `//` note saying why, so the exported file is one drifty can
check as written rather than one that fails on the same request the export
did. Drop the name once the token can read the group.

See [SPEC.md](SPEC.md#export) for the full set of flags and what does not
round-trip.
