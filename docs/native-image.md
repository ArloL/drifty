# Native image

- **Native build time is mostly builder memory, and the macOS runner has 7GB.**
  `macos-latest` has 3 cores and 7GB, and the native test image is what makes
  its job the slowest by far; the builder's peak RSS decides whether that
  build runs or thrashes. `-H:+IncludeAllLocales` cost 2GB of it for ~49,000
  reflection-registered locale classes the tool never formats with, so it is
  gone from `native-image.properties`; do not bring it back for a
  locale-sensitive feature without measuring the test image on macOS. The
  test image builds with `quickBuild` (`-Ob`) because it verifies metadata,
  not speed; the production image stays at `-O2`. The build report's
  "registered for reflection" and "Peak RSS" lines are the numbers to watch.

- **Take the collection types Pkl's mapper already has metadata for.**
  `PklConfigLoader` walks the `organizations` and `users` mappings key by key
  and builds the `LinkedHashMap` itself, because
  `.as(LinkedHashMap<String, Organization>)` fails in the shipped binary:
  `PMapToMap` instantiates the raw target class reflectively, and
  pkl-config-java-native registers only `HashMap`, `ArrayList`, `HashSet`,
  `TreeMap`, `TreeSet` and `ArrayDeque`. Any other target maps fine on the JVM
  and ends a user's first run with
  `ConversionException: ... because no conversion was found`.
  `NativeExecutableIT.selfTestWithConfig` runs the real binary against
  `config/example.pkl` so that is a build failure instead.
  (`Mapping` fields inside the records are `Map`, which the mapper fills with a
  `HashMap` — do not rely on their iteration order.)
- **`--self-test` is what covers the shipped image.** It is the only place the
  production binary's reflective paths — libsodium through JNA, and a full Pkl
  evaluation when `--config` is passed — are exercised. Native *test* image
  runs do not: they see the test-scoped metadata too.

The native image needs reflection/resource metadata for everything Jackson and
Pkl touch reflectively. It is **scope-split** so the shipped image stays lean:

- `src/main/resources/META-INF/native-image/reachability-metadata.json` —
  production scope (project records, Jackson, Pkl/Truffle, JNA/lazysodium, TLS).
- `src/test/resources/META-INF/native-image/reachability-metadata.json` —
  test-only scope (WireMock, Jetty, JMX/JFR, JUnit/surefire/AssertJ). The native
  *test* image sees both because test resources are on its classpath; the
  production image only sees the main file.

Do **not** commit the raw tracing-agent dump into the main file — it mixes
~100+ test-only entries into the shipped image. The caller-based
`access-filter.json` cannot remove them (the reflective calls originate in
JDK/JSSE code, not the test libraries). Instead, regenerate like this:

```bash
./mvnw test -Dagent=true                # retrace into target/native/agent-output
./mvnw test-compile                     # compile the tool onto the test classpath
./mvnw exec:java@reachability-metadata  # partition into the two scoped files
./mvnw -DskipTests package              # build + smoke-run the production image
./mvnw clean test                       # build + run the native test image
```

The splitter is `ReachabilityMetadata` (a `main` in `src/test/java`, so it uses
test-scoped ClassGraph without shipping it). Both reflection and resources are
partitioned by a production **allowlist**, with everything else supplied by the
GraalVM metadata repository and routed to test scope:

- reflection: only `io.github.arlol.*` types and the `com.goterl.lazysodium` /
  `com.sun.jna` binding; everything else is repository-supplied;
- resources: only Pkl's own resources and a platform-agnostic `**/libsodium.*`
  glob for the lazysodium native library.

`io.github.arlol.*` is a package prefix, not a record filter: `DriftyState` and
its `RepoState`/`OrgState` inner classes are plain classes and are matched the
same way. Their metadata comes from what the suite traces, so a field or an
inner class Jackson only touches on a code path no test exercises is silently
absent from the shipped image — `StateStoreTest` round-trips both a repository
and an organization secret record for that reason.

The main file is then augmented with every public `client`/`pkl` record via
ClassGraph so the project's own types are registered even if untested, and a
standard class in the `pkl` package gets `allPublicFields` besides.
`pkl-codegen-java` emits a schema *class*, not a record — `Drifty.Ruleset` has
public final fields and `withX` methods — so it takes the standard-class
branch, where the constructor alone is what Pkl's own mapper needs. The fields
are registered because two tests read one reflectively: the maximality guards
in `RulesetFixConvergenceTest` and `BranchProtectionFixConvergenceTest`, which
exist so a field added to `config/drifty.pkl` cannot arrive at its default and
go unexercised. It is scoped to `pkl` rather than applied to every standard
class the scan sees, or the same rule would register the public fields of
`GitHubClient`, three request builders and forty `client` enums, none of which
anything reflects over.

**`Class.getFields()` needs no metadata; `Field.get` does.** That is why
`SchemaCoverageTest` has walked the same classes for as long as it has existed
and never needed this — it reads names. A `MissingReflectionRegistrationError`
in the *native test image* was the first sign, and only there: the JVM run is
green, and so is `./mvnw verify -DskipNativeTests`. Anything new that reflects
over a project type wants the full `./mvnw clean verify` before it is pushed.

The reflection allowlist was established empirically: the production image was
rebuilt with progressively fewer entries and smoke-tested (Pkl load + TLS to
GitHub), while the native test suite (Jackson round-trips + libsodium crypto)
guarded the rest. Removing the JNA/lazysodium entries breaks `SecretsTest`
(`UnsatisfiedLinkError: sodium_init`), which is why they stay.

Note: don't pass `-Dexec.arguments` on a full lifecycle invocation — it would
leak into the phase-bound `pkl-codegen-java` exec execution. The splitter needs
no arguments; it reads the default agent-output path.

