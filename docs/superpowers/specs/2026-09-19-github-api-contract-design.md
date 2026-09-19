# Checking drifty's wire types against GitHub's own OpenAPI spec

`GitHubClient`'s mapper is configured this way:

```java
new ObjectMapper()
		.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
		.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
		.configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
```

A response record component whose name does not match the wire therefore
deserializes to `null`. No exception, no failed request, no failing test — the
group compares `null` against the config and reports drift, or reports none,
and both answers are wrong for the same invisible reason. It is the shape of
the `Link`-header-on-304 bug: a wrong answer rather than an error.

Nothing in the build compares drifty's 58 request and response records against
what GitHub says those endpoints carry. The fixtures cannot: a hand-written
WireMock stub is written to match the record, so a record with a misspelled
field and a fixture that misspells it the same way agree with each other and
the suite is green.

## The evidence that this has teeth

**`source_type` is spelled two ways in GitHub's own spec.** A ruleset's is
`Repository` / `Organization` / `Enterprise`; a custom property's is
`organization` / `enterprise`. Drifty gets both right today —
`RulesetSourceType` carries TitleCase `@JsonProperty` values and
`CustomPropertyResponse.sourceType` is a bare `String` compared against
lowercase — and nothing pins either. The same field name meaning two different
value sets is also why the check must be per endpoint: a global field-name
lookup would report a conflict that is not one.

**Uppercase is the 5% case, which is what makes it dangerous.** Of 360 distinct
enum value-sets in the 2026-03-10 spec, 18 contain an uppercase value. Four of
drifty's enums —`MergeCommitTitle`, `MergeCommitMessage`,
`SquashMergeCommitTitle`, `SquashMergeCommitMessage` — carry no `@JsonProperty`
at all and rely on the Java constant name being the wire value. They are
correct (the spec really does say `PR_TITLE`, `MERGE_MESSAGE`,
`COMMIT_OR_PR_TITLE`), and they are correct by nobody's decision that is
written down. Every other enum in `client` spells its values out.

**The reverse direction is free on requests and needs a rule on responses.**
`PATCH /orgs/{org}` accepts 29 fields; `OrganizationUpdateRequest` declares 20.
All nine of the difference are real feature gaps — `billing_email`,
`members_allowed_repository_creation_type`, and the six organization-level
security defaults. `GET /orgs/{org}` returns 66 fields against
`OrganizationResponse`'s 31, but 25 of the 35 are `*_url`, `id`, `node_id`,
counts and timestamps.

## What is checked

Both directions, per endpoint, per direction (request body or response body):

| Check | Catches |
|---|---|
| Declared name absent from the spec's property set | The silent-null class above |
| Spec property absent from the record and not declared `unmanaged` | The feature gap — `billing_email` and the six security defaults |
| Java enum constant absent from the spec's `enum` list | A value GitHub answers 422 to, reported as fixed |
| Spec enum value absent from the Java enum | A value GitHub sends that drifty cannot parse |
| Java type contradicts the spec `type` | The `{"status": "enabled"}` wrapper class of bug |
| Primitive component against a property the spec marks `nullable: true` | A crash in the wild — the mapper sets `FAIL_ON_NULL_FOR_PRIMITIVES` |

The last row fires only on an explicit `nullable: true`, never on "absent from
`required`". GitHub's `required` lists are patchy enough that the wider rule
would be noise, and a check people learn to distrust is worse than no check.

## The vendored contract

`download-schemas.py` gains a `--contract` mode writing one committed file,
`src/test/resources/github-api-contract.json`. It keeps, per endpoint and
direction, only what a wire mismatch can hide in: the nested property tree,
each property's `type`, its `enum` values verbatim, `nullable`, and the
object's `required` list. Descriptions, examples and the `$ref` fan-out that
makes the dereferenced spec 70 MB are dropped.

Measured over the 60 endpoints drifty calls: **483 KB** pretty-printed with
sorted keys — the size of a lockfile, and diffing like one, so a refresh shows
a new GitHub field as a reviewable addition.

The file carries `apiVersion` (`2026-03-10`, the pin `download-schemas.py`
already defaults to) and the upstream commit sha it was cut from. Refresh is
manual, so nothing in CI reports that the copy has aged; the sha is what makes
that visible to anyone who looks, and saying so here is the honest form of the
trade.

**Which endpoints go in is not a hand-maintained list.** The extractor scans
the `client` sources for the endpoint literals the annotations carry and
extracts exactly those, so a record naming a new endpoint pulls its schema in
on the next run and an endpoint drifty stopped calling drops out. Scanning
source text rather than reading the annotations off compiled classes keeps a
Python script out of the build lifecycle, and it loses nothing: the test fails
when an annotated endpoint is missing from the contract file, so a typo or a
skipped refresh is still caught — at the point where being wrong would
otherwise mean checking nothing.

**A discriminated `oneOf` is preserved, not merged.** The spec models ruleset
rules as a `oneOf` with a `type` const per branch, and `Rule` mirrors it with
`@JsonTypeInfo` and 21 `@JsonSubTypes`. Merging the branches would check every
subtype against the union of all rules' fields and find nothing wrong ever. So
a discriminated `oneOf` is stored as a map keyed by the discriminator value.
Undiscriminated `allOf` and `anyOf` still merge, because Jackson flattens those
into one record anyway.

## How a record names its endpoint

`CLASS` retention, type target, never read at runtime, so it needs no
reachability metadata:

```java
@GitHubEndpoint(
		response = {
			"GET /repos/{owner}/{repo}/rulesets/{ruleset_id}",
			"POST /repos/{owner}/{repo}/rulesets",
			"PUT /repos/{owner}/{repo}/rulesets/{ruleset_id}"
		},
		unmanaged = {
			"_links — navigation, not a setting",
			"current_user_can_bypass — about the token, not the ruleset"
		}
)
public record RulesetDetailsResponse(...)
```

`request` and `response` are separate `String[]` rather than a direction enum,
so a record serving three endpoints says so in one place. The loop closes on
itself: because the extractor builds the contract file *from* these
annotations, a typo'd path fails extraction with "not found in spec" instead of
silently checking nothing.

**Nested records carry no annotation.** `SecurityAndAnalysis`, `Permissions`,
`SimpleUser`, `Secrets` and `Rule`'s 21 subtypes are reached from an annotated
root and checked against the sub-schema at their position. One reached from two
endpoints must satisfy both, which is the right answer rather than a problem to
work around.

**Two lists, two directions, and they are not interchangeable.** `unmanaged`
answers the reverse direction: GitHub has this field and drifty does not model
it, because. `undocumented` answers the forward direction: drifty reads this
field and the spec does not carry it, because GitHub's spec lags its API.
FOLLOWUPS.md already records the mirror-image case —
`secret_scanning_extended_metadata` is in the spec with no default and no
example predating it. Without `undocumented`, the first spec lag turns correct
code red and somebody deletes the test.

An entry in either list is a property path, then a reason, separated by an em
dash: everything up to the first ` — ` is the path, the rest is prose nothing
parses. A path is dotted for a nested property (`config.url`) and carries a `#`
for one enum value of a property (`target#actions`), so a single value can be
excluded without excluding the field that holds it. An entry whose path matches
nothing in the spec fails the test — a stale exclusion is how a check quietly
stops covering what it claims to.

## Asking Jackson rather than reimplementing it

The check is worthless if it derives its own idea of the wire name while the
real mapper does something else. So the walk introspects through the same
`ObjectMapper` configuration `GitHubClient` builds:

```java
mapper.getDeserializationConfig().introspect(type).findProperties()  // responses
mapper.getSerializationConfig().introspect(type).findProperties()    // requests
```

That yields the names Jackson will actually use — `@JsonProperty` overrides
applied, `SNAKE_CASE` everywhere else, `@JsonIgnore` already removed. Enum
values come the same way, by serializing each constant and unquoting it.
Nothing in the test knows that `hasIssues` becomes `has_issues`; Jackson says
so.

The two directions use different configs deliberately, because they differ:
`@JsonInclude(NON_NULL)` is a serialization concern, and
`RepositoryUpdateRequest`'s whole nullable-wrapper design lives there.

## The metadata rule

The reverse direction on responses would otherwise be a list of URLs. Across
the 60 endpoints there are 1187 root-level response properties; a rule matching
`*_url` plus a literal list of `id`, `node_id`, timestamps and counts removes
626 of them, leaving 561 to be either modeled or declared — and drifty already
models a good share of those (31 of the organization's 38, 24 of the code
security configuration's 30).

**The rule matches only at the root of a response object.** This is
load-bearing: a webhook's `config.url` is the payload URL, a managed setting at
depth 1, and a blanket `*_url` glob would hide it. The rule lives in the test
rather than on the records because it is shared and its reasoning belongs in
one place.

## The completeness guard

ClassGraph finds every record in `io.github.arlol.githubcheck.client` whose
name ends in `Request` or `Response`. One that neither carries
`@GitHubEndpoint` nor is reachable from a record that does fails the test, so
coverage cannot rot the way it would behind a hand-written list.

Two exclusions, each by name with a reason: `CachedHttpResponse` is not wire
JSON, and `GraphQlRepositoryResponse` answers to GitHub's GraphQL schema rather
than the OpenAPI spec. The GraphQL rewrite is still pinned — `GraphQlShape`
writes into `RulesetDetailsResponse`, `BranchProtectionResponse` and
`CollaboratorResponse`, which are checked against their REST endpoints, and
that is exactly the contract CLAUDE.md states the rewrite has.

## Rollout

Planned as two stages, because 150–250 reverse declarations did not look like
one commit's worth of judgement: the mechanism and the reverse direction on
request bodies first, then responses endpoint by endpoint behind a list that
shrinks to empty.

**It took one.** Two rules the first run made obvious collapsed the work by
more than half. Treating a name ending in `_url` as navigation at *any* depth,
rather than only at the root, took 295 findings to 153 — most of the difference
was `owner.events_url` and its kin on records reached as references to other
resources. Letting an exclusion path end in `.*` took the rest from a line per
field to a line per subtree: `repository.*` is one declaration where the walk
found 44.

The bare `url` stayed root-only through both, because that is the case the
distinction exists for.

So there is no staging list and no FOLLOWUPS entry for one. Every endpoint is
under both directions, and every field GitHub carries is either modeled or
declared with a reason.

## Testing the checker

Against a small fixture contract in test resources, not the real 483 KB one:

- a miscased component fails;
- a lowercase enum constant against an uppercase spec list fails;
- an `unmanaged` entry suppresses exactly one reverse finding and no others;
- an `undocumented` entry suppresses exactly one forward finding;
- a polymorphic subtype is matched to its own `oneOf` branch, not the union;
- the root-level metadata rule does not hide a webhook's `config.url`.

One parameterized test per endpoint and direction, with soft assertions inside,
so a run reports every mismatch at once and each failure names endpoint,
direction, property path and both sides. A first run that turns up forty things
is a list to work through, not forty reruns.

## What this does not do

It does not check what GitHub actually sends, only what GitHub says it sends.
Running the recorded WireMock mappings through an OpenAPI validator would catch
spec-versus-reality divergence that this cannot, but only for endpoints a
fixture exercises, and it gives no reverse direction at all. It is a complement
worth having later, not a competitor, and it is not in this design.

It does not cover the GraphQL query. `GraphQlShape`'s output is pinned
indirectly, through the REST records it writes into.

It does not tell anyone the vendored contract has aged. Refresh is a person
running one command.
