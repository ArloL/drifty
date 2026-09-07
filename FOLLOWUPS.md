# Follow-ups

Things carried here only because something upstream is unfinished. Each entry
says what to delete once it lands, and how to check.

## 1. Self-supplied JNA reachability metadata

**Carrying:** `"com.sun.jna."` in `MAIN_TYPE_PREFIXES`
(`src/test/java/io/github/arlol/githubcheck/ReachabilityMetadata.java`), which
routes the 21 agent-traced `com.sun.jna.*` reflection entries into the
production image instead of the test scope.

**Why:** the GraalVM reachability-metadata repository's JNA config only covers
interface mapping (`Native.load`). lazysodium uses direct mapping
(`Native.register`), which builds the libffi call descriptors in Java and
reflectively instantiates types the config never registers. Verified on
2026-07-28 with JNA 5.19.1: with the block removed, the production image dies
at `new SodiumJava()` with

```
NoSuchMethodException: com.sun.jna.Structure$FFIType.<init>()
```

and, once that one entry is restored, with

```
NoSuchMethodException: com.sun.jna.NativeLong.<init>()
    at com.sun.jna.NativeMappedConverter.defaultValue(NativeMappedConverter.java:65)
    at com.sun.jna.Native.register(Native.java:1965)
```

This is a property of the binding mode, not the JNA version — 5.18.1 fails
identically.

**Waiting on:**
<https://github.com/oracle/graalvm-reachability-metadata/pull/9121> — adds the
`Structure$FFIType`, `Structure$FFIType$size_t` and `NativeLong` entries plus a
direct-mapping test covering both failures above. Open as of 2026-07-28.

Merging is not enough on its own: the entries have to reach us through a
`native-maven-plugin` release that bundles a metadata repository snapshot
containing them. Re-run the check below after a plugin bump, not after the
merge notification.

Whether those four entries are *all* that direct mapping needs is unverified —
they are what this project's code path happens to hit. If the check below turns
up a third missing registration, that is another upstream PR, not a local fix.

**How to check whether it can go:**

```bash
# drop "com.sun.jna." from MAIN_TYPE_PREFIXES, then regenerate and rebuild
./mvnw test -Dagent=true
./mvnw test-compile
./mvnw exec:java@reachability-metadata
./mvnw -DskipTests package
./target/drifty-macos-0.0.1-SNAPSHOT --self-test   # must print "self-test OK"
```

Keep `--self-test` and `NativeExecutableIT.selfTest` regardless — they are the
guard that catches this class of breakage in the shipped binary, not just a
scaffold for this particular workaround.

## 2. JNA 5.19.x is not a tested version upstream

**Carrying:** nothing in the build any more — `pom.xml` is on JNA **5.19.1**.
This entry exists so the reason is not rediscovered.

**Why it looked like a pin:** 5.19.x is absent from `tested-versions` in
`metadata/net.java.dev.jna/jna/index.json`. That does **not** mean "no metadata"
— `native-maven-plugin` calls `Query.useLatestConfigWhenVersionIsUntested()`
unconditionally, so an untested version falls back to the latest config
directory (`net.java.dev.jna/jna/5.8.0`). 5.19.1 therefore gets the same
metadata 5.18.1 does, with the same gaps. The earlier pin to 5.18.1 was
therefore treating the wrong cause; see item 1 for the real one.

**Waiting on:**
<https://github.com/oracle/graalvm-reachability-metadata/issues/7741> — 5.19.x
cannot be added to `tested-versions` while the TCK's `future-defaults-all` mode
fails for it with `VMError$HostedError: Bulk queries can only be set with
'name' which does not allow run-time conditions`. That failure does not
originate in the JNA metadata file (it contains no bulk queries), so it is not
something this project can fix.

**When it lands:** nothing to remove here. It only matters as a precondition
for upstream being able to *test* what item 1 depends on.

## 3. `maven-shared-utils` on the native-maven-plugin classpath

**Carrying:** an explicit `org.apache.maven.shared:maven-shared-utils`
dependency on the `native-maven-plugin` declaration in `pom.xml`.

**Why:** 1.1.6 calls `org.apache.maven.shared.utils.logging.MessageUtils` but
no longer receives maven-shared-utils from the Maven core classpath, so the
test/compile goals fail with `NoClassDefFoundError` without it.

**How to check whether it can go:** drop the `<dependencies>` block from the
plugin declaration and run `./mvnw verify`. If it completes, the upstream
plugin has fixed its own classpath and the workaround can be deleted.

## 4. `secret_scanning_extended_metadata` has no established default

**Carrying:** nothing in the code — `CodeSecurityConfiguration` in
`config/drifty.pkl` does not declare the field, so drifty neither compares nor
sends it.

**Why:** GitHub's OpenAPI spec (2026-03-10) has
`secret_scanning_extended_metadata` on the code security configuration GET,
POST and PATCH as the usual `enabled | disabled | not_set` toggle, so it is
managed exactly like the other seventeen once it has a default. The spec
supplies none: the field carries no `default`, and every example response in
`schemas/orgs/{org}/code-security/configurations/` predates it and answers
`null`. Every other field's Pkl default is GitHub's own, which is what lets a
configuration created with only a name report no drift; guessing this one
would make drifty report drift on configurations nobody has touched, in
whichever direction the guess was wrong.

**Waiting on:** one live read, not an upstream change. Run drifty against an
organization with a configuration created bare, or:

```bash
curl -H "Authorization: Bearer $DRIFTY_GITHUB_TOKEN" \
  https://api.github.com/orgs/<org>/code-security/configurations \
  | jq '.[] | {name, secret_scanning_extended_metadata}'
```

**When it lands:** add the field to `CodeSecurityConfiguration` with the value
GitHub returns for an untouched configuration, a `Setting` row in
`OrgCodeSecurityConfigurationsDriftGroup`, a `settings.put` in
`ActualTypes.codeSecurityConfiguration`, the response and request record
components, and the SPEC.md table; then delete this entry. If GitHub answers
the field as null on a bare configuration, it reads as `not_set` — the
`settings.replaceAll` in `ActualTypes` already does that for every toggle.

## 5. `RepositoryChecker.checkOne` does not catch `GitHubApiException`

Unlike 1–3, nothing upstream needs to move for this one — it is a local bug,
carried here instead of fixed because fixing it is a behavior change outside
the work that found it.

**Carrying:** the `catch (InterruptedException e)` / `catch (IOException e)`
pair at the end of `RepositoryChecker.checkOne`
(`src/main/java/io/github/arlol/githubcheck/RepositoryChecker.java`), with no
`catch (GitHubApiException e)` beside them.

**Why it's a bug:** `GitHubClient` converts every failed request into a
`GitHubApiException`, which extends `RuntimeException`, not `IOException`. A
403 or 5xx partway through `fetchState` — a group's own request, not the
`GET /repos/{owner}/{repo}` already wrapped by `FetchFailures` — therefore
escapes `checkOne` uncaught. `checkOne` runs inside a virtual-thread
`Callable` (`check`'s `executor.submit`), so the exception resurfaces at
`Future.get()` wrapped in an `ExecutionException`, which `check` does not
catch either — it declares `throws ExecutionException` and lets it out.
`GitHubCheck.main` has no handler for that above `check(...)`, so the whole
run dies with a stack trace and exit code 1 instead of reporting the one
repository as `CheckResult.Status.ERROR` and continuing with the rest.

**Where it's asymmetric:** `OrganizationChecker.checkOne` already has a
`catch (GitHubApiException e)` arm that returns an `error` entry. Only the
repository side is missing it.

**Why this surfaced now, and why it wasn't fixed here:** `--export`
(`ExportRunner.exportRepositories`) calls `RepositoryChecker.fetchState`
directly instead of going through `checkOne`, specifically to catch
`GitHubApiException` itself per repository — a comment on `ExportRunner`
points here. Fixing `checkOne` is a behavior change to the existing
check/fix path (every repository after the one that 403s currently never
gets reported at all; catching the exception changes that to one `ERROR`
entry and the rest reported normally), which is outside the export feature
this follow-up was written during.

**How to check whether it's fixed:** add a `catch (GitHubApiException e)`
arm to `checkOne` mirroring `OrganizationChecker.checkOne`'s, then a test
that gives one repository in a batch a client that throws
`GitHubApiException` from a managed group's request and asserts the other
repositories still report and the failing one comes back as
`CheckResult.Status.ERROR` rather than aborting `check()`.
