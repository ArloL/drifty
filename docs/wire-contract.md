# Checking the wire against GitHub's spec

- **A new request or response record names its endpoint, or the build fails.**
  `@GitHubEndpoint(request = ..., response = ...)` on every record in `client`
  whose name ends in `Request` or `Response`; a nested record needs none,
  because the walk reaches it from an annotated root. This is a fourth place a
  new wire field appears, beside the reader, the group and the exporter.
  `GitHubApiContractTest` compares each one against
  `src/test/resources/github-api-contract.json`, which
  `python3 download-schemas.py --contract` rewrites from GitHub's own spec.
  The file's `specEtag` is what says which spec it was cut from.
- **The contract refreshes itself, and only an endpoint change asks to be
  read.** `api-contract.yaml` reruns `--contract` monthly and opens a pull
  request, so GitHub moving under drifty arrives as a failing
  `GitHubApiContractTest` on a diff rather than as an
  `InvalidFormatException` in somebody's run. The refresh is committed either
  way — a stale `specEtag` is a copy that cannot say what it was cut from —
  but `specEtag` moves on *every* commit to `github/rest-api-description`,
  most of which touch endpoints drifty has never heard of, so the file
  changing says nothing on its own. `write_contract` compares the `endpoints`
  object, the only part `GitHubApiContractTest` reads, and reports
  `endpoints_changed` on `GITHUB_OUTPUT`; the workflow titles its pull request
  from that. Without the split, a monthly one-line etag bump would be
  indistinguishable from GitHub adding a field, and both would be ignored.
- **Ask Jackson for a wire name; never derive one.** `WireShape` introspects
  through the same `ObjectMapper` configuration `GitHubClient` builds, so
  `@JsonProperty` overrides, `SNAKE_CASE` and `@JsonIgnore` are already
  applied, and an enum value is whatever serializing the constant produces.
  Reimplementing `SNAKE_CASE` in the test would check a second guess against
  the spec while the mapper did something else — and the wire names are
  exactly what is under test, so a shared mistake would be invisible. The two
  directions introspect different configs on purpose: `@JsonInclude(NON_NULL)`
  is a serialization concern and `RepositoryUpdateRequest`'s whole
  nullable-wrapper design lives there.
- **`unmanaged` and `undocumented` are not interchangeable.** `unmanaged` says
  GitHub carries a field drifty does not model; `undocumented` says drifty
  reads a field GitHub's spec has not caught up with. Each entry is
  `"path — reason"`, dotted for a nested property and `#`-suffixed for one enum
  value (`target#actions`). An entry no binding of its record used fails
  `noExclusionIsDead`, so a fix that removes a finding has to remove its
  declaration too — which is how the `current_user_can_bypass#exempt` entry was
  caught the moment the constant that made it unnecessary was added.
- **Two ignore rules, and the difference is a webhook's `config.url`.**
  `isNavigation` matches at any depth — a name ending in `_url`, plus
  `node_id`, `gravatar_id`, `starred_at`, `user_view_type` and `site_admin` —
  because a record reached as `owner`, `parent` or `repository` is a reference
  to another resource and its links are GitHub's. `ROOT_METADATA` matches only
  at the root of a response, and holds the names that are settings one level
  down: `id`, `type`, `name`, timestamps, counters. A **bare** `url` is in the
  second list on purpose: a webhook's `config.url` is the payload URL, a
  managed setting at depth 1, and it is the whole reason the two lists differ.
  Silence a finding with a declaration that carries its reason, never by
  widening either list.
- **An exclusion path ending in `.*` covers the subtree below it.** That is how
  a reference to another resource is declared once rather than forty times —
  `parent.*` on a team, `repository.*` on a code security attachment,
  `protection_rules.reviewers.reviewer.*` on an environment. The entry still
  names the subtree, so it says what it covers; the reason says why nothing
  under it is compared.
- **A response enum needs a constant for every value the spec lists, even when
  nothing compares the field.** `currentUserCanBypass` describes the token
  rather than the ruleset and no group reads it, but it is parsed: GitHub's
  fourth value `exempt` against drifty's three constants was an
  `InvalidFormatException` that ended that repository's check. Reaching for
  `READ_UNKNOWN_ENUM_VALUES_AS_NULL` instead would turn every future addition
  into a silent null.
- **A component whose wire name is a Java keyword carries `@JsonProperty`.**
  `RepositoryCreateRequest.isPrivate` sent `is_private` where `POST /user/repos`
  accepts `private`; GitHub ignores an unknown field, so asking for a private
  repository created a public one. `RepositoryDetailsResponse` had it right and
  nothing made the two agree until this check existed.
- **The contract keeps a discriminated `oneOf` branch by branch.** GitHub writes
  ruleset rules as 25 branches each pinning `type` to a single-value enum, and
  `Rule` mirrors them with `@JsonSubTypes`. Merging the branches — which is what
  the extractor does for `allOf` and an undiscriminated `anyOf`, because Jackson
  flattens those into one record — would compare every subtype against the union
  of all rules' fields and so find nothing wrong, ever. The discriminator
  property itself is written by `@JsonTypeInfo`, not by a component, so the walk
  treats it as declared.
- **The contract is per endpoint because the same field name means different
  things.** A ruleset's `source_type` is `Repository`/`Organization`/
  `Enterprise` and a custom property's is `organization`/`enterprise`; a
  ruleset's `target` accepts `repository` at the organization endpoints and not
  at the repository ones. A global field-name lookup would report conflicts that
  are not conflicts and miss the ones that are.
- **`GraphQlRepositoryResponse` and `CachedHttpResponse` answer to no OpenAPI
  endpoint** and are named in `NOT_OPENAPI`. The GraphQL rewrite is still
  pinned: `GraphQlShape` writes into `RulesetDetailsResponse`,
  `BranchProtectionResponse` and `CollaboratorResponse`, each checked against
  its REST endpoint.

