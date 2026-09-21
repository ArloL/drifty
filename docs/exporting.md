# Exporting a config

- **The export's byte-identity comes from the exporter's section order, and
  within a section from `Collecting`'s sort.** An export of an unchanged
  account has to produce the identical file twice running, or an adopter who
  commits it reads a later diff as GitHub changing when it was only drifty
  running again.
  `anExportIsByteIdenticalEveryTimeItRuns` pins that, and establishes where it
  comes from by reversing each candidate in turn: `addUnmanagedGroups`' own
  `sorted()` orders the `managed` block, and `addFailureNote` emits each note
  where the exporter puts that group's section, so which section a note lands
  under is the exporter's fixed order and never arrival order. Inside a section
  it is not: `addFailureNote` emits every failure whose group matches, in list
  order, and sorts nothing. A group can fail more than once for one entity —
  `ENVIRONMENT_CONFIG`, `ENVIRONMENT_SECRETS` and `ENVIRONMENT_VARIABLES` read
  per environment, on parallel threads — so `Collecting.failures()`'s sort by
  `(group, reason)` is the only thing ordering those, and it is redundant only
  while a group fails at most once. Keep it, and do not delete a downstream
  `sorted()` believing it is the one carrying the order. A fixture with one
  failure per entity proves none of this: there is one `Collecting` per entity,
  so a single failure sorts trivially — which is why removing the sort leaves
  the suite green and breaks a repository with two failing environments.

- **The exported file is `pkl format` output, not merely valid Pkl.** An
  adopter commits it as their starting config and CI runs `pkl format
  --diff-name-only` over it, so `PklWriter` writes what the formatter would:
  an empty body is `{}` on the line that opens it, and a scalar assignment
  past `LINE_WIDTH` moves its value to its own line one level in. 100 is the
  formatter's width; the narrower `NOTE_WIDTH` is only where comments wrap,
  which is drifty's own choice — `pkl format` never rewraps one. Those two
  shapes, with the third below, are the whole of it: a block header, a listing
  element and a comment are left where they are however long they get, so do
  not bring them to 80 columns too. Issue #138 was the export failing that check on its first CI
  run.
- **Three shapes, and the third one looks wrong.** `PklWriter`'s model of
  `pkl format` is: an empty body is `{}` on the line that opens it, a scalar
  assignment past `LINE_WIDTH` moves its value to its own line one level in,
  and a body holding nothing but notes keeps them at the *enclosing* indent
  rather than one level in. The formatter indents a comment to the member it
  precedes, and with no member to precede it falls back to the block's own
  line — so `security {` is followed by `// ...` at `security`'s indent, not
  two spaces past it. The writer reproduces it for the same reason it
  reproduces the other two: the alternative is a rule saying no exporter may
  leave a block holding only notes, spread across a dozen exporters with
  nothing to check it. A pkl release that indents these properly fails
  `PklWriterAgreesWithTheFormatterTest` on the version bump, which is where
  finding out belongs.
- **A note's text is normalised, because a `//` comment ends at a line
  break.** `PklWriter.wrap` splits on any run of whitespace, so a note
  carrying a newline stays one comment instead of dropping everything after
  the break into the file as bare Pkl — no exception, a file that does not
  evaluate. `FetchFailures.firstLine` takes the first line of the one reason
  that comes from somebody else's text, and that is a guard a layer away from
  the syntax it protects; this is the layer that owns the syntax.
- **The writer is also checked against trees nobody wrote down.**
  `PklWriterAgreesWithTheFormatterTest` generates `PklNode` trees and asserts
  the formatter leaves the writer's output alone — which covers parsing too,
  since the formatter cannot format what it cannot parse. Every file the other
  two formatter tests see came out of an exporter, so every string in it is a
  GitHub value somebody put in a fixture; the input space is what this
  reaches, and it is where both shapes above came from. The seeds are fixed:
  a generative test that picks a new one every run reports a failure the next
  run cannot reproduce, and this one runs in CI where nobody is watching. Its
  `shrink` is what replaces a property-testing dependency — the tree that
  fails is a hundred nodes and what a reader needs is the three that matter.
  It only ever removes, so it terminates. Adding a seed is a commit; adding
  jqwik would be a test dependency the native test image needs metadata for.
- **The formatter is asked, not described.** Those three shapes are
  `PklWriter`'s model of `pkl format`, and a model agrees with itself: before
  `pkl-formatter` was a test dependency the only thing that ever disagreed
  with it was an adopter's CI. `PklFormat.assertFormatted` runs the formatter
  the command runs, `ExportRoundTripTest` holds all three exported files to
  it, and `TrackedPklFilesAreFormattedTest` holds the repository's own four —
  which is also where a library that stopped agreeing with the binary would
  show up, since `main.yaml`'s `pkl-format` job checks those same files with
  the CLI. `pom.xml`'s `pkl.version` is the one pin: the generator, the
  formatter and the CLI the workflow downloads all resolve it, and the
  workflow reads it out of the pom with `sed` rather than repeating it.
  Keep `PklWriterTest` anyway — it is what says which shape a change broke,
  where the formatter only says the file differs.
- **A new schema field needs an exporter line.** `SchemaCoverageTest` fails
  the build otherwise, and nothing else would: a field the export omits is one
  the config leaves at its default, so the round-trip test agrees with itself
  and passes.
- **A setting drifty reports but cannot write is exported as a field AND a
  note.** `OrgSettingsDriftGroup` compares a check-only setting like any
  other and only its writer is null, so a file that omitted the field would
  carry the schema default against GitHub's real value and report drift no
  `--fix` could ever clear. The field is what makes the file round-trip; the
  note is what tells a reader drifty will not change it. `visibility` on the
  repository side and the ten check-only organization settings are the
  cases.
- **A group the export could not read is exported as a `managed` exclusion AND
  a note.** `AccountExporter.addUnmanagedGroups` names every
  `FetchFailures.Failure` in the entry's own `managed` block; the note beside
  the section says why. The note alone is what issue #136 was: a `//` comment
  is the one form a later run cannot act on, so the exported file failed on
  exactly the request the export had already failed on. Emit the block before
  the archived branch in `RepositoryExporter.entry` —
  `RepositoryStateReader.fetchState` reads a group for an archived repository
  too.

