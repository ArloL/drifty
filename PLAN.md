# Feature 9: Immutable Releases Validation

## Context

Feature 9 is partially implemented. `GitHubClient.getImmutableReleases()` and the `ImmutableReleases` record exist but are never used. The feature needs to be wired into config, state, diff, and fix flows, plus tests.

## API

- **GET** `/repos/{owner}/{repo}/immutable-releases` → 200 `{"enabled": true}` or 404 (already implemented)
- **PUT** `/repos/{owner}/{repo}/immutable-releases` → 204 (enable, no request body)
- **DELETE** `/repos/{owner}/{repo}/immutable-releases` → 204 (disable)

## Implementation Steps

### 1. `RepositoryState.java` — add `boolean immutableReleases` as 13th record parameter

### 2. `config/RepositoryArgs.java` — add `immutableReleases` boolean field
- Default: `false` (GitHub default)
- Add: field, getter, builder field + setter + copy, equals/hashCode

### 3. `client/GitHubClient.java` — add enable/disable methods
- `enableImmutableReleases(owner, repo)` — PUT empty body, expect 204 (pattern: `enableVulnerabilityAlerts`)
- `disableImmutableReleases(owner, repo)` — DELETE, expect 204 (pattern: `deletePages`)

### 4. `OrgChecker.java` — wire everything
- **fetchState()**: call `getImmutableReleases()` for non-archived repos, extract boolean, pass to `RepositoryState` constructor
- **computeDiffs()**: `check(diffs, "immutable_releases", desired.immutableReleases(), actual.immutableReleases())`
- **applyFixes()**: if drifted, call enable or disable based on desired value, remove from remaining

### 5. Tests — fix all `RepositoryState` constructor calls (add 13th param)

#### OrgCheckerDiffTest
- Update `StateBuilder`: add `immutableReleases` field (default false), setter, pass in `build()`
- Add test: `drift_immutableReleases_wantTrue_gotFalse`
- Add test: `noDrift_immutableReleases_bothFalse`

#### OrgCheckerFixTest
- Update `goodPublicState()` and `stateWithDetailsOverride()`: add `false` as 13th arg
- Update all other `new RepositoryState(...)` calls
- Add test: `immutableReleasesDisabled_enablesThem` (stub PUT, verify fix)
- Add test: `immutableReleasesEnabled_disablesThem` (stub DELETE, verify fix)

#### GitHubClientRecordingTest
- Add `client.getImmutableReleases("ArloL", "terraform-github")` call

#### GitHubClientPlaybackTest
- Add `getImmutableReleases_returnsRecordedState` test

### 6. `FEATURES.md` — mark Feature 9 as DONE with implementation summary

## Files to Modify
- `src/main/java/io/github/arlol/githubcheck/RepositoryState.java`
- `src/main/java/io/github/arlol/githubcheck/config/RepositoryArgs.java`
- `src/main/java/io/github/arlol/githubcheck/client/GitHubClient.java`
- `src/main/java/io/github/arlol/githubcheck/OrgChecker.java`
- `src/test/java/io/github/arlol/githubcheck/OrgCheckerDiffTest.java`
- `src/test/java/io/github/arlol/githubcheck/OrgCheckerFixTest.java`
- `src/test/java/io/github/arlol/githubcheck/client/GitHubClientRecordingTest.java`
- `src/test/java/io/github/arlol/githubcheck/client/GitHubClientPlaybackTest.java`
- `FEATURES.md`

## Verification
1. `./mvnw verify` — all tests pass
2. New diff tests verify drift detection for immutable_releases
3. New fix tests (WireMock) verify enable/disable API calls
4. Recording test captures real API interaction for playback
