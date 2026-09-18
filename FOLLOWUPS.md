# Follow-ups

Things carried here only because something upstream is unfinished. Each entry
says what to delete once it lands, and how to check.

## 1. Self-supplied JNA reachability metadata

**Carrying:** `"com.sun.jna."` in `MAIN_TYPE_PREFIXES`
(`src/test/java/io/github/arlol/githubcheck/ReachabilityMetadata.java`), which
routes the 21 agent-traced `com.sun.jna.*` reflection entries into the
production image instead of the test scope. One of the 21 is load-bearing.

**Why:** lazysodium binds through direct mapping (`Native.register`), which
builds the libffi call descriptors in Java rather than native code and
instantiates the parameter types reflectively. `PointerType` implements
`NativeMapped`, so `NativeMappedConverter` constructs a default value for every
`ByReference` parameter, and the metadata repository registers no
`com.sun.jna.ptr.*` type at all. With the block removed the production image
dies at `new SodiumJava()` with

```
NoSuchMethodException: com.sun.jna.ptr.IntByReference.<init>()
    at com.sun.jna.NativeMappedConverter.defaultValue(NativeMappedConverter.java:65)
    at com.sun.jna.Structure$FFIType.get(Structure.java:2255)
    at com.sun.jna.Native.register(Native.java:1982)
```

Verified 2026-09-18 on native-maven-plugin 1.1.10 with JNA 5.19.1: with
`com.sun.jna.ptr.IntByReference` as the only self-supplied entry, `--self-test`
passes both with and without `--config`. The other twenty now come from the
repository:
<https://github.com/oracle/graalvm-reachability-metadata/pull/9121> merged, and
the `Structure$FFIType`, `Structure$FFIType$size_t` and `NativeLong`
constructors it added ship in the plugin's bundled snapshot. Narrowing the carry
to that one type therefore waits on nothing.

Which `ByReference` types direct mapping instantiates is decided by lazysodium's
signatures, not by JNA: a binding taking a different one adds a second missing
registration with no upstream change involved.

**Waiting on:** a PR registering the `com.sun.jna.ptr.*` constructors, the way
#9121 registered the `Structure$FFIType` ones. None is open as of 2026-09-18.

Merging is not enough on its own: the entries have to reach us through a
`native-maven-plugin` release that bundles a metadata repository snapshot
containing them. Re-run the check below after a plugin bump, not after the merge
notification.

**How to check whether it can go:**

```bash
# delete every com.sun.jna.* entry from
# src/main/resources/META-INF/native-image/reachability-metadata.json
./mvnw -DskipTests package
./target/drifty-linux-0.0.1-SNAPSHOT --self-test   # must print "self-test OK"
```

A pass means `"com.sun.jna."` comes out of `MAIN_TYPE_PREFIXES`; regenerate both
metadata files afterwards with the sequence in CLAUDE.md.

Keep `--self-test` and `NativeExecutableIT.selfTest` regardless — they are the
guard that catches this class of breakage in the shipped binary, not just a
scaffold for this particular workaround.

## 2. `maven-shared-utils` on the native-maven-plugin classpath

**Carrying:** an explicit `org.apache.maven.shared:maven-shared-utils`
dependency on the `native-maven-plugin` declaration in `pom.xml`.

**Why:** 1.1.10 calls `org.apache.maven.shared.utils.logging.MessageUtils` but
no longer receives maven-shared-utils from the Maven core classpath, so the
test/compile goals fail with `NoClassDefFoundError` without it.

**How to check whether it can go:** drop the `<dependencies>` block from the
plugin declaration and run `./mvnw verify`. If it completes, the upstream
plugin has fixed its own classpath and the workaround can be deleted.

## 3. `secret_scanning_extended_metadata` has no established default

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

## 4. `ExportRoundTripTest` covers roughly a third of the exporters

Unlike 1 and 2, nothing upstream needs to move for this one — it is a
test-coverage gap, carried here instead of closed because closing it
properly (a dedicated all-drifted fixture per section, the way
`SchemaCoverageTest`'s own "Not covered" list already admits for the plain
field-name check) is a task in its own right, not something to fold into
whichever change happens to touch the export next.

**Carrying:** `ExportRoundTripTest`'s single WireMock fixture exercises an
organization's settings, actions permissions, one ruleset and one repository
with a handful of its own settings. It does not touch: org secrets and
variables, custom properties, code security configurations, runner groups,
org members, `selected` Actions mode, repository webhooks, branch
protections, repository rulesets, collaborators, custom property values,
Pages, bypass actors, status checks, workflows, merge queue, an archived
repository, or a `users` account.

**Why it matters:** the round trip is what catches a field the assembler
dropped, mis-keyed, or paired with the wrong schema default — a value that
never round-trips looks identical to no value at all until something loads
the file back and compares. Both Critical findings in the 2026-09-07 fix wave
(a webhook's `events` listing unioning with the schema default instead of
replacing it, and a ruleset pattern rule noted instead of exported and then
deleted by the next `--fix`) lived in sections this fixture never reached;
an exporter's own unit tests caught neither, because both assert what one
field renders as, not whether the file the export produces is one drifty
itself agrees has zero drift. Every section still outside this fixture is
exposed to the same class of bug with nothing here to catch it.

**What already closed the two known holes:** `WebhookExporterTest` gained a
case whose `events` exclude `push`, and `ExportRoundTripTest`'s own webhook
fixture had `push` removed from its `events` list, closing the listing-
replacement hole directly; `RulesetExporterTest` and `ActualTypesTest` gained
cases for a pattern rule's name, negate flag and operator, closing the
pattern-rule hole. Neither addition widened `ExportRoundTripTest`'s account-
level fixture — they cover the same ground at the unit level, which is
narrower than a round trip but was enough to pin both specific regressions.

**How to check whether it's fixed:** extend the WireMock stubs in
`ExportRoundTripTest` (or add sibling tests using the same pattern) so each
section in the list above appears at least once with a non-default value,
export it, load the export back through `PklConfigLoader`, and assert
`GitHubCheck.check` reports zero drift — the same property the existing test
checks, just reaching further into the schema.
