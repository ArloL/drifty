# Null checking with NullAway

**Null is a value drifty means, so the compiler checks it.** Error Prone runs
with every check disabled but NullAway, at `ERROR`, over `src/main` only. It is
JSpecify mode against the `jspecify` dependency that had sat on the classpath
with nothing reading it, so `@Nullable` is a TYPE_USE annotation: on a
qualified nested type it goes before the simple name
(`ActualRuleset.@Nullable RulePattern`), not before the qualifier. Three rules
follow.

- **Prefer saying what is true over silencing the checker.** The 392
  annotations this needed were almost all facts the javadoc already stated —
  `ResponseCache.lookup` was documented "or null when there is none",
  `OrgRunnerGroupsDriftGroup.ids` "or null when one is unknown" — and
  over-annotating is self-correcting: mark a parameter `@Nullable` that is
  never null and the dereference inside it becomes the next error.
- **A guard the checker cannot see is `Objects.requireNonNull` with a reason,
  never a cast or a silenced warning.** Five exist: `started.get(name)` in
  `RepositoryChecker`, `setting.write()` in `SettingTable`, `Section.get()`'s
  value, `present()` on the three organization groups whose section is null
  only for a group `createDriftGroups`' own filter drops before `detectDrift`
  runs, and `PklConfigLoader.byLogin`'s mapping key — the raw value arrives as
  `Map<?, ?>`, whose key type JSpecify reads as `@Nullable Object`, where the
  schema types both account blocks `Mapping<String, …>` and Pkl has no way to
  write a null key.
- **A finding in a file the build did not recompile is a finding you do not
  see.** `byLogin` was the one above, and it reached CI on four platforms
  while `./mvnw verify` stayed green here: a non-clean build recompiles only
  what changed, and Error Prone runs on what javac compiles. Run `./mvnw clean
  verify` before pushing, not `verify`.
- **A fallback that may be null wants `<T extends @Nullable Object>`, not a
  cast.** `Fanout.read` and `FetchFailures.read` take one; a caller passing a
  non-null fallback still gets a non-null `T`, and the call site says which it
  is by declaring `Supplier<@Nullable ActualWorkflowPermissions>`.

Test sources are deliberately outside it — the `default-testCompile` execution
overrides both `compilerArgs` and `annotationProcessorPaths` away. Test code
builds absent values on purpose (a response with the section missing, a secret
with no baseline), which is the behaviour under test: 526 findings there
against zero in `src/main`.

