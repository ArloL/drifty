# Security

## Reporting a vulnerability

Report privately through GitHub:
[**Report a vulnerability**](https://github.com/ArloL/drifty/security/advisories/new).
That opens a draft advisory only you and the maintainer can read.

Please do not open a public issue for a vulnerability, and do not include a
real token, a real secret value or a real `drifty-state.json` in the report —
a redacted excerpt is enough to describe any of them.

Expect a first reply within a week. drifty is maintained by one person as a
side project, so that is an intention rather than an SLA.

## What is supported

The latest release. Versions are calver (`vYYMM.0.N`) and roll forward; there
are no maintenance branches, so a fix ships in the next release rather than
being backported.

## The token is the sensitive part

drifty reads and writes an account's settings, so the token it is given is the
main thing worth protecting.

**It is only ever read from `DRIFTY_GITHUB_TOKEN`.** There is no `--token`
argument, deliberately: an argument is visible in `ps`, in shell history and in
a CI log that echoes its command line, and an environment variable is not.
Keep it that way.

**Give it the least it needs.** The documented scopes — `repo`, `admin:org`,
`workflow` — are what a config managing *everything* needs. A config managing
less needs less, and a token that cannot read a group is not a failure: drifty
reports the group as unreadable and `--export` writes it into the exported
file's `managed` block so the config it produces is one that still checks. Run
a check before a `--fix` and narrow the token until the check stops reporting
anything you did not intend to manage.

**A check writes.** Only with `--fix` does drifty change GitHub, but every run
writes the state file (below). Read-only in the GitHub sense is `drifty`
without `--fix`.

## The state file

`drifty-state.json` (`--state`) holds two things, and they have different
sensitivity:

- **Secret baselines.** Not secret values — GitHub never returns those. Each
  entry is the secret's `updated_at` plus a *salted* hash of the value drifty
  last pushed, which is what lets it notice a rotation. The hash is not
  reversible to the secret.
- **Cached HTTP responses.** Bodies of `GET`s drifty has made, kept to answer
  `304`s. This is the part to think about: it contains whatever those
  endpoints returned — organization settings, member and collaborator logins,
  webhook payload URLs.

drifty creates it readable and writable by its owner alone (`0600`), and sets
that mode as an attribute of the create rather than as a chmod after the
write, so there is no window in which the cached bodies sit in a
world-readable file — which on a shared CI runner is the whole of the
exposure. Windows has no POSIX mode and the file inherits the directory's ACL
there instead. A file an earlier version left readable is tightened by the
next run, because each save lands through a fresh temp file and an atomic
move.

Beyond that, treat it as you would any build cache holding account metadata:
it belongs next to the config, not in a public repository or a shared
artifact. Deleting it is always safe — the next run costs one uncached check
and, if a secret baseline is lost, reports the secret as needing a baseline
rather than doing anything silently.

## Secret values

`--fix` takes secret values from `DRIFTY_GITHUB_SECRETS`, a JSON object, and
never from the config file — a config is meant to be committed. Values are
encrypted with libsodium's sealed boxes (`crypto_box_seal`) against the
repository's or organization's public key before they leave the process, which
is what the GitHub secrets API requires. drifty does not log them and does not
write them to the state file.

## Release integrity

Release assets carry [build provenance
attestations](https://github.com/ArloL/drifty/attestations) produced by
`actions/attest-build-provenance` in the release workflow, so an asset can be
traced to the workflow run and commit that built it. `mise` verifies them when
`github_attestations` is enabled. Verify manually with:

```bash
gh attestation verify drifty-linux-x64.tar.gz --repo ArloL/drifty
```

## Dependencies and supply chain

- Renovate opens dependency updates on a monthly schedule, and immediately for
  anything with a published vulnerability.
- GitHub Actions are pinned to commit SHAs, not tags.
- CodeQL and zizmor (workflow-specific static analysis) run on every pull
  request.
- The vendored GitHub API contract is refreshed monthly by a workflow that
  opens a pull request, so the spec drifty checks itself against does not
  silently age.
