# Mutation testing and coverage

**Mutation testing answers what coverage cannot, and only for the two packages
where it pays.**

```bash
./mvnw test-compile pitest:mutationCoverage    # target/pit-reports
```

`drift` and `export` are tables — a `Setting` row pairs a comparison with the
builder call that writes it, an exporter line pairs a field with its schema
default — and a table is the shape most likely to be *executed* by a test that
asserts nothing about it. Line coverage says the row ran; a surviving mutant
says nothing checked what it did. Measured 2026-09-20: 1118 mutations, 1073
killed, **96.0%**, and `mutationThreshold` is 92.

The first run scored 90.1%, and what the 111 survivors turned out to be is the
argument for running it at all — each was a whole behaviour nothing asserted,
not a missing edge case:

| What survived | Why it mattered |
| --- | --- |
| 25 × `addFailureNote` removed | Issue #136's note, pinned for two groups out of 27 |
| 18 ruleset fields | `ocompare(...).ifPresent(items::add)` — drop the `ifPresent` and drifty compares the field, builds the item and discards it |
| 6 × Actions-permissions fixes | Detected, built, never executed — including "a name with no id fails the whole fix", which `docs/drift-groups.md` states and nothing tested |
| 8 × deployment branch policies | The one part of an environment `--fix` **deletes** |
| 6 × `PklWriter` nested listings | Both arms unexecuted, and with them the `depth + 1` that indents |
| 1 × `recordActionSecret` | The baseline that tells the next run "unchanged" from "rotated" |

The 45 left are a different shape: mostly a fix lambda's `return
FixResult.success()` on a path another test already drives. Worth doing, not
worth doing before the next real gap.

It is not bound to a phase, and it is not on every pull request either.
`mutation.yaml` runs it monthly and on `workflow_dispatch`, on Temurin so the
graal profile does not fire and build an image the job would throw away. The
reason it is not a required check: a run costs about what a native build does,
and it is not answering a question about the commit in front of it — the score
moves when test quality moves, which is slower than a branch. Dispatch it
before merging something that adds a table row. `NativeExecutableIT` is
excluded: it runs the built binary, which that job has not produced, and PIT
refuses to start unless every test it sees is green.

`jacoco:check` runs at `verify` and fails the build under 80% line or branch
coverage. The measured bundle excludes `io.github.arlol.githubcheck.pkl`,
which is `pkl-codegen-java`'s output: counting its generated `withX`, `equals`
and `toString` arms reported 73.5% instruction against the 92.1% the
hand-written code holds, and moved the number when a schema field was added
rather than when a test was deleted. `sonar.exclusions` drops the same
directory for the same reason. Branch coverage sits at 81.8%, so the bound
bites — what it does not reach is concentrated in `GitHubClient`'s transport
arms and `GitHubCheck`'s argument handling. Cover those to raise it; never
raise it by widening the excludes.

