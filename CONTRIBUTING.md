# Contributing

## Build it

```bash
./mvnw verify
```

That is the whole gate. On a GraalVM JDK it also builds the native image and
runs the suite again inside it, which takes a few minutes; iterate with
`-DskipNativeTests` and run the full thing once before you push.

```bash
./mvnw exec:java          # run against config/example.pkl
```

Java 25 on GraalVM, pinned in `.tool-versions`. [mise](https://mise.jdx.dev)
installs it.

## What the build checks

Worth knowing before a failure surprises you, because several of these are
specific to this project:

| Check | Fails when |
| --- | --- |
| `GitHubApiContractTest` | a wire record no longer matches GitHub's OpenAPI spec |
| `ConfigSpellingTest` / `PklTypesTest` | a client enum and the schema's literal for it disagree |
| `SchemaCoverageTest` | a new schema field has no exporter line |
| `ExportRoundTripTest` | an exported config does not load back clean, or is not `pkl format` output |
| `TrackedPklFilesAreFormattedTest` | a `.pkl` file in the repository is not formatted |
| `*RequestShapeTest` | a read got deeper, or sends more requests per entity |
| `*FixConvergenceTest` | a field the check reports but `--fix` never writes, so the drift survives the fix |
| `jacoco:check` | line or branch coverage drops below 80% |
| `pitest` (monthly, or `workflow_dispatch`) | the mutation score over `drift`/`export` drops below 92% — not a required check, so run it yourself with `./mvnw test-compile pitest:mutationCoverage` when you add a table row |
| NullAway | `src/main` dereferences something `@Nullable`, or a signature hides that it can be null |
| the formatter plugin | never locally — it rewrites your source in place. CI runs `git diff --exit-code` after the build, so commit what it wrote |

CI additionally runs actionlint, zizmor, CodeQL and SonarCloud, and fails the
build on any tracked `.pkl` file `pkl format` would rewrite.

## Adding a managed setting

Read [`CLAUDE.md`](CLAUDE.md) first. It is written for AI assistants but it is
the real design documentation: it says which class a read belongs in, why the
reader and the checker are separate, what a drift group may not know about
GitHub's wire format, and roughly forty other things that were each learned by
getting them wrong. A change that fights the build is usually a change that
skipped it.

[`SPEC.md`](SPEC.md) is the specification — what drifty manages and what
`--fix` will and will not delete. [`FEATURES.md`](FEATURES.md) is the history
of what has been built. [`FOLLOWUPS.md`](FOLLOWUPS.md) holds the things
carried only because something upstream is unfinished, each with the check
that says whether it can go yet.

## Commits and pull requests

Commit messages here explain *why*, usually in a paragraph or several, and the
subject line is a sentence in the imperative. `git log` is the best guide.

Open the pull request as a draft if it is not ready. There is one required
status check per workflow; all of them must be green.

## Reporting things

A bug or a feature request: open an
[issue](https://github.com/ArloL/drifty/issues). A vulnerability: do not —
see [`SECURITY.md`](SECURITY.md) for the private route.
