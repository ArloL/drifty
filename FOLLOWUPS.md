# Follow-ups

Things carried here only because something upstream is unfinished. Each entry
says what to delete once it lands, and how to check.

## 1. Self-supplied JNA reachability metadata

**Carrying:** `"com.sun.jna."` in `MAIN_TYPE_PREFIXES`
(`src/test/java/io/github/arlol/githubcheck/ReachabilityMetadata.java`), which
routes the 21 agent-traced `com.sun.jna.*` reflection entries into the
production image instead of the test scope. One of the 21 is required.

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

**Waiting on:**
<https://github.com/oracle/graalvm-reachability-metadata/pull/10114> —
registers the no-arg constructor of all eight `com.sun.jna.ptr` by-reference
types, with a direct-mapping test that fails if any one of them is dropped.
Still open as of 2026-09-19.

Merging is not enough on its own: the entries have to reach us through a
`native-maven-plugin` release that bundles a metadata repository snapshot
containing them. Re-run the check below after a plugin bump, not after the merge
notification. Renovate raises those bumps — 1.1.12 was awaiting its schedule on
2026-09-19 against the pinned 1.1.10 — so the trigger to watch for is its pull
request landing, not a version appearing on Maven Central.

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

## 2. `secret_scanning_extended_metadata` has no established default

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

**Waiting on:** one live read, not an upstream change, and not one drifty's
own account can make — `ArloL` is a personal account, and code security
configurations exist only on an organization. Run drifty against an
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

## 3. `ExportRoundTripTest` does not reach three ruleset conditions

**Carrying:** the round trip's fixture now exercises the organization's
settings, `selected` Actions permissions with both listings behind it,
variables, webhooks, custom property definitions, a code security
configuration with both nullable sub-option objects, teams and their
membership, organization members, a runner group, an organization ruleset
with all six bypass actor types and all three modes, and a ruleset carrying
pull request, status check, workflow and merge queue rules; an
organization-owned repository with its settings, variables, webhook, custom
property values, team access, Pages, environment, repository ruleset, branch
protection and collaborator; an archived repository; and a personal account
whose repository comes from `/user/repos`.

Still outside it: a repository ruleset targeting `tag` or `push`, an
organization ruleset's repository-name and repository-property conditions,
and a deployment branch policy on an environment.

**What cannot be there:** a secret of any kind. GitHub never returns a
secret's value, so a freshly exported config has no baseline for one and the
first run reports `SecretMissingBaseline` — see SPEC.md's "What does not
round-trip". The three secret listings in the fixture are empty for that
reason and not by omission.

**Why the rest matters:** the round trip is what catches a field the assembler
dropped, mis-keyed, or paired with the wrong schema default — a value that
never round-trips looks identical to no value at all until something loads the
file back and compares. Both Critical findings in the 2026-09-07 fix wave (a
webhook's `events` listing unioning with the schema default instead of
replacing it, and a ruleset pattern rule noted instead of exported and then
deleted by the next `--fix`) lived in sections the fixture did not then reach.

**How to check whether it's fixed:** add the condition or policy to the
fixture's stubs and run `ExportRoundTripTest`. Two ways an addition can pass
without proving anything: a stub answering an empty listing exports nothing,
and a stub answering something drifty cannot parse fails the group, which the
export writes out as a `managed` exclusion the check then skips. The test
asserts no group was left unmanaged, which catches the second — a Pages
payload missing one primitive field was caught exactly that way. Against the
first there is only reading the fixture.
