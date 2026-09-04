# Organization Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** drifty manages settings on the GitHub organizations that own the repositories it already checks — org profile and member policies, Actions permissions, default workflow permissions, and org Actions secrets.

**Architecture:** `config/drifty.pkl` gains `organizations` and `users` mappings keyed by login, with repositories nested inside them and `Repository.owner` removed. `DriftGroup` and `ManagedGroups` become generic over their group-name enum so org groups use the same namespacing, fix accounting and partial-management machinery under a separate `Drifty.OrgGroupName` union. `OrgChecker` is renamed `RepositoryChecker`, a new `OrganizationChecker` sits beside it, fix execution moves to `DriftFixer`, and report printing moves to `Report`.

**Tech Stack:** Java 25, Maven, Pkl (`pkl-codegen-java`), JUnit 5, AssertJ, WireMock, Jackson, libsodium via lazysodium.

**Spec:** `docs/superpowers/specs/2026-09-04-org-settings-design.md`

## Global Constraints

- Build and test with `./mvnw verify`. Iterate with `./mvnw test -DskipNativeTests`; run the full `./mvnw verify` once before the final push.
- Drift groups and state records never hold GitHub response types. `ActualStateBoundaryTest` fails a class holding a `client.*` type other than `GitHubClient`, `RepoRef`, or an enum. Wire-shape knowledge (nulls that mean `""`, omitted sections, wrappers) goes in `ActualTypes`.
- Desired-state test fixtures come from `testsupport.Desired`, which evaluates `src/test/resources/desired-defaults.pkl`. Do not add hand-written `*Args` builders.
- A drift group's paths are relative to the group. `DriftGroup.detect()` namespaces them; never namespace inside a group.
- Every group that sends its own requests must be guarded in the fetch as well as filtered from the comparison. Filtering alone still sends the request.
- `GET /orgs/{org}` is the one exception: `OrganizationChecker` sends it to learn whether the org exists, even when `org_settings` is unmanaged. Document that where it is sent.
- Generated sources live in `target/generated-sources/pkl/java/io/github/arlol/githubcheck/pkl/Drifty.java`. Run `./mvnw generate-sources` after editing `config/drifty.pkl`.
- Pkl codegen emits enums that return their source string from `toString()` — `Drifty.OrgGroupName.ORG_SETTINGS.toString()` is `"org_settings"`. Report output depends on that.
- `OrganizationUpdateRequest` fields are all nullable wrappers under `@JsonInclude(NON_NULL)`, because `OrgSettingsDriftGroup` sends only the fields that drifted.
- The production config is not in this repository. It lives in
  `/Users/arlookeeffe/Developer/github.com/arlol/drifty-arlol/drifty.pkl` and
  amends the schema from
  `https://raw.githubusercontent.com/ArloL/drifty/refs/heads/main/config/drifty.pkl`.
  That URL tracks `main`, so merging this branch breaks that config until
  Task 12 lands there. Either land both together, or pin the amend to a commit
  before merging.
- Commit after every task. Commit messages end with:
  `Claude-Session: https://claude.ai/code/session_014wTAEAEa9wytEzdrqpvBsH`

---

### Task 1: Generify DriftGroup and ManagedGroups

Pure refactor. No behaviour changes, no new group. The generic parameter is what lets org groups reuse `detect()`'s namespacing and `ManagedGroups`' set arithmetic without either scope seeing the other's constants.

**Files:**
- Modify: `src/main/java/io/github/arlol/githubcheck/drift/DriftGroup.java`
- Modify: `src/main/java/io/github/arlol/githubcheck/drift/ManagedGroups.java`
- Modify: all 24 files in `src/main/java/io/github/arlol/githubcheck/drift/*DriftGroup.java`
- Modify: `src/main/java/io/github/arlol/githubcheck/OrgChecker.java`
- Test: `src/test/java/io/github/arlol/githubcheck/drift/ManagedGroupsTest.java`

**Interfaces:**
- Produces: `DriftGroup<N extends Enum<N>>` with `public abstract N name()`; `ManagedGroups<N extends Enum<N>>` with `static ManagedGroups<Drifty.GroupName> of(Drifty.Managed)`, `static <N extends Enum<N>> ManagedGroups<N> all(Class<N> type)`, `boolean manages(N)`, `List<N> unmanaged()`.

- [ ] **Step 1: Make `ManagedGroups` generic**

Replace the body of `ManagedGroups.java` (keep the existing class javadoc, add the sentence about the type token):

```java
public final class ManagedGroups<N extends Enum<N>> {

	private final Class<N> type;
	private final Set<N> managed;

	private ManagedGroups(Class<N> type, Set<N> managed) {
		this.type = type;
		this.managed = managed;
	}

	public static ManagedGroups<Drifty.GroupName> of(Drifty.Managed managed) {
		return of(Drifty.GroupName.class, managed.mode, managed.groups);
	}

	/**
	 * Every group of one scope, which is what an entity that declares nothing
	 * gets. The class token is the only way to enumerate an enum's constants
	 * generically — {@code EnumSet.allOf} needs it.
	 */
	public static <N extends Enum<N>> ManagedGroups<N> all(Class<N> type) {
		return new ManagedGroups<>(type, EnumSet.allOf(type));
	}

	private static <N extends Enum<N>> ManagedGroups<N> of(
			Class<N> type,
			Drifty.ManageMode mode,
			List<N> groups
	) {
		Set<N> named = groups.isEmpty() ? EnumSet.noneOf(type)
				: EnumSet.copyOf(groups);
		return new ManagedGroups<>(type, switch (mode) {
		case ONLY -> named;
		case ALL_EXCEPT -> {
			var rest = EnumSet.allOf(type);
			rest.removeAll(named);
			yield rest;
		}
		});
	}

	public boolean manages(N group) {
		return managed.contains(group);
	}

	/** The groups this entity leaves alone, for the report. */
	public List<N> unmanaged() {
		var rest = EnumSet.allOf(type);
		rest.removeAll(managed);
		return List.copyOf(rest);
	}

}
```

- [ ] **Step 2: Make `DriftGroup` generic**

In `DriftGroup.java`, change the declaration and the abstract method; every other member stays as it is:

```java
public abstract class DriftGroup<N extends Enum<N>> {

	public abstract N name();
```

- [ ] **Step 3: Update the 24 groups and the checker**

Each group changes only its `extends` clause:

```bash
cd /Users/arlookeeffe/Developer/drifty
sed -i '' 's/extends DriftGroup {/extends DriftGroup<Drifty.GroupName> {/' \
  src/main/java/io/github/arlol/githubcheck/drift/*DriftGroup.java
grep -L 'import io.github.arlol.githubcheck.pkl.Drifty;' \
  src/main/java/io/github/arlol/githubcheck/drift/*DriftGroup.java
```

Any file the `grep -L` lists needs `import io.github.arlol.githubcheck.pkl.Drifty;` added.

In `OrgChecker.java`, widen the three declarations that name the raw type:

```java
	Map<DriftGroup<Drifty.GroupName>, List<DriftFix>> computeGroupDrifts(
			RepositoryState actual,
			Drifty.Repository desired
	) {
		Map<DriftGroup<Drifty.GroupName>, List<DriftFix>> groupDrifts = new LinkedHashMap<>();
```

```java
	List<DriftGroup<Drifty.GroupName>> createDriftGroups(
			RepositoryState actual,
			Drifty.Repository desired
	) {
```

```java
	private static List<DriftGroup<Drifty.GroupName>> onlyManaged(
			List<DriftGroup<Drifty.GroupName>> groups,
			ManagedGroups<Drifty.GroupName> managed
	) {
		return groups.stream().filter(g -> managed.manages(g.name())).toList();
	}
```

`applyFixes` takes `Map<DriftGroup<Drifty.GroupName>, List<DriftFix>>`, and so does `prerequisitesFirst`. Inside `createDriftGroups`, `var groups = new ArrayList<DriftGroup<Drifty.GroupName>>();`.

- [ ] **Step 4: Update `ManagedGroupsTest` and the other call sites**

`ManagedGroups.all()` becomes `ManagedGroups.all(Drifty.GroupName.class)` at all 6 sites:

```bash
grep -rn --include='*.java' -F 'ManagedGroups.all()' src
sed -i '' 's/ManagedGroups\.all()/ManagedGroups.all(Drifty.GroupName.class)/g' \
  $(grep -rl --include='*.java' -F 'ManagedGroups.all()' src)
```

Declared types in tests (`ManagedGroups managed = …`) become `ManagedGroups<Drifty.GroupName>`, or `var`.

- [ ] **Step 5: Run the tests**

Run: `./mvnw test -DskipNativeTests`
Expected: PASS, with no test changed beyond the type parameters.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -F - <<'EOF'
Make DriftGroup and ManagedGroups generic over their group enum

Claude-Session: https://claude.ai/code/session_014wTAEAEa9wytEzdrqpvBsH
EOF
```

---

### Task 2: Extract DriftFixer and Report, rename OrgChecker

Second pure refactor. Fix execution and report printing are needed by both checkers, and a class called `OrgChecker` that checks repositories misdirects every reader once organizations exist.

**Files:**
- Create: `src/main/java/io/github/arlol/githubcheck/drift/DriftFixer.java`
- Create: `src/main/java/io/github/arlol/githubcheck/Report.java`
- Rename: `OrgChecker.java` → `RepositoryChecker.java`
- Rename: `OrgCheckerCheckTest`, `OrgCheckerDiffTest`, `OrgCheckerFetchStateTest`, `OrgCheckerFixTest` → `RepositoryChecker*Test`
- Modify: `src/main/java/io/github/arlol/githubcheck/CheckResult.java`
- Modify: `src/main/java/io/github/arlol/githubcheck/GitHubCheck.java`
- Test: `src/test/java/io/github/arlol/githubcheck/CheckResultTest.java`

**Interfaces:**
- Consumes: `DriftGroup<N>` and `ManagedGroups<N>` from Task 1.
- Produces:
  - `DriftFixer.FixOutcome(List<DriftItem> fixed, List<FixResult.Unfixed> unfixed)` with `List<DriftItem> unfixedItems()`
  - `static FixOutcome DriftFixer.applyFixes(Map<? extends DriftGroup<?>, List<DriftFix>> groupDrifts)`
  - `static List<String> DriftFixer.render(List<DriftItem> items)`
  - `static List<CheckResult.FixReport> DriftFixer.fixReports(FixOutcome outcome)`
  - `CheckResult(List<Entry> orgs, List<Entry> repos)` with `Entry` carrying the former `RepoCheckResult` shape and factories
  - `Report.print(CheckResult result)`

- [ ] **Step 1: Move fix execution into `DriftFixer`**

Create `src/main/java/io/github/arlol/githubcheck/drift/DriftFixer.java` holding, verbatim from `OrgChecker`, the `FixOutcome` record and the `applyFixes`, `prerequisitesFirst`, `apply`, `byItem`, `allUnfixed` and `reason` methods, plus `render` and `fixReports` from the same class. Keep every javadoc — the accounting-by-item and prerequisite-ordering comments are the reason the code has this shape.

```java
package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.arlol.githubcheck.CheckResult;

/**
 * Runs the fixes a set of drift groups produced and accounts for the result
 * per drift item, for repositories and organizations alike.
 */
public final class DriftFixer {

	private DriftFixer() {
	}

	public record FixOutcome(
			List<DriftItem> fixed,
			List<FixResult.Unfixed> unfixed
	) {

		public FixOutcome {
			fixed = List.copyOf(fixed);
			unfixed = List.copyOf(unfixed);
		}

		public List<DriftItem> unfixedItems() {
			return unfixed.stream().map(FixResult.Unfixed::item).toList();
		}

	}

	public static FixOutcome applyFixes(
			Map<? extends DriftGroup<?>, List<DriftFix>> groupDrifts
	) {
		var fixed = new ArrayList<DriftItem>();
		var unfixed = new ArrayList<FixResult.Unfixed>();

		for (DriftFix driftFix : prerequisitesFirst(groupDrifts)) {
			if (!driftFix.items().isEmpty()) {
				apply(driftFix, fixed, unfixed);
			}
		}
		return new FixOutcome(fixed, unfixed);
	}

	public static List<String> render(List<DriftItem> items) {
		return items.stream().map(DriftItem::message).toList();
	}

	public static List<CheckResult.FixReport> fixReports(FixOutcome outcome) {
		var reports = new ArrayList<CheckResult.FixReport>();
		outcome.fixed()
				.forEach(
						item -> reports.add(
								new CheckResult.FixReport(
										item.path(),
										true,
										null
								)
						)
				);
		outcome.unfixed()
				.forEach(
						unfixed -> reports.add(
								new CheckResult.FixReport(
										unfixed.item().path(),
										false,
										unfixed.reason()
								)
						)
				);
		return reports;
	}

	// prerequisitesFirst, apply, byItem, allUnfixed, reason move here
	// unchanged, with `private static` visibility and their javadoc.

}
```

`DriftFixer` importing `CheckResult` from the parent package is the one direction that package already depends on; `CheckResult` must not import anything from `drift`.

- [ ] **Step 2: Turn `RepoCheckResult` into `Entry` and split the result**

In `CheckResult.java`: rename the nested record `RepoCheckResult` to `Entry`, keep all seven components and all six factories, and change the outer record:

```java
public record CheckResult(
		List<Entry> orgs,
		List<Entry> repos
) {

	public CheckResult {
		orgs = List.copyOf(orgs);
		repos = List.copyOf(repos);
	}

	public static CheckResult ofRepos(List<Entry> repos) {
		return new CheckResult(List.of(), repos);
	}

	private Stream<Entry> all() {
		return Stream.concat(orgs.stream(), repos.stream());
	}

	public long okCount() {
		return repos.stream().filter(r -> r.status() == Status.OK).count();
	}

	public long orgOkCount() {
		return orgs.stream().filter(r -> r.status() == Status.OK).count();
	}
```

`driftCount`, `errorCount`, `unknownCount` and `missingCount` keep counting `repos` only; add `orgDriftCount()` and `orgErrorCount()` and `orgMissingCount()` alongside. `hasDrift()` and `fixFailures()` work over `all()`:

```java
	public boolean hasDrift() {
		return all().anyMatch(
				entry -> entry.status() == Status.DRIFT
						|| entry.status() == Status.ERROR
						|| entry.status() == Status.MISSING
		);
	}

	public List<String> fixFailures() {
		return all().flatMap(
				entry -> entry.fixReports()
						.stream()
						.filter(report -> !report.fixed())
						.map(report -> entry.name() + ": " + report.message())
		).toList();
	}
```

- [ ] **Step 3: Move printing into `Report`**

Create `src/main/java/io/github/arlol/githubcheck/Report.java` with `printReport`'s body split into a public `print(CheckResult)` and a private `printEntries(List<Entry>)`, so the organizations section can be added in Task 6 without touching the repository section:

```java
public final class Report {

	private Report() {
	}

	public static void print(CheckResult result) {
		printEntries(result.repos(), "not in desired config", "in config but not found in org");
		printSummary(result);
	}

	private static void printEntries(
			List<CheckResult.Entry> entries,
			String unknownSuffix,
			String missingSuffix
	) {
		List<CheckResult.Entry> sorted = entries.stream()
				.sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
				.toList();
		// the existing switch over r.status(), unchanged apart from taking
		// the two suffixes for UNKNOWN and MISSING
	}

	private static void printSummary(CheckResult result) {
		// the existing "=== Summary ===" block and the failed-fixes block,
		// unchanged
	}

	// printUnmanaged and printFixReports move here unchanged.
}
```

- [ ] **Step 4: Rename the checker**

```bash
git mv src/main/java/io/github/arlol/githubcheck/OrgChecker.java \
       src/main/java/io/github/arlol/githubcheck/RepositoryChecker.java
for t in Check Diff FetchState Fix; do
  git mv src/test/java/io/github/arlol/githubcheck/OrgChecker${t}Test.java \
         src/test/java/io/github/arlol/githubcheck/RepositoryChecker${t}Test.java
done
grep -rl --include='*.java' -e 'OrgChecker' src | xargs sed -i '' 's/OrgChecker/RepositoryChecker/g'
```

Delete `applyFixes`, `FixOutcome`, `render`, `fixReports`, `prerequisitesFirst`, `apply`, `byItem`, `allUnfixed`, `reason` and `printReport` from `RepositoryChecker`; call `DriftFixer.applyFixes(...)`, `DriftFixer.render(...)` and `DriftFixer.fixReports(...)` from `checkOne`. Update the class javadoc: it checks the repositories of one owner.

In `GitHubCheck.main`, `checker.printReport(result)` becomes `Report.print(result)`, and `checker.check(repos)` now returns `List<CheckResult.Entry>` wrapped by `CheckResult.ofRepos(...)`.

- [ ] **Step 5: Run the tests**

Run: `./mvnw test -DskipNativeTests`
Expected: PASS. `RepositoryCheckerFixTest` exercises `DriftFixer` through the checker; nothing in the output changed.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -F - <<'EOF'
Extract DriftFixer and Report, rename OrgChecker to RepositoryChecker

Claude-Session: https://claude.ai/code/session_014wTAEAEa9wytEzdrqpvBsH
EOF
```

---

### Task 3: Nest repositories under their owner

The config gets `organizations` and `users` mappings keyed by login; `Repository.owner` goes away. `Organization` carries only `repositories` for now — its settings arrive in Task 6, so this task stays a restructure with no new managed state.

**Files:**
- Modify: `config/drifty.pkl`
- Create: `config/example.pkl`
- Delete: `config/ArloL.pkl`
- Modify: `pom.xml` (the `exec-maven-plugin` `--config` argument, around line 467)
- Create: `src/main/java/io/github/arlol/githubcheck/DriftyConfig.java`
- Modify: `src/main/java/io/github/arlol/githubcheck/PklConfigLoader.java`
- Modify: `src/main/java/io/github/arlol/githubcheck/RepositoryChecker.java`
- Modify: `src/main/java/io/github/arlol/githubcheck/RepositoryState.java`
- Modify: `src/main/java/io/github/arlol/githubcheck/GitHubCheck.java`
- Modify: `src/main/java/io/github/arlol/githubcheck/client/GitHubClient.java`
- Modify: `src/test/resources/desired-defaults.pkl`
- Modify: `src/test/java/io/github/arlol/githubcheck/testsupport/Desired.java`
- Test: `src/test/java/io/github/arlol/githubcheck/PklConfigLoaderTest.java`, `GitHubCheckTest.java`, `client/GitHubClientTest.java`

**Interfaces:**
- Produces:
  - `record DriftyConfig(Map<String, Drifty.Organization> organizations, Map<String, Drifty.User> users)` with `List<Drifty.Repository> allRepositories()`
  - `static DriftyConfig PklConfigLoader.load(Path pklFile)`
  - `Optional<List<RepositorySummaryResponse>> GitHubClient.listOrgRepos(String org)` — empty on 404
  - `List<RepositorySummaryResponse> GitHubClient.listUserRepos(String login)`
  - `List<CheckResult.Entry> RepositoryChecker.check(String owner, List<RepositorySummaryResponse> summaries, List<Drifty.Repository> desired)`
  - `RepositoryState` first component becomes `RepoRef ref`, with `String name()` delegating

- [ ] **Step 1: Write the failing loader test**

In `PklConfigLoaderTest`, add:

```java
	@Test
	void load_readsOrganizationsAndUsers(@TempDir Path dir) throws Exception {
		Path config = dir.resolve("drifty.pkl");
		Files.writeString(config, """
				amends "%s"

				organizations {
				  ["my-org"] {
				    repositories {
				      new { name = "repo-a" }
				    }
				  }
				}

				users {
				  ["ArloL"] {
				    repositories {
				      new { name = "drifty" }
				    }
				  }
				}
				""".formatted(
				Path.of("config/drifty.pkl").toAbsolutePath().toUri()
		));

		DriftyConfig loaded = PklConfigLoader.load(config);

		assertThat(loaded.organizations()).containsOnlyKeys("my-org");
		assertThat(loaded.organizations().get("my-org").repositories)
				.extracting(r -> r.name)
				.containsExactly("repo-a");
		assertThat(loaded.users()).containsOnlyKeys("ArloL");
		assertThat(loaded.users().get("ArloL").repositories)
				.extracting(r -> r.name)
				.containsExactly("drifty");
	}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -DskipNativeTests -Dtest=PklConfigLoaderTest`
Expected: FAIL — `drifty.pkl` has no `organizations`, and `load` returns `List<Drifty.Repository>`.

- [ ] **Step 3: Restructure the schema**

In `config/drifty.pkl`, delete `owner: String` from `Repository`, and replace the trailing `repositories` declaration and `output` block with:

```pkl
/// A GitHub organization drifty manages, keyed by login in `organizations`.
class Organization {
  repositories: Listing<Repository> = new {}
}

/// A personal account. It owns repositories and has no org-level settings —
/// which is why it is a separate type rather than a flag on Organization.
class User {
  repositories: Listing<Repository> = new {}
}

organizations: Mapping<String, Organization> = new {}
users: Mapping<String, User> = new {}

output {
  value = new Dynamic {
    organizations = module.organizations
    users = module.users
  }
}
```

Run `./mvnw generate-sources` before compiling.

- [ ] **Step 4: Load both mappings**

Create `DriftyConfig`:

```java
package io.github.arlol.githubcheck;

import java.util.List;
import java.util.Map;

import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * The whole desired state: organizations and personal accounts, each holding
 * the repositories it owns. The owner is the key, so a repository cannot name
 * an account nobody declared.
 */
public record DriftyConfig(
		Map<String, Drifty.Organization> organizations,
		Map<String, Drifty.User> users
) {

	public DriftyConfig {
		organizations = Map.copyOf(organizations);
		users = Map.copyOf(users);
	}

	/** Every declared repository, for checks that do not care who owns it. */
	public List<Drifty.Repository> allRepositories() {
		return java.util.stream.Stream
				.concat(
						organizations.values().stream().flatMap(o -> o.repositories.stream()),
						users.values().stream().flatMap(u -> u.repositories.stream())
				)
				.toList();
	}

}
```

`PklConfigLoader.load`:

```java
	public static DriftyConfig load(Path pklFile) throws IOException {
		try (var evaluator = ConfigEvaluator.preconfigured()) {
			var root = evaluator.evaluate(ModuleSource.path(pklFile));
			return new DriftyConfig(
					root.get("organizations")
							.as(
									Types.mapOf(
											String.class,
											Drifty.Organization.class
									)
							),
					root.get("users")
							.as(Types.mapOf(String.class, Drifty.User.class))
			);
		}
	}
```

- [ ] **Step 5: Split the repository listing in the client**

`listOrgRepos` currently falls back to `/user/repos` on 404, which would list the token owner's repositories for an organization that does not exist. Replace it with two methods:

```java
	/**
	 * The organization's repositories, or empty when GitHub does not know the
	 * organization. The empty result is what makes the org report MISSING
	 * rather than error.
	 */
	public Optional<List<RepositorySummaryResponse>> listOrgRepos(String org) {
		String url = baseUrl + "/orgs/" + org + "/repos?per_page=100&type=all";
		HttpResponse<String> resp = get(url);
		if (resp.statusCode() == 404) {
			return Optional.empty();
		}
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " listing repos for " + org
							+ ": " + resp.body()
			);
		}
		return Optional.of(summaries(resp));
	}

	/**
	 * A personal account's repositories. {@code /users/{login}/repos} returns
	 * only public ones, so this reads {@code /user/repos}, which covers public,
	 * private and archived — for the authenticated user, which is the only
	 * personal account a token can manage.
	 */
	public List<RepositorySummaryResponse> listUserRepos(String login) {
		HttpResponse<String> resp = get(
				baseUrl + "/user/repos?per_page=100&type=owner"
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " listing repos for " + login
							+ ": " + resp.body()
			);
		}
		return summaries(resp);
	}

	private List<RepositorySummaryResponse> summaries(
			HttpResponse<String> resp
	) {
		return collectPaginatedArrayItems(resp, null).stream()
				.map(
						node -> mapper.convertValue(
								node,
								RepositorySummaryResponse.class
						)
				)
				.toList();
	}
```

Update `GitHubClientTest`'s `listOrgRepos` tests: the 404 case now asserts `assertThat(client.listOrgRepos("owner")).isEmpty();` and a new `listUserRepos` test covers `/user/repos`.

- [ ] **Step 6: Give the checker an owner**

`RepositoryState`'s first component becomes the ref, so `createDriftGroups` keeps its two-argument shape — adding a parameter there would touch 106 call sites instead of 35:

```java
public record RepositoryState(
		RepoRef ref,
		ActualRepository repository,
		// … unchanged …
) {

	public String name() {
		return ref.name();
	}
```

`RepositoryChecker.check` takes one owner's repositories instead of listing every owner itself:

```java
	public List<CheckResult.Entry> check(
			String owner,
			List<RepositorySummaryResponse> summaries,
			List<Drifty.Repository> desired
	) throws InterruptedException, ExecutionException {
		Map<String, Drifty.Repository> desiredByName = desired.stream()
				.collect(
						Collectors.toMap(
								r -> r.name,
								r -> r,
								(a, _) -> a,
								LinkedHashMap::new
						)
				);

		List<CheckResult.Entry> results = new ArrayList<>();
		try (ExecutorService executor = Executors
				.newVirtualThreadPerTaskExecutor()) {
			List<Future<CheckResult.Entry>> futures = summaries.stream()
					.map(
							summary -> executor.submit(
									() -> checkOne(
											new RepoRef(owner, summary.name()),
											summary,
											desiredByName.get(summary.name())
									)
							)
					)
					.toList();
			for (Future<CheckResult.Entry> f : futures) {
				results.add(f.get());
			}
		}

		Set<String> found = summaries.stream()
				.map(RepositorySummaryResponse::name)
				.collect(Collectors.toSet());
		desiredByName.keySet()
				.stream()
				.filter(name -> !found.contains(name))
				.map(CheckResult.Entry::missing)
				.forEach(results::add);

		return List.copyOf(results);
	}
```

In `createDriftGroups`, `var ref = new RepoRef(desired.owner, actual.name());` becomes `var ref = actual.ref();`.

- [ ] **Step 7: Drive both owner kinds from main**

In `GitHubCheck.main`, replace the load-and-check block:

```java
		DriftyConfig config = PklConfigLoader.load(configPath.toAbsolutePath());
		…
		var client = new GitHubClient(token);
		var repoChecker = new RepositoryChecker(client, fix, githubSecrets, state);
		var repoEntries = new ArrayList<CheckResult.Entry>();

		for (var entry : config.organizations().entrySet()) {
			String login = entry.getKey();
			System.out.println("Fetching repo list for organization: " + login);
			Optional<List<RepositorySummaryResponse>> repos = client
					.listOrgRepos(login);
			if (repos.isEmpty()) {
				entry.getValue().repositories
						.forEach(r -> repoEntries.add(CheckResult.Entry.missing(r.name)));
				continue;
			}
			repoEntries.addAll(
					repoChecker.check(
							login,
							repos.orElseThrow(),
							entry.getValue().repositories
					)
			);
		}

		for (var entry : config.users().entrySet()) {
			String login = entry.getKey();
			System.out.println("Fetching repo list for user: " + login);
			repoEntries.addAll(
					repoChecker.check(
							login,
							client.listUserRepos(login),
							entry.getValue().repositories
					)
			);
		}

		CheckResult result = CheckResult.ofRepos(repoEntries);
		Report.print(result);
```

`reportMissingSecrets` and `collectMissingSecrets` take `config.allRepositories()`.

- [ ] **Step 8: Update the test fixtures**

`src/test/resources/desired-defaults.pkl`: `repository: drifty.Repository = new { name = "repo" }`.

`Desired.repository` loses its owner argument:

```java
	public static Drifty.Repository repository(String name) {
		return REPOSITORY.withName(name);
	}
```

```bash
sed -i '' 's/Desired\.repository("[^"]*", *\("[^"]*"\))/Desired.repository(\1)/g' \
  $(grep -rl --include='*.java' -F 'Desired.repository(' src/test)
```

Then fix the 35 `new RepositoryState(` sites, each of which passes a bare name first; they become `new RepoRef("owner", "repo")` (add the import where missing).

- [ ] **Step 9: Replace `config/ArloL.pkl` with an example**

`config/ArloL.pkl` is a stale copy of a config that now lives in its own
repository, where it amends this schema over HTTPS. Two things in this
repository still need a config to point at — the `exec-maven-plugin` arguments
and `PklConfigLoaderTest` — so it is replaced rather than simply deleted.

```bash
git rm config/ArloL.pkl
```

Create `config/example.pkl`, a fictional config that exercises the schema
end to end: one organization with a repository group sharing a `local`
template, one personal account, a ruleset, an environment, and a repository
that declares partial management.

```pkl
/// Example configuration. Not a real account — it exists so `./mvnw exec:java`
/// and PklConfigLoaderTest have something to evaluate, and so SPEC.md can
/// point at a complete config.
amends "drifty.pkl"

local checkActions = new StatusCheck {
  context = "check-actions.required-status-check"
  appId = module.githubActionsAppId
}

local mainRuleset: Ruleset = new {
  includePatterns { "refs/heads/main" }
  requiredLinearHistory = true
  noForcePushes = true
  requiredStatusChecks { checkActions }
}

local defaultRepo: Repository = new {
  allowMergeCommit = false
  allowAutoMerge = true
  deleteBranchOnMerge = true
  rulesets { ["main"] = mainRuleset }
}

organizations {
  ["example-org"] {
    description = "An example organization"
    membersCanCreatePages = false
    membersCanForkPrivateRepositories = false

    repositories {
      (defaultRepo) { name = "example-service" }
      (defaultRepo) {
        name = "example-library"
        topics { "java"; "library" }
      }
      (defaultRepo) {
        name = "shared-with-another-team"
        managed { mode = "all_except"; groups { "action_secrets"; "rulesets" } }
      }
    }
  }
}

users {
  ["example-user"] {
    repositories {
      (defaultRepo) { name = "personal-site"; pages = new Pages {} }
    }
  }
}
```

Point `pom.xml`'s exec argument at it:

```xml
						<argument>./config/example.pkl</argument>
```

Rewrite the two `PklConfigLoaderTest` cases that loaded `config/ArloL.pkl` to
load `config/example.pkl` and assert the nested shape:

```java
	@Test
	void loadsExampleConfig() throws IOException {
		DriftyConfig config = PklConfigLoader
				.load(Path.of("config/example.pkl").toAbsolutePath());

		assertThat(config.organizations()).containsOnlyKeys("example-org");
		assertThat(config.users()).containsOnlyKeys("example-user");
		assertThat(config.allRepositories()).extracting(r -> r.name)
				.contains("example-service", "personal-site");
	}

	@Test
	void managedDefaultsToEverything() throws IOException {
		DriftyConfig config = PklConfigLoader
				.load(Path.of("config/example.pkl").toAbsolutePath());

		assertThat(config.allRepositories())
				.filteredOn(repo -> !"shared-with-another-team".equals(repo.name))
				.allSatisfy(repo -> {
					assertThat(repo.managed.mode)
							.isEqualTo(Drifty.ManageMode.ALL_EXCEPT);
					assertThat(repo.managed.groups).isEmpty();
				});
	}
```

Run: `./mvnw test -DskipNativeTests -Dtest=PklConfigLoaderTest`
Expected: PASS.

- [ ] **Step 10: Run everything**

Run: `./mvnw test -DskipNativeTests`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -F - <<'EOF'
Nest repositories under the organization or user that owns them

Claude-Session: https://claude.ai/code/session_014wTAEAEa9wytEzdrqpvBsH
EOF
```

---

### Task 4: Organization endpoints in the client

Every request the org groups need, with its response and request records. No drift group yet — this task ends with a client that can read and write org state and tests that prove it.

**Files:**
- Create: `client/OrganizationResponse.java`, `client/OrganizationUpdateRequest.java`, `client/OrgActionsPermissionsResponse.java`, `client/OrgActionsPermissionsRequest.java`, `client/ActionsEnabledRepositories.java`, `client/AllowedActions.java`, `client/SelectedActions.java`, `client/OrgSecretResponse.java`, `client/OrgSecretRequest.java`, `client/SecretVisibility.java` (all under `src/main/java/io/github/arlol/githubcheck/client/`)
- Modify: `src/main/java/io/github/arlol/githubcheck/client/GitHubClient.java`
- Test: `src/test/java/io/github/arlol/githubcheck/client/GitHubClientTest.java`

**Interfaces:**
- Produces:
  - `Optional<OrganizationResponse> GitHubClient.getOrganization(String org)`
  - `void GitHubClient.updateOrganization(String org, OrganizationUpdateRequest request)`
  - `OrgActionsPermissionsResponse GitHubClient.getOrgActionsPermissions(String org)`
  - `void GitHubClient.updateOrgActionsPermissions(String org, OrgActionsPermissionsRequest request)`
  - `SelectedActions GitHubClient.getOrgSelectedActions(String org)`
  - `void GitHubClient.updateOrgSelectedActions(String org, SelectedActions selected)`
  - `WorkflowPermissions GitHubClient.getOrgWorkflowPermissions(String org)`
  - `void GitHubClient.updateOrgWorkflowPermissions(String org, WorkflowPermissions permissions)`
  - `List<OrgSecretResponse> GitHubClient.getOrgActionSecrets(String org)`
  - `OrgSecretResponse GitHubClient.getOrgActionSecret(String org, String name)`
  - `List<RepositorySummaryResponse> GitHubClient.getOrgActionSecretRepositories(String org, String name)`
  - `SecretPublicKeyResponse GitHubClient.getOrgActionSecretPublicKey(String org)`
  - `void GitHubClient.createOrUpdateOrgActionSecret(String org, String name, String value, SecretVisibility visibility, List<Long> selectedRepositoryIds)`

- [ ] **Step 1: Write the failing client tests**

Add to `GitHubClientTest`:

```java
	@Test
	void getOrganization_parsesSettings() {
		stubFor(get(urlPathEqualTo("/orgs/my-org")).willReturn(okJson("""
				{
				  "login": "my-org",
				  "description": null,
				  "blog": "https://example.com",
				  "default_repository_permission": "read",
				  "members_can_create_repositories": true,
				  "members_can_fork_private_repositories": false,
				  "web_commit_signoff_required": true,
				  "two_factor_requirement_enabled": true
				}
				""")));

		OrganizationResponse org = client.getOrganization("my-org").orElseThrow();

		assertThat(org.login()).isEqualTo("my-org");
		assertThat(org.description()).isNull();
		assertThat(org.blog()).isEqualTo("https://example.com");
		assertThat(org.webCommitSignoffRequired()).isTrue();
		assertThat(org.twoFactorRequirementEnabled()).isTrue();
	}

	@Test
	void getOrganization_missingIsEmpty() {
		stubFor(
				get(urlPathEqualTo("/orgs/nope"))
						.willReturn(aResponse().withStatus(404))
		);

		assertThat(client.getOrganization("nope")).isEmpty();
	}

	@Test
	void updateOrganization_sendsOnlySetFields() {
		stubFor(patch(urlPathEqualTo("/orgs/my-org")).willReturn(okJson("{}")));

		client.updateOrganization(
				"my-org",
				OrganizationUpdateRequest.builder()
						.description("new")
						.membersCanCreatePages(false)
						.build()
		);

		verify(
				patchRequestedFor(urlPathEqualTo("/orgs/my-org")).withRequestBody(
						equalToJson(
								"""
										{"description":"new","members_can_create_pages":false}"""
						)
				)
		);
	}

	@Test
	void getOrgActionsPermissions_parses() {
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/actions/permissions"))
						.willReturn(okJson("""
								{
								  "enabled_repositories": "all",
								  "allowed_actions": "selected",
								  "sha_pinning_required": false
								}
								"""))
		);

		OrgActionsPermissionsResponse permissions = client
				.getOrgActionsPermissions("my-org");

		assertThat(permissions.enabledRepositories())
				.isEqualTo(ActionsEnabledRepositories.ALL);
		assertThat(permissions.allowedActions())
				.isEqualTo(AllowedActions.SELECTED);
		assertThat(permissions.shaPinningRequired()).isFalse();
	}

	@Test
	void getOrgSelectedActions_parses() {
		stubFor(
				get(
						urlPathEqualTo(
								"/orgs/my-org/actions/permissions/selected-actions"
						)
				).willReturn(okJson("""
						{
						  "github_owned_allowed": true,
						  "verified_allowed": false,
						  "patterns_allowed": ["my-org/*"]
						}
						"""))
		);

		SelectedActions selected = client.getOrgSelectedActions("my-org");

		assertThat(selected.githubOwnedAllowed()).isTrue();
		assertThat(selected.verifiedAllowed()).isFalse();
		assertThat(selected.patternsAllowed()).containsExactly("my-org/*");
	}

	@Test
	void getOrgWorkflowPermissions_parses() {
		stubFor(
				get(
						urlPathEqualTo(
								"/orgs/my-org/actions/permissions/workflow"
						)
				).willReturn(okJson("""
						{
						  "default_workflow_permissions": "read",
						  "can_approve_pull_request_reviews": false
						}
						"""))
		);

		WorkflowPermissions permissions = client
				.getOrgWorkflowPermissions("my-org");

		assertThat(permissions.defaultWorkflowPermissions())
				.isEqualTo(WorkflowPermissions.DefaultWorkflowPermissions.READ);
		assertThat(permissions.canApprovePullRequestReviews()).isFalse();
	}

	@Test
	void getOrgActionSecrets_parsesVisibility() {
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/actions/secrets"))
						.willReturn(okJson("""
								{
								  "total_count": 2,
								  "secrets": [
								    {"name":"PAT","created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-02T00:00:00Z","visibility":"selected"},
								    {"name":"NPM","created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-02T00:00:00Z","visibility":"all"}
								  ]
								}
								"""))
		);

		List<OrgSecretResponse> secrets = client.getOrgActionSecrets("my-org");

		assertThat(secrets).extracting(OrgSecretResponse::name)
				.containsExactly("PAT", "NPM");
		assertThat(secrets.getFirst().visibility())
				.isEqualTo(SecretVisibility.SELECTED);
	}

	@Test
	void createOrUpdateOrgActionSecret_sendsVisibilityAndRepositoryIds() {
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/actions/secrets/public-key"))
						.willReturn(okJson("""
								{"key_id":"1","key":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="}
								"""))
		);
		stubFor(
				put(urlPathEqualTo("/orgs/my-org/actions/secrets/PAT"))
						.willReturn(aResponse().withStatus(204))
		);

		client.createOrUpdateOrgActionSecret(
				"my-org",
				"PAT",
				"value",
				SecretVisibility.SELECTED,
				List.of(1L, 2L)
		);

		verify(
				putRequestedFor(
						urlPathEqualTo("/orgs/my-org/actions/secrets/PAT")
				).withRequestBody(matchingJsonPath("$.visibility", equalTo("selected")))
						.withRequestBody(
								matchingJsonPath("$.selected_repository_ids[1]", equalTo("2"))
						)
		);
	}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -DskipNativeTests -Dtest=GitHubClientTest`
Expected: FAIL — none of these types or methods exist.

- [ ] **Step 3: Add the wire records**

```java
// SecretVisibility.java
package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum SecretVisibility {

	@JsonProperty("all")
	ALL,

	@JsonProperty("private")
	PRIVATE,

	@JsonProperty("selected")
	SELECTED

}
```

`ActionsEnabledRepositories` (`ALL`, `NONE`, `SELECTED` → `"all"`, `"none"`, `"selected"`) and `AllowedActions` (`ALL`, `LOCAL_ONLY`, `SELECTED` → `"all"`, `"local_only"`, `"selected"`) follow the same shape.

```java
// OrganizationResponse.java — the managed subset of GET /orgs/{org}
package io.github.arlol.githubcheck.client;

public record OrganizationResponse(
		String login,
		String name,
		String description,
		String blog,
		String company,
		String email,
		String location,
		String twitterUsername,
		Boolean hasOrganizationProjects,
		Boolean hasRepositoryProjects,
		String defaultRepositoryPermission,
		Boolean membersCanCreateRepositories,
		Boolean membersCanCreateInternalRepositories,
		Boolean membersCanCreatePrivateRepositories,
		Boolean membersCanCreatePublicRepositories,
		Boolean membersCanCreatePages,
		Boolean membersCanCreatePublicPages,
		Boolean membersCanCreatePrivatePages,
		Boolean membersCanForkPrivateRepositories,
		Boolean webCommitSignoffRequired,
		Boolean deployKeysEnabledForRepositories,
		String defaultRepositoryBranch,
		Boolean twoFactorRequirementEnabled,
		Boolean membersCanDeleteRepositories,
		Boolean membersCanChangeRepoVisibility,
		Boolean membersCanInviteOutsideCollaborators,
		Boolean membersCanDeleteIssues,
		Boolean membersCanCreateTeams,
		Boolean membersCanViewDependencyInsights,
		Boolean readersCanCreateDiscussions,
		Boolean displayCommenterFullNameSettingEnabled
) {
}
```

Every field is a wrapper: GitHub omits several of them for a token without admin rights, and `FAIL_ON_NULL_FOR_PRIMITIVES` is enabled on the client's mapper.

`OrganizationUpdateRequest` mirrors `RepositoryUpdateRequest`: `@JsonInclude(NON_NULL)`, one nullable component per writable setting from the spec's first table, plus a `builder()` with one setter per component. Copy `RepositoryUpdateRequest`'s class javadoc, adapted: the nullability is what keeps the PATCH to the drifted settings.

```java
// OrgActionsPermissionsResponse.java
public record OrgActionsPermissionsResponse(
		ActionsEnabledRepositories enabledRepositories,
		AllowedActions allowedActions,
		Boolean shaPinningRequired
) {
}

// OrgActionsPermissionsRequest.java — enabled_repositories is required by the
// API even when only allowed_actions drifted.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrgActionsPermissionsRequest(
		ActionsEnabledRepositories enabledRepositories,
		AllowedActions allowedActions,
		Boolean shaPinningRequired
) {
}

// SelectedActions.java — same shape in the response and the request, like
// WorkflowPermissions.
public record SelectedActions(
		boolean githubOwnedAllowed,
		boolean verifiedAllowed,
		List<String> patternsAllowed
) {

	public SelectedActions {
		patternsAllowed = patternsAllowed == null ? List.of()
				: List.copyOf(patternsAllowed);
	}

}

// OrgSecretResponse.java
public record OrgSecretResponse(
		String name,
		String createdAt,
		String updatedAt,
		SecretVisibility visibility
) {
}

// OrgSecretRequest.java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrgSecretRequest(
		String encryptedValue,
		String keyId,
		SecretVisibility visibility,
		List<Long> selectedRepositoryIds
) {
}
```

- [ ] **Step 4: Add the client methods**

Add an `orgUrl` helper beside `repoUrl` and the methods below. Follow the existing error-message style (`"HTTP " + status + " …"`), and keep them together under a `// ─── Organizations` banner comment.

```java
	private String orgUrl(String org) {
		return baseUrl + "/orgs/" + org;
	}

	public Optional<OrganizationResponse> getOrganization(String org) {
		HttpResponse<String> resp = get(orgUrl(org));
		if (resp.statusCode() == 404) {
			return Optional.empty();
		}
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " fetching organization "
							+ org + ": " + resp.body()
			);
		}
		return Optional.of(readValue(resp.body(), OrganizationResponse.class));
	}

	public void updateOrganization(
			String org,
			OrganizationUpdateRequest request
	) {
		HttpResponse<String> resp = patch(orgUrl(org), writeValue(request));
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " updating organization "
							+ org + ": " + resp.body()
			);
		}
	}

	public OrgActionsPermissionsResponse getOrgActionsPermissions(String org) {
		HttpResponse<String> resp = get(
				orgUrl(org) + "/actions/permissions"
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " GET actions permissions on " + org + ": "
							+ resp.body()
			);
		}
		return readValue(resp.body(), OrgActionsPermissionsResponse.class);
	}

	public void updateOrgActionsPermissions(
			String org,
			OrgActionsPermissionsRequest request
	) {
		HttpResponse<String> resp = put(
				orgUrl(org) + "/actions/permissions",
				writeValue(request)
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " updating actions permissions on " + org + ": "
							+ resp.body()
			);
		}
	}

	public SelectedActions getOrgSelectedActions(String org) { /* GET …/selected-actions, 200 */ }

	public void updateOrgSelectedActions(String org, SelectedActions selected) { /* PUT …/selected-actions, 204 */ }

	public WorkflowPermissions getOrgWorkflowPermissions(String org) { /* GET …/permissions/workflow, 200 */ }

	public void updateOrgWorkflowPermissions(String org, WorkflowPermissions permissions) { /* PUT …/permissions/workflow, 204 */ }

	public List<OrgSecretResponse> getOrgActionSecrets(String org) {
		HttpResponse<String> resp = get(
				orgUrl(org) + "/actions/secrets?per_page=100"
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " for org action secrets on "
							+ org + ": " + resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, "secrets").stream()
				.map(s -> mapper.convertValue(s, OrgSecretResponse.class))
				.toList();
	}

	public OrgSecretResponse getOrgActionSecret(String org, String name) { /* GET …/secrets/{name}, 200 */ }

	/** The repositories a {@code selected} secret is shared with. */
	public List<RepositorySummaryResponse> getOrgActionSecretRepositories(
			String org,
			String name
	) {
		HttpResponse<String> resp = get(
				orgUrl(org) + "/actions/secrets/" + name
						+ "/repositories?per_page=100"
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " for repositories of org "
							+ "secret " + name + " on " + org + ": "
							+ resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, "repositories").stream()
				.map(
						node -> mapper.convertValue(
								node,
								RepositorySummaryResponse.class
						)
				)
				.toList();
	}

	public SecretPublicKeyResponse getOrgActionSecretPublicKey(String org) { /* GET …/secrets/public-key, 200 */ }

	public void createOrUpdateOrgActionSecret(
			String org,
			String name,
			String value,
			SecretVisibility visibility,
			List<Long> selectedRepositoryIds
	) {
		var publicKey = getOrgActionSecretPublicKey(org);
		var request = new OrgSecretRequest(
				Secrets.encryptSecret(publicKey.key(), value),
				publicKey.keyId(),
				visibility,
				visibility == SecretVisibility.SELECTED ? selectedRepositoryIds
						: null
		);
		HttpResponse<String> resp = put(
				orgUrl(org) + "/actions/secrets/" + name,
				writeValue(request)
		);
		if (resp.statusCode() != 201 && resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " PUT org action secret "
							+ name + " on " + org + ": " + resp.body()
			);
		}
	}
```

Every `/* … */` placeholder above is a two-line method in the shape of the one above it: send, check the documented status, parse or throw. Write them out.

- [ ] **Step 5: Run the tests**

Run: `./mvnw test -DskipNativeTests -Dtest=GitHubClientTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -F - <<'EOF'
Read and write organization settings in the GitHub client

Claude-Session: https://claude.ai/code/session_014wTAEAEa9wytEzdrqpvBsH
EOF
```

---

### Task 5: Actual types and OrganizationState

The translation layer, so no org group ever sees a `client` response record.

**Files:**
- Create: `src/main/java/io/github/arlol/githubcheck/actual/ActualOrganization.java`
- Create: `src/main/java/io/github/arlol/githubcheck/actual/ActualOrgActionsPermissions.java`
- Create: `src/main/java/io/github/arlol/githubcheck/actual/ActualSelectedActions.java`
- Create: `src/main/java/io/github/arlol/githubcheck/actual/ActualOrgSecret.java`
- Create: `src/main/java/io/github/arlol/githubcheck/OrganizationState.java`
- Modify: `src/main/java/io/github/arlol/githubcheck/ActualTypes.java`
- Test: `src/test/java/io/github/arlol/githubcheck/ActualTypesTest.java`

**Interfaces:**
- Consumes: the client records from Task 4.
- Produces:
  - `ActualOrganization` with one component per row of both spec tables, `String` for text and `boolean` for flags
  - `ActualOrgActionsPermissions(ActionsEnabledRepositories enabledRepositories, AllowedActions allowedActions, boolean shaPinningRequired, ActualSelectedActions selectedActions)`
  - `ActualSelectedActions(boolean githubOwnedAllowed, boolean verifiedAllowed, List<String> patternsAllowed)`
  - `ActualOrgSecret(String name, String updatedAt, SecretVisibility visibility, List<String> selectedRepositories)`
  - `ActualTypes.organization(OrganizationResponse)`, `ActualTypes.orgActionsPermissions(OrgActionsPermissionsResponse, SelectedActions)`, `ActualTypes.orgSecret(OrgSecretResponse, List<String>)`
  - `OrganizationState(String login, ActualOrganization settings, ActualOrgActionsPermissions actionsPermissions, ActualWorkflowPermissions workflowPermissions, List<ActualOrgSecret> actionSecrets)`

- [ ] **Step 1: Write the failing translation test**

Add to `ActualTypesTest`:

```java
	@Test
	void organization_normalisesNullsAndMissingFlags() {
		var response = new OrganizationResponse(
				"my-org",
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				"read",
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				"main",
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null
		);

		ActualOrganization actual = ActualTypes.organization(response);

		assertThat(actual.description()).isEmpty();
		assertThat(actual.displayName()).isEmpty();
		assertThat(actual.websiteUrl()).isEmpty();
		assertThat(actual.membersCanCreatePages()).isFalse();
		assertThat(actual.defaultRepositoryBranch()).isEqualTo("main");
	}

	@Test
	void orgActionsPermissions_keepsSelectedActionsOnlyWhenSelected() {
		var response = new OrgActionsPermissionsResponse(
				ActionsEnabledRepositories.ALL,
				AllowedActions.ALL,
				false
		);

		ActualOrgActionsPermissions actual = ActualTypes
				.orgActionsPermissions(response, null);

		assertThat(actual.selectedActions()).isNull();
		assertThat(actual.shaPinningRequired()).isFalse();
	}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -DskipNativeTests -Dtest=ActualTypesTest`
Expected: FAIL — the types do not exist.

- [ ] **Step 3: Write the actual records**

`ActualOrganization` has 30 components — the twenty writable settings and the ten check-only ones — in the order the spec's two tables list them, named after the Pkl field rather than the wire field, `String` where the API returns text and `boolean` where it returns a flag:

```java
public record ActualOrganization(
		String displayName,
		String description,
		String websiteUrl,
		String company,
		String email,
		String location,
		String twitterUsername,
		boolean hasOrganizationProjects,
		boolean hasRepositoryProjects,
		String defaultRepositoryPermission,
		boolean membersCanCreateRepositories,
		boolean membersCanCreatePublicRepositories,
		boolean membersCanCreatePrivateRepositories,
		boolean membersCanCreateInternalRepositories,
		boolean membersCanCreatePages,
		boolean membersCanCreatePublicPages,
		boolean membersCanCreatePrivatePages,
		boolean membersCanForkPrivateRepositories,
		boolean webCommitSignoffRequired,
		boolean deployKeysEnabledForRepositories,
		String defaultRepositoryBranch,
		boolean twoFactorRequirementEnabled,
		boolean membersCanDeleteRepositories,
		boolean membersCanChangeRepoVisibility,
		boolean membersCanInviteOutsideCollaborators,
		boolean membersCanDeleteIssues,
		boolean membersCanCreateTeams,
		boolean membersCanViewDependencyInsights,
		boolean readersCanCreateDiscussions,
		boolean displayCommenterFullNameSettingEnabled
) {
}
```
 Its javadoc says what `ActualRepository`'s says about nulls: GitHub returns `null` for an unset description, blog, company, email, location or twitter handle where the config says `""`, and omits the policy flags entirely for a token without admin rights, which reads here as `false`.

```java
public record ActualOrgActionsPermissions(
		ActionsEnabledRepositories enabledRepositories,
		AllowedActions allowedActions,
		boolean shaPinningRequired,
		/** Null unless allowedActions is SELECTED; GitHub serves the list from
		 * its own endpoint and only that value makes it meaningful. */
		ActualSelectedActions selectedActions
) {
}
```

```java
/**
 * An organization Actions secret as GitHub lists it. Values are never
 * readable; the update timestamp is what drifty compares against its recorded
 * baseline. Visibility is listed with the secret, the repository names behind
 * a {@code selected} visibility are not — they come from a second request.
 */
public record ActualOrgSecret(
		String name,
		String updatedAt,
		SecretVisibility visibility,
		List<String> selectedRepositories
) {

	public ActualOrgSecret {
		selectedRepositories = List.copyOf(selectedRepositories);
	}

}
```

- [ ] **Step 4: Write the translators**

In `ActualTypes`, under a `// ─── Organizations` banner:

```java
	public static ActualOrganization organization(
			OrganizationResponse response
	) {
		return new ActualOrganization(
				text(response.name()),
				text(response.description()),
				text(response.blog()),
				text(response.company()),
				text(response.email()),
				text(response.location()),
				text(response.twitterUsername()),
				flag(response.hasOrganizationProjects()),
				// … one per component, in declaration order …
				text(response.defaultRepositoryBranch()),
				flag(response.twoFactorRequirementEnabled())
		);
	}

	private static String text(String value) {
		return value == null ? "" : value;
	}

	private static boolean flag(Boolean value) {
		return value != null && value;
	}
```

Reuse `text`/`flag` if equivalents already exist in the class; otherwise add them privately.

```java
	public static ActualOrgActionsPermissions orgActionsPermissions(
			OrgActionsPermissionsResponse response,
			SelectedActions selected
	) {
		return new ActualOrgActionsPermissions(
				response.enabledRepositories(),
				response.allowedActions(),
				flag(response.shaPinningRequired()),
				selected == null ? null
						: new ActualSelectedActions(
								selected.githubOwnedAllowed(),
								selected.verifiedAllowed(),
								selected.patternsAllowed()
						)
		);
	}

	public static ActualOrgSecret orgSecret(
			OrgSecretResponse response,
			List<String> selectedRepositories
	) {
		return new ActualOrgSecret(
				response.name(),
				response.updatedAt(),
				response.visibility(),
				selectedRepositories
		);
	}
```

- [ ] **Step 5: Write `OrganizationState`**

```java
package io.github.arlol.githubcheck;

import java.util.List;

import io.github.arlol.githubcheck.actual.ActualOrgActionsPermissions;
import io.github.arlol.githubcheck.actual.ActualOrgSecret;
import io.github.arlol.githubcheck.actual.ActualOrganization;
import io.github.arlol.githubcheck.actual.ActualWorkflowPermissions;

/**
 * Everything drifty knows about one organization on GitHub, in drifty's own
 * vocabulary — the org-level counterpart of {@link RepositoryState}, and held
 * to the same rule: no field here is a GitHub response type.
 * <p>
 * A field is null when the organization does not manage the group that reads
 * it: the request is never sent, and the group that would compare it is not
 * built.
 */
public record OrganizationState(
		String login,
		ActualOrganization settings,
		ActualOrgActionsPermissions actionsPermissions,
		ActualWorkflowPermissions workflowPermissions,
		List<ActualOrgSecret> actionSecrets
) {

	public OrganizationState {
		actionSecrets = List.copyOf(actionSecrets);
	}

}
```

- [ ] **Step 6: Extend the boundary test**

In `ActualStateBoundaryTest`, beside `repositoryStateHoldsNoGitHubResponseTypes`:

```java
	@Test
	void organizationStateHoldsNoGitHubResponseTypes() {
		assertThat(clientTypesHeldBy(OrganizationState.class))
				.as("client types reachable from OrganizationState's fields")
				.isEmpty();
	}
```

- [ ] **Step 7: Run the tests**

Run: `./mvnw test -DskipNativeTests -Dtest='ActualTypesTest,ActualStateBoundaryTest'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -F - <<'EOF'
Translate organization responses into drifty's own types

Claude-Session: https://claude.ai/code/session_014wTAEAEa9wytEzdrqpvBsH
EOF
```

---

### Task 6: org_settings, OrganizationChecker and the report section

The first end-to-end org group: schema fields, the group name union, the checker that fetches and compares, and the Organizations section in the report.

**Files:**
- Modify: `config/drifty.pkl`
- Create: `src/main/java/io/github/arlol/githubcheck/drift/OrgSettingsDriftGroup.java`
- Create: `src/main/java/io/github/arlol/githubcheck/OrganizationChecker.java`
- Modify: `src/main/java/io/github/arlol/githubcheck/Report.java`
- Modify: `src/main/java/io/github/arlol/githubcheck/GitHubCheck.java`
- Modify: `src/main/java/io/github/arlol/githubcheck/drift/ManagedGroups.java`
- Modify: `src/test/resources/desired-defaults.pkl`
- Modify: `src/test/java/io/github/arlol/githubcheck/testsupport/Desired.java`
- Create: `src/test/java/io/github/arlol/githubcheck/drift/OrgSettingsDriftGroupTest.java`
- Create: `src/test/java/io/github/arlol/githubcheck/OrganizationCheckerTest.java`
- Modify: `src/test/java/io/github/arlol/githubcheck/DriftPathNamespacingTest.java`

**Interfaces:**
- Consumes: `ActualOrganization`, `OrganizationState`, the client methods from Task 4, `DriftFixer`, `ManagedGroups`.
- Produces:
  - `Drifty.OrgGroupName` with `ORG_SETTINGS`, `ORG_ACTIONS_PERMISSIONS`, `ORG_WORKFLOW_PERMISSIONS`, `ORG_ACTION_SECRETS`
  - `Drifty.OrgManaged` with `mode` and `groups`
  - `static ManagedGroups<Drifty.OrgGroupName> ManagedGroups.of(Drifty.OrgManaged)`
  - `OrgSettingsDriftGroup(Drifty.Organization desired, ActualOrganization actual, GitHubClient client, String org)`
  - `OrganizationChecker(GitHubClient client, boolean fix, Map<String, String> githubSecrets, DriftyState state)` with `CheckResult.Entry check(String login, Drifty.Organization desired, List<RepositorySummaryResponse> repos)`, `OrganizationState fetchState(String login, ManagedGroups<Drifty.OrgGroupName> managed)`, `Map<DriftGroup<Drifty.OrgGroupName>, List<DriftFix>> computeGroupDrifts(OrganizationState actual, Drifty.Organization desired, Map<String, Long> repositoryIds)` (the org twin of `RepositoryChecker.computeGroupDrifts`: build the groups, keep the ones that detected drift, in insertion order) and `List<DriftGroup<Drifty.OrgGroupName>> createDriftGroups(OrganizationState actual, Drifty.Organization desired, Map<String, Long> repositoryIds)`

- [ ] **Step 1: Write the failing group test**

Create `OrgSettingsDriftGroupTest`:

```java
package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.testsupport.Actual;
import io.github.arlol.githubcheck.testsupport.Desired;

class OrgSettingsDriftGroupTest {

	@Test
	void noDriftWhenEverythingMatches() {
		var group = new OrgSettingsDriftGroup(
				Desired.organization(),
				Actual.organization(),
				null,
				"my-org"
		);

		assertThat(group.detect().getFirst().items()).isEmpty();
	}

	@Test
	void detectsWritableDrift() {
		var group = new OrgSettingsDriftGroup(
				Desired.organization().withDescription("wanted"),
				Actual.organization(),
				null,
				"my-org"
		);

		List<DriftItem> items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).extracting(DriftItem::path)
				.containsExactly("org_settings.description");
	}

	@Test
	void checkOnlySettingIsReportedAndNotWritten() {
		var group = new OrgSettingsDriftGroup(
				Desired.organization().withMembersCanDeleteRepositories(false),
				Actual.organization(),
				null,
				"my-org"
		);

		FixResult result = group.detect().getFirst().fix().execute();

		assertThat(result.unfixedItems()).singleElement()
				.satisfies(unfixed -> {
					assertThat(unfixed.item().path())
							.isEqualTo("org_settings.members_can_delete_repositories");
					assertThat(unfixed.reason())
							.contains("cannot be changed through the API");
				});
	}

}
```

`Actual.organization()` is a new test-support factory returning an `ActualOrganization` with GitHub's defaults — the actual-side counterpart of `Desired`. Create `src/test/java/io/github/arlol/githubcheck/testsupport/Actual.java` holding it, with `withX`-style helpers written by hand only where a test needs them (records give none), or build the record inline in each test if that reads better.

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -DskipNativeTests -Dtest=OrgSettingsDriftGroupTest`
Expected: FAIL — `Desired.organization()` and `OrgSettingsDriftGroup` do not exist.

- [ ] **Step 3: Add the schema**

In `config/drifty.pkl`, add the union and the managed class beside the existing `GroupName`/`Managed` pair:

```pkl
/// Every drift group drifty can check on an organization. Separate from
/// GroupName because neither scope's groups can be checked on the other, and
/// the union is what makes a name from the wrong scope fail at config-eval.
typealias OrgGroupName =
  "org_settings"
  | "org_actions_permissions"
  | "org_workflow_permissions"
  | "org_action_secrets"

class OrgManaged {
  mode: ManageMode = "all_except"
  groups: Listing<OrgGroupName> = new {}
}

typealias RepositoryPermission = "none" | "read" | "write" | "admin"
```

Then fill in `Organization` (added empty in Task 3):

```pkl
/// Desired state for a GitHub organization, keyed by login in `organizations`.
/// Defaults match GitHub's defaults for a new organization.
class Organization {
  managed: OrgManaged = new {}

  displayName: String = ""
  description: String = ""
  websiteUrl: String = ""
  company: String = ""
  email: String = ""
  location: String = ""
  twitterUsername: String = ""

  hasOrganizationProjects: Boolean = true
  hasRepositoryProjects: Boolean = true
  defaultRepositoryPermission: RepositoryPermission = "read"
  membersCanCreateRepositories: Boolean = true
  membersCanCreatePublicRepositories: Boolean = true
  membersCanCreatePrivateRepositories: Boolean = true
  membersCanCreateInternalRepositories: Boolean = false
  membersCanCreatePages: Boolean = true
  membersCanCreatePublicPages: Boolean = true
  membersCanCreatePrivatePages: Boolean = true
  membersCanForkPrivateRepositories: Boolean = false
  webCommitSignoffRequired: Boolean = false
  deployKeysEnabledForRepositories: Boolean = false

  /// Check-only below: GitHub returns these but PATCH /orgs/{org} accepts
  /// none of them, so drifty reports drift and never writes it.
  defaultRepositoryBranch: String = "main"
  twoFactorRequirementEnabled: Boolean = false
  membersCanDeleteRepositories: Boolean = true
  membersCanChangeRepoVisibility: Boolean = true
  membersCanInviteOutsideCollaborators: Boolean = true
  membersCanDeleteIssues: Boolean = false
  membersCanCreateTeams: Boolean = true
  membersCanViewDependencyInsights: Boolean = true
  readersCanCreateDiscussions: Boolean = false
  displayCommenterFullNameSettingEnabled: Boolean = false

  repositories: Listing<Repository> = new {}
}
```

Run `./mvnw generate-sources`.

Add to `src/test/resources/desired-defaults.pkl`:

```pkl
organization: drifty.Organization = new {}
```

and to `Desired`:

```java
	private static final Drifty.Organization ORGANIZATION;
	// … ORGANIZATION = root.get("organization").as(Drifty.Organization.class);

	/** An organization with GitHub's defaults, which is what the schema declares. */
	public static Drifty.Organization organization() {
		return ORGANIZATION;
	}
```

- [ ] **Step 4: Teach `ManagedGroups` the org union**

```java
	public static ManagedGroups<Drifty.OrgGroupName> of(
			Drifty.OrgManaged managed
	) {
		return of(Drifty.OrgGroupName.class, managed.mode, managed.groups);
	}
```

- [ ] **Step 5: Write `OrgSettingsDriftGroup`**

Copy `RepoSettingsDriftGroup`'s structure exactly — the `Setting` record with `of`/`checkOnly`, `detectDrift`, `fix`, `write`, `writeIndividually`, `request` — substituting `OrganizationUpdateRequest.Builder`, `client.updateOrganization(org, …)` and the settings below. Keep the javadoc explaining why the body is built from the drifted entries and why a rejected PATCH is re-sent per field.

```java
	private static final String NOT_WRITABLE = "cannot be changed through the API: PATCH /orgs/{org} does not accept this setting";
```

One `Setting` per row, in this order. `Setting.of(path, wanted, got, write)` for the writable ones and `Setting.checkOnly(path, wanted, got, NOT_WRITABLE)` for the rest:

| Path | Wanted | Got | Builder call |
|---|---|---|---|
| `name` | `desired.displayName` | `actual.displayName()` | `b.name(desired.displayName)` |
| `description` | `desired.description` | `actual.description()` | `b.description(desired.description)` |
| `blog` | `desired.websiteUrl` | `actual.websiteUrl()` | `b.blog(desired.websiteUrl)` |
| `company` | `desired.company` | `actual.company()` | `b.company(desired.company)` |
| `email` | `desired.email` | `actual.email()` | `b.email(desired.email)` |
| `location` | `desired.location` | `actual.location()` | `b.location(desired.location)` |
| `twitter_username` | `desired.twitterUsername` | `actual.twitterUsername()` | `b.twitterUsername(desired.twitterUsername)` |
| `has_organization_projects` | `desired.hasOrganizationProjects` | `actual.hasOrganizationProjects()` | `b.hasOrganizationProjects(…)` |
| `has_repository_projects` | `desired.hasRepositoryProjects` | `actual.hasRepositoryProjects()` | `b.hasRepositoryProjects(…)` |
| `default_repository_permission` | `PklTypes.repositoryPermission(desired.defaultRepositoryPermission)` | `actual.defaultRepositoryPermission()` | `b.defaultRepositoryPermission(…)` |
| `members_can_create_repositories` | `desired.membersCanCreateRepositories` | `actual.membersCanCreateRepositories()` | `b.membersCanCreateRepositories(…)` |
| `members_can_create_public_repositories` | `desired.membersCanCreatePublicRepositories` | `actual.membersCanCreatePublicRepositories()` | `b.membersCanCreatePublicRepositories(…)` |
| `members_can_create_private_repositories` | `desired.membersCanCreatePrivateRepositories` | `actual.membersCanCreatePrivateRepositories()` | `b.membersCanCreatePrivateRepositories(…)` |
| `members_can_create_internal_repositories` | `desired.membersCanCreateInternalRepositories` | `actual.membersCanCreateInternalRepositories()` | `b.membersCanCreateInternalRepositories(…)` |
| `members_can_create_pages` | `desired.membersCanCreatePages` | `actual.membersCanCreatePages()` | `b.membersCanCreatePages(…)` |
| `members_can_create_public_pages` | `desired.membersCanCreatePublicPages` | `actual.membersCanCreatePublicPages()` | `b.membersCanCreatePublicPages(…)` |
| `members_can_create_private_pages` | `desired.membersCanCreatePrivatePages` | `actual.membersCanCreatePrivatePages()` | `b.membersCanCreatePrivatePages(…)` |
| `members_can_fork_private_repositories` | `desired.membersCanForkPrivateRepositories` | `actual.membersCanForkPrivateRepositories()` | `b.membersCanForkPrivateRepositories(…)` |
| `web_commit_signoff_required` | `desired.webCommitSignoffRequired` | `actual.webCommitSignoffRequired()` | `b.webCommitSignoffRequired(…)` |
| `deploy_keys_enabled_for_repositories` | `desired.deployKeysEnabledForRepositories` | `actual.deployKeysEnabledForRepositories()` | `b.deployKeysEnabledForRepositories(…)` |
| `default_repository_branch` | `desired.defaultRepositoryBranch` | `actual.defaultRepositoryBranch()` | check-only |
| `two_factor_requirement_enabled` | `desired.twoFactorRequirementEnabled` | `actual.twoFactorRequirementEnabled()` | check-only |
| `members_can_delete_repositories` | `desired.membersCanDeleteRepositories` | `actual.membersCanDeleteRepositories()` | check-only |
| `members_can_change_repo_visibility` | `desired.membersCanChangeRepoVisibility` | `actual.membersCanChangeRepoVisibility()` | check-only |
| `members_can_invite_outside_collaborators` | `desired.membersCanInviteOutsideCollaborators` | `actual.membersCanInviteOutsideCollaborators()` | check-only |
| `members_can_delete_issues` | `desired.membersCanDeleteIssues` | `actual.membersCanDeleteIssues()` | check-only |
| `members_can_create_teams` | `desired.membersCanCreateTeams` | `actual.membersCanCreateTeams()` | check-only |
| `members_can_view_dependency_insights` | `desired.membersCanViewDependencyInsights` | `actual.membersCanViewDependencyInsights()` | check-only |
| `readers_can_create_discussions` | `desired.readersCanCreateDiscussions` | `actual.readersCanCreateDiscussions()` | check-only |
| `display_commenter_full_name_setting_enabled` | `desired.displayCommenterFullNameSettingEnabled` | `actual.displayCommenterFullNameSettingEnabled()` | check-only |

`PklTypes.repositoryPermission` is new:

```java
	public static String repositoryPermission(Drifty.RepositoryPermission p) {
		return p.toString();
	}
```

`OrganizationUpdateRequest.defaultRepositoryPermission` is a `String`, because GitHub's four values do not need an enum anywhere else.

- [ ] **Step 6: Write `OrganizationChecker`**

```java
public class OrganizationChecker {

	private final GitHubClient client;
	private final boolean fix;
	private final Map<String, String> githubSecrets;
	private final DriftyState state;

	public OrganizationChecker(
			GitHubClient client,
			boolean fix,
			Map<String, String> githubSecrets,
			DriftyState state
	) { … }

	public CheckResult.Entry check(
			String login,
			Drifty.Organization desired,
			List<RepositorySummaryResponse> repos
	) {
		ManagedGroups<Drifty.OrgGroupName> managed = ManagedGroups
				.of(desired.managed);
		List<String> unmanaged = managed.unmanaged()
				.stream()
				.map(Drifty.OrgGroupName::toString)
				.toList();
		try {
			OrganizationState actual = fetchState(login, managed);
			var groupDrifts = computeGroupDrifts(
					actual,
					desired,
					repositoryIds(repos)
			);
			if (fix) {
				var outcome = DriftFixer.applyFixes(groupDrifts);
				return CheckResult.Entry.fixed(
						login,
						DriftFixer.render(outcome.unfixedItems()),
						DriftFixer.fixReports(outcome)
				);
			}
			List<String> diffs = groupDrifts.values()
					.stream()
					.flatMap(List::stream)
					.flatMap(driftFix -> driftFix.items().stream())
					.map(DriftItem::message)
					.toList();
			if (diffs.isEmpty()) {
				return CheckResult.Entry.ok(login, unmanaged);
			}
			return CheckResult.Entry.drift(
					login,
					diffs,
					groupDrifts.keySet()
							.stream()
							.map(group -> group.name().toString())
							.toList(),
					unmanaged
			);
		} catch (GitHubApiException e) {
			return CheckResult.Entry.error(login, e.getMessage());
		}
	}

	private static Map<String, Long> repositoryIds(
			List<RepositorySummaryResponse> repos
	) {
		return repos.stream()
				.filter(repo -> repo.id() != null)
				.collect(
						Collectors.toMap(
								RepositorySummaryResponse::name,
								RepositorySummaryResponse::id,
								(a, _) -> a
						)
				);
	}
```

`fetchState` guards each group's requests, and says why the organization read is not guarded:

```java
	/**
	 * Reads the organization state, one request per managed group.
	 * <p>
	 * {@code GET /orgs/{org}} is sent even when {@code org_settings} is
	 * unmanaged: it is how drifty learns the organization exists, and any
	 * member can read it. Every other request here belongs to a group and is
	 * skipped with it — filtering a group out of the comparison alone would
	 * still send its request, and an organization someone else administers is
	 * where those return 403.
	 */
	OrganizationState fetchState(
			String login,
			ManagedGroups<Drifty.OrgGroupName> managed
	) {
		var settings = client.getOrganization(login)
				.map(ActualTypes::organization)
				.orElse(null);

		ActualOrgActionsPermissions permissions = null;
		if (managed.manages(Drifty.OrgGroupName.ORG_ACTIONS_PERMISSIONS)) {
			var response = client.getOrgActionsPermissions(login);
			var selected = response.allowedActions() == AllowedActions.SELECTED
					? client.getOrgSelectedActions(login)
					: null;
			permissions = ActualTypes
					.orgActionsPermissions(response, selected);
		}

		var workflowPermissions = managed
				.manages(Drifty.OrgGroupName.ORG_WORKFLOW_PERMISSIONS)
						? ActualTypes.workflowPermissions(
								client.getOrgWorkflowPermissions(login)
						)
						: null;

		List<ActualOrgSecret> secrets = managed
				.manages(Drifty.OrgGroupName.ORG_ACTION_SECRETS)
						? orgSecrets(login)
						: List.of();

		return new OrganizationState(
				login,
				settings,
				permissions,
				workflowPermissions,
				secrets
		);
	}
```

`check` turns a null `settings` into `CheckResult.Entry.missing(login)` before computing drift. `orgSecrets` arrives in Task 9; until then it returns `List.of()`.

`createDriftGroups` builds only `OrgSettingsDriftGroup` for now and ends with the same `onlyManaged` filter as `RepositoryChecker`:

```java
	List<DriftGroup<Drifty.OrgGroupName>> createDriftGroups(
			OrganizationState actual,
			Drifty.Organization desired,
			Map<String, Long> repositoryIds
	) {
		var groups = new ArrayList<DriftGroup<Drifty.OrgGroupName>>();
		groups.add(
				new OrgSettingsDriftGroup(
						desired,
						actual.settings(),
						client,
						actual.login()
				)
		);
		ManagedGroups<Drifty.OrgGroupName> managed = ManagedGroups
				.of(desired.managed);
		return groups.stream()
				.filter(group -> managed.manages(group.name()))
				.toList();
	}
```

- [ ] **Step 7: Print the organizations section**

In `Report.print`:

```java
	public static void print(CheckResult result) {
		if (!result.orgs().isEmpty()) {
			System.out.println("=== Organizations ===");
			printEntries(
					result.orgs(),
					"not in desired config",
					"in config but not found on GitHub"
			);
			System.out.println();
			System.out.println("=== Repositories ===");
		}
		printEntries(
				result.repos(),
				"not in desired config",
				"in config but not found in org"
		);
		printSummary(result);
	}
```

`printSummary` gains, before the repository counts:

```java
		if (!result.orgs().isEmpty()) {
			System.out.printf("Orgs checked:   %d%n", result.orgs().size());
			System.out.printf("Orgs drifted:   %d%n", result.orgDriftCount());
		}
```

- [ ] **Step 8: Wire it into main**

In the organizations loop from Task 3, check the org before its repositories, and use the listing for both:

```java
		var orgChecker = new OrganizationChecker(client, fix, githubSecrets, state);
		var orgEntries = new ArrayList<CheckResult.Entry>();
		…
			Optional<List<RepositorySummaryResponse>> repos = client.listOrgRepos(login);
			if (repos.isEmpty()) {
				orgEntries.add(CheckResult.Entry.missing(login));
				entry.getValue().repositories
						.forEach(r -> repoEntries.add(CheckResult.Entry.missing(r.name)));
				continue;
			}
			orgEntries.add(
					orgChecker.check(login, entry.getValue(), repos.orElseThrow())
			);
			repoEntries.addAll(
					repoChecker.check(login, repos.orElseThrow(), entry.getValue().repositories)
			);
		…
		CheckResult result = new CheckResult(orgEntries, repoEntries);
```

- [ ] **Step 9: Write the checker test**

Only `/orgs/my-org` is stubbed, and the fixture manages only `org_settings`. A group whose request escaped its guard would hit an unstubbed path and fail the test, which is what makes this a guard test as well as a checker test.

```java
package io.github.arlol.githubcheck;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.state.DriftyState;
import io.github.arlol.githubcheck.testsupport.Desired;

@WireMockTest
class OrganizationCheckerTest {

	private OrganizationChecker checker;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		checker = new OrganizationChecker(
				new GitHubClient(wm.getHttpBaseUrl(), "test-token"),
				false,
				Map.of(),
				new DriftyState()
		);
	}

	private static Drifty.Organization onlySettings() {
		return Desired.organization()
				.withManaged(
						new Drifty.OrgManaged(
								Drifty.ManageMode.ONLY,
								List.of(Drifty.OrgGroupName.ORG_SETTINGS)
						)
				);
	}

	private static void stubOrg(String description) {
		stubFor(
				get(urlPathEqualTo("/orgs/my-org"))
						.willReturn(okJson("""
								{
								  "login": "my-org",
								  "description": %s,
								  "default_repository_permission": "read",
								  "default_repository_branch": "main",
								  "has_organization_projects": true,
								  "has_repository_projects": true,
								  "members_can_create_repositories": true,
								  "members_can_create_public_repositories": true,
								  "members_can_create_private_repositories": true,
								  "members_can_create_pages": true,
								  "members_can_create_public_pages": true,
								  "members_can_create_private_pages": true,
								  "members_can_delete_repositories": true,
								  "members_can_change_repo_visibility": true,
								  "members_can_invite_outside_collaborators": true,
								  "members_can_create_teams": true,
								  "members_can_view_dependency_insights": true
								}
								""".formatted(description)))
		);
	}

	@Test
	void matchingSettingsReportOk() {
		stubOrg("null");

		CheckResult.Entry entry = checker
				.check("my-org", onlySettings(), List.of());

		assertThat(entry.status()).isEqualTo(CheckResult.Status.OK);
		assertThat(entry.unmanaged()).containsExactlyInAnyOrder(
				"org_actions_permissions",
				"org_workflow_permissions",
				"org_action_secrets"
		);
	}

	@Test
	void driftedDescriptionIsReported() {
		stubOrg("\"stale\"");

		CheckResult.Entry entry = checker
				.check("my-org", onlySettings().withDescription("wanted"), List.of());

		assertThat(entry.status()).isEqualTo(CheckResult.Status.DRIFT);
		assertThat(entry.diffs()).singleElement()
				.asString()
				.startsWith("org_settings.description:");
		assertThat(entry.fixPreview()).containsExactly("org_settings");
	}

	@Test
	void unknownOrganizationIsMissing() {
		stubFor(
				get(urlPathEqualTo("/orgs/my-org"))
						.willReturn(aResponse().withStatus(404))
		);

		CheckResult.Entry entry = checker
				.check("my-org", onlySettings(), List.of());

		assertThat(entry.status()).isEqualTo(CheckResult.Status.MISSING);
	}

}
```

The generated `Drifty.OrgManaged` constructor takes `(mode, groups)` in declaration order; if codegen emits a different shape, build it with `Desired.organization().managed` and the generated `withX` methods instead.

- [ ] **Step 10: Extend the namespacing test**

In `DriftPathNamespacingTest`, add the org-side pair, mirroring the repository tests:

```java
	@Test
	void everyOrgGroupNameConstantHasAGroup() {
		List<Drifty.OrgGroupName> names = orgDriftGroups().stream()
				.map(DriftGroup::name)
				.toList();

		assertThat(names).doesNotHaveDuplicates()
				.containsExactlyInAnyOrder(Drifty.OrgGroupName.values());
	}

	@Test
	void everyOrgDriftItemPathIsNamespacedByItsGroup() {
		// same body as the repository test, over orgDriftGroups()
	}
```

`orgDriftGroups()` builds an `OrganizationState` that drifts on everything, the way `driftGroups()` does for repositories. Until Tasks 7–9 land, `everyOrgGroupNameConstantHasAGroup` fails for the three constants with no group yet — add those constants to `OrgGroupName` in this task but keep the test's expectation to the groups that exist, then tighten it in Task 9. Note that in the test with a comment naming Task 9.

- [ ] **Step 11: Run the tests**

Run: `./mvnw test -DskipNativeTests`
Expected: PASS.

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -F - <<'EOF'
Check and fix organization profile and member policies

Claude-Session: https://claude.ai/code/session_014wTAEAEa9wytEzdrqpvBsH
EOF
```

---

### Task 7: org_actions_permissions

**Files:**
- Modify: `config/drifty.pkl`
- Create: `src/main/java/io/github/arlol/githubcheck/drift/OrgActionsPermissionsDriftGroup.java`
- Modify: `src/main/java/io/github/arlol/githubcheck/PklTypes.java`
- Modify: `src/main/java/io/github/arlol/githubcheck/OrganizationChecker.java`
- Modify: `src/test/resources/desired-defaults.pkl`, `testsupport/Desired.java`
- Create: `src/test/java/io/github/arlol/githubcheck/drift/OrgActionsPermissionsDriftGroupTest.java`

**Interfaces:**
- Consumes: `ActualOrgActionsPermissions`, `client.updateOrgActionsPermissions`, `client.updateOrgSelectedActions`.
- Produces: `OrgActionsPermissionsDriftGroup(Drifty.ActionsPermissions desired, ActualOrgActionsPermissions actual, GitHubClient client, String org)`; `PklTypes.enabledRepositories(Drifty.ActionsEnabledRepositories)`, `PklTypes.allowedActions(Drifty.AllowedActions)`.

- [ ] **Step 1: Write the failing test**

```java
	@Test
	void detectsAllowedActionsDrift() {
		var group = new OrgActionsPermissionsDriftGroup(
				Desired.actionsPermissions()
						.withAllowedActions(Drifty.AllowedActions.LOCAL_ONLY),
				new ActualOrgActionsPermissions(
						ActionsEnabledRepositories.ALL,
						AllowedActions.ALL,
						false,
						null
				),
				null,
				"my-org"
		);

		assertThat(group.detect())
				.flatExtracting(DriftFix::items)
				.extracting(DriftItem::path)
				.containsExactly("org_actions_permissions.allowed_actions");
	}

	@Test
	void detectsPatternDriftWhenSelected() {
		var group = new OrgActionsPermissionsDriftGroup(
				Desired.actionsPermissions()
						.withAllowedActions(Drifty.AllowedActions.SELECTED)
						.withSelectedActions(
								Desired.selectedActions()
										.withPatternsAllowed(List.of("my-org/*"))
						),
				new ActualOrgActionsPermissions(
						ActionsEnabledRepositories.ALL,
						AllowedActions.SELECTED,
						false,
						new ActualSelectedActions(true, false, List.of())
				),
				null,
				"my-org"
		);

		assertThat(group.detect())
				.flatExtracting(DriftFix::items)
				.extracting(DriftItem::path)
				.containsExactly(
						"org_actions_permissions.selected_actions.patterns_allowed"
				);
	}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -DskipNativeTests -Dtest=OrgActionsPermissionsDriftGroupTest`
Expected: FAIL.

- [ ] **Step 3: Add the schema**

```pkl
typealias ActionsEnabledRepositories = "all" | "none" | "selected"
typealias AllowedActions = "all" | "local_only" | "selected"

class SelectedActions {
  githubOwnedAllowed: Boolean = true
  verifiedAllowed: Boolean = false
  patternsAllowed: Listing<String> = new {}
}

/// Organization Actions policy. Which repositories are selected under
/// enabledRepositories = "selected" is not managed: drifty writes the policy
/// value and GitHub keeps the existing selection.
class ActionsPermissions {
  enabledRepositories: ActionsEnabledRepositories = "all"
  allowedActions: AllowedActions = "all"
  shaPinningRequired: Boolean = false
  /// Required when allowedActions = "selected"
  selectedActions: SelectedActions?
}
```

On `Organization`: `actionsPermissions: ActionsPermissions = new {}`.

Add `actionsPermissions` and `selectedActions` entries to `desired-defaults.pkl` and `Desired`.

- [ ] **Step 4: Write the group**

Two `DriftFix` values, because they are two endpoints: one for the policy fields (`enabled_repositories`, `allowed_actions`, `sha_pinning_required`), one for the allow-list. The policy request always carries `enabled_repositories`, which the API requires even when only `allowed_actions` drifted.

```java
	@Override
	protected List<DriftFix> detectDrift() {
		var policyItems = combine(
				compare(
						"enabled_repositories",
						PklTypes.enabledRepositories(desired.enabledRepositories),
						actual.enabledRepositories()
				),
				compare(
						"allowed_actions",
						PklTypes.allowedActions(desired.allowedActions),
						actual.allowedActions()
				),
				compare(
						"sha_pinning_required",
						desired.shaPinningRequired,
						actual.shaPinningRequired()
				)
		);
		var fixes = new ArrayList<DriftFix>();
		fixes.add(new DriftFix(policyItems, () -> {
			client.updateOrgActionsPermissions(
					org,
					new OrgActionsPermissionsRequest(
							PklTypes.enabledRepositories(desired.enabledRepositories),
							PklTypes.allowedActions(desired.allowedActions),
							desired.shaPinningRequired
					)
			);
			return FixResult.success();
		}));
		if (desired.selectedActions != null) {
			fixes.add(selectedActionsFix());
		}
		return fixes;
	}
```

```java
	/**
	 * The allow-list is a second endpoint, so it is a second fix: a rejected
	 * policy write must not be reported as having failed the patterns too.
	 */
	private DriftFix selectedActionsFix() {
		var selected = desired.selectedActions;
		var current = actual.selectedActions();
		boolean githubOwned = current != null && current.githubOwnedAllowed();
		boolean verified = current != null && current.verifiedAllowed();
		List<String> patterns = current == null ? List.of()
				: current.patternsAllowed();

		var items = combine(
				compare(
						"selected_actions.github_owned_allowed",
						selected.githubOwnedAllowed,
						githubOwned
				),
				compare(
						"selected_actions.verified_allowed",
						selected.verifiedAllowed,
						verified
				),
				compare(
						"selected_actions.patterns_allowed",
						selected.patternsAllowed,
						patterns
				)
		);
		return new DriftFix(items, () -> {
			client.updateOrgSelectedActions(
					org,
					new SelectedActions(
							selected.githubOwnedAllowed,
							selected.verifiedAllowed,
							selected.patternsAllowed
					)
			);
			return FixResult.success();
		});
	}
```

- [ ] **Step 5: Register the group**

Add it to `OrganizationChecker.createDriftGroups`, after `OrgSettingsDriftGroup`.

- [ ] **Step 6: Run the tests**

Run: `./mvnw test -DskipNativeTests`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -F - <<'EOF'
Check and fix organization Actions permissions

Claude-Session: https://claude.ai/code/session_014wTAEAEa9wytEzdrqpvBsH
EOF
```

---

### Task 8: org_workflow_permissions

The org twin of `WorkflowPermissionsDriftGroup`, on `/orgs/{org}/actions/permissions/workflow`.

**Files:**
- Modify: `config/drifty.pkl`
- Create: `src/main/java/io/github/arlol/githubcheck/drift/OrgWorkflowPermissionsDriftGroup.java`
- Modify: `src/main/java/io/github/arlol/githubcheck/OrganizationChecker.java`
- Create: `src/test/java/io/github/arlol/githubcheck/drift/OrgWorkflowPermissionsDriftGroupTest.java`

**Interfaces:**
- Produces: `OrgWorkflowPermissionsDriftGroup(Drifty.WorkflowPermissions desiredPermissions, boolean desiredCanApprove, ActualWorkflowPermissions actual, GitHubClient client, String org)`.

- [ ] **Step 1: Write the failing test**

```java
	@Test
	void detectsDefaultPermissionsDrift() {
		var group = new OrgWorkflowPermissionsDriftGroup(
				Drifty.WorkflowPermissions.READ,
				true,
				new ActualWorkflowPermissions(
						WorkflowPermissions.DefaultWorkflowPermissions.WRITE,
						true
				),
				null,
				"my-org"
		);

		assertThat(group.detect())
				.flatExtracting(DriftFix::items)
				.extracting(DriftItem::path)
				.containsExactly(
						"org_workflow_permissions.default_workflow_permissions"
				);
	}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -DskipNativeTests -Dtest=OrgWorkflowPermissionsDriftGroupTest`
Expected: FAIL.

- [ ] **Step 3: Add the schema fields**

On `Organization`:

```pkl
  defaultWorkflowPermissions: WorkflowPermissions = "write"
  canApprovePullRequestReviews: Boolean = true
```

- [ ] **Step 4: Write the group**

Read `WorkflowPermissionsDriftGroup` and write the same comparison and single-`DriftFix` shape, calling `client.updateOrgWorkflowPermissions(org, new WorkflowPermissions(PklTypes.workflowPermissions(desiredPermissions), desiredCanApprove))`, with paths `default_workflow_permissions` and `can_approve_pull_request_reviews`.

- [ ] **Step 5: Register and run**

Add to `createDriftGroups`; run `./mvnw test -DskipNativeTests`.
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -F - <<'EOF'
Check and fix organization default workflow permissions

Claude-Session: https://claude.ai/code/session_014wTAEAEa9wytEzdrqpvBsH
EOF
```

---

### Task 9: org_action_secrets

**Files:**
- Modify: `config/drifty.pkl`
- Create: `src/main/java/io/github/arlol/githubcheck/drift/OrgActionSecretsDriftGroup.java`
- Modify: `src/main/java/io/github/arlol/githubcheck/state/DriftyState.java`
- Modify: `src/main/java/io/github/arlol/githubcheck/OrganizationChecker.java`
- Modify: `src/main/java/io/github/arlol/githubcheck/GitHubCheck.java`
- Create: `src/test/java/io/github/arlol/githubcheck/drift/OrgActionSecretsDriftGroupTest.java`
- Modify: `src/test/java/io/github/arlol/githubcheck/state/DriftyStateTest.java`, `state/StateStoreTest.java`, `GitHubCheckTest.java`, `DriftPathNamespacingTest.java`

**Interfaces:**
- Produces:
  - `OrgActionSecretsDriftGroup(Map<String, Drifty.OrgSecret> desired, List<ActualOrgSecret> actual, Map<String, Long> repositoryIds, Map<String, String> secretValues, DriftyState state, GitHubClient client, String org)`
  - `DriftyState.SecretRecord orgActionSecretRecord(String org, String name)` and `void recordOrgActionSecret(String org, String name, String updatedAt, String valueHash)`
  - `PklTypes.secretVisibility(Drifty.SecretVisibility)`

- [ ] **Step 1: Write the failing state test**

In `DriftyStateTest`:

```java
	@Test
	void orgAndRepoSecretsAreRecordedSeparately() {
		var state = new DriftyState();
		state.recordActionSecret("drifty", "PAT", "t1", "h1");
		state.recordOrgActionSecret("my-org", "PAT", "t2", "h2");

		assertThat(state.actionSecretRecord("drifty", "PAT").valueHash())
				.isEqualTo("h1");
		assertThat(state.orgActionSecretRecord("my-org", "PAT").valueHash())
				.isEqualTo("h2");
		assertThat(state.isEmpty()).isFalse();
	}
```

In `StateStoreTest`:

```java
	@Test
	void stateFileWithoutOrganizations_stillLoads(@TempDir Path dir)
			throws Exception {
		Path file = dir.resolve("drifty-state.json");
		Files.writeString(file, """
				{
				  "version": 1,
				  "salt": "abcd",
				  "repositories": {
				    "drifty": {
				      "action_secrets": {
				        "PAT": {"updated_at": "t", "value_hash": "h"}
				      }
				    }
				  }
				}
				""");

		DriftyState state = new StateStore().load(file);

		assertThat(state.actionSecretRecord("drifty", "PAT")).isNotNull();
		assertThat(state.orgActionSecretRecord("my-org", "PAT")).isNull();
	}

	@Test
	void saveWritesOnlyOrgRecords(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("drifty-state.json");
		var state = new DriftyState();
		state.recordOrgActionSecret("my-org", "PAT", "t", state.hash("v"));

		new StateStore().save(file, state);

		assertThat(Files.readString(file)).contains("\"organizations\"");
	}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -DskipNativeTests -Dtest='DriftyStateTest,StateStoreTest'`
Expected: FAIL — the org accessors do not exist.

- [ ] **Step 3: Extend `DriftyState`**

```java
	@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
	public static class OrgState {

		ConcurrentHashMap<String, SecretRecord> actionSecrets = new ConcurrentHashMap<>();

	}

	ConcurrentHashMap<String, OrgState> organizations = new ConcurrentHashMap<>();

	public SecretRecord orgActionSecretRecord(String org, String name) {
		OrgState orgState = organizations.get(org);
		return orgState == null ? null : orgState.actionSecrets.get(name);
	}

	public void recordOrgActionSecret(
			String org,
			String name,
			String updatedAt,
			String valueHash
	) {
		organizations.computeIfAbsent(org, key -> new OrgState()).actionSecrets
				.put(name, new SecretRecord(updatedAt, valueHash));
	}
```

`isEmpty()` becomes:

```java
	@JsonIgnore
	public boolean isEmpty() {
		return repositories.values().stream().allMatch(DriftyState::isEmpty)
				&& organizations.values()
						.stream()
						.allMatch(orgState -> orgState.actionSecrets.isEmpty());
	}
```

The version stays 1: an old file simply has no `organizations` key, and the mapper's `FAIL_ON_UNKNOWN_PROPERTIES` is already off for the other direction.

- [ ] **Step 4: Write the failing group test**

```java
	@Test
	void missingSecretIsDrift() {
		var group = new OrgActionSecretsDriftGroup(
				Map.of("PAT", Desired.orgSecret()),
				List.of(),
				Map.of(),
				Map.of("org-my-org-PAT", "value"),
				new DriftyState(),
				null,
				"my-org"
		);

		assertThat(group.detect())
				.flatExtracting(DriftFix::items)
				.extracting(DriftItem::path)
				.containsExactly("org_action_secrets.PAT");
	}

	@Test
	void visibilityDriftIsReported() {
		var state = new DriftyState();
		state.recordOrgActionSecret("my-org", "PAT", "t1", state.hash("value"));
		var group = new OrgActionSecretsDriftGroup(
				Map.of(
						"PAT",
						Desired.orgSecret()
								.withVisibility(Drifty.SecretVisibility.ALL)
				),
				List.of(
						new ActualOrgSecret(
								"PAT",
								"t1",
								SecretVisibility.PRIVATE,
								List.of()
						)
				),
				Map.of(),
				Map.of("org-my-org-PAT", "value"),
				state,
				null,
				"my-org"
		);

		assertThat(group.detect())
				.flatExtracting(DriftFix::items)
				.extracting(DriftItem::path)
				.containsExactly("org_action_secrets.PAT.visibility");
	}

	@Test
	void missingValueIsUnfixable() {
		var group = new OrgActionSecretsDriftGroup(
				Map.of("PAT", Desired.orgSecret()),
				List.of(),
				Map.of(),
				Map.of(),
				new DriftyState(),
				null,
				"my-org"
		);

		FixResult result = group.detect().getFirst().fix().execute();

		assertThat(result.unfixedItems()).singleElement()
				.satisfies(
						unfixed -> assertThat(unfixed.reason())
								.contains("org-my-org-PAT")
				);
	}
```

- [ ] **Step 5: Add the schema**

```pkl
typealias SecretVisibility = "all" | "private" | "selected"

/// An organization Actions secret. The name is the key in
/// Organization.actionsSecrets; the value comes from DRIFTY_GITHUB_SECRETS
/// under the key `org-<org>-<secret>`.
class OrgSecret {
  visibility: SecretVisibility = "private"
  /// Only when visibility = "selected"
  selectedRepositories: Listing<String> = new {}
}
```

On `Organization`: `actionsSecrets: Mapping<String, OrgSecret> = new {}`. Add `orgSecret` to `desired-defaults.pkl` and `Desired`.

- [ ] **Step 6: Write the group**

Model it on `ActionSecretsDriftGroup`: one `DriftFix` per desired secret, plus a `SectionExtra` fix per secret on GitHub that config does not declare, reporting `"drifty does not delete secrets it did not create"`.

Per secret, in order:

1. absent on GitHub → `DriftItem.SectionMissing(name)`
2. present, no state record → `DriftItem.SecretMissingBaseline(name)`
3. present, `updated_at` differs from the record → `DriftItem.SecretChanged(name, recorded, actual)`
4. present, timestamps agree, hash of the configured value differs → `DriftItem.SecretValueChanged(name)`
5. otherwise verified — and the visibility is still compared, as its own item at path `<name>.visibility`, with `<name>.selected_repositories` compared through the collection overload when either side is `SELECTED`

```java
	private DriftFix secretDriftFix(String name, Drifty.OrgSecret wanted) {
		ActualOrgSecret current = actual.get(name);
		var items = new ArrayList<DriftItem>();
		if (current == null) {
			items.add(new DriftItem.SectionMissing(name));
		} else {
			var record = state.orgActionSecretRecord(org, name);
			if (record == null) {
				items.add(new DriftItem.SecretMissingBaseline(name));
			} else if (!Objects
					.equals(record.updatedAt(), current.updatedAt())) {
				items.add(
						new DriftItem.SecretChanged(
								name,
								record.updatedAt(),
								current.updatedAt()
						)
				);
			} else {
				var value = secretValues.get(key(name));
				if (value != null
						&& !record.valueHash().equals(state.hash(value))) {
					items.add(new DriftItem.SecretValueChanged(name));
				}
			}
			items.addAll(
					compare(
							name + ".visibility",
							PklTypes.secretVisibility(wanted.visibility),
							current.visibility()
					)
			);
			if (wanted.visibility == Drifty.SecretVisibility.SELECTED
					|| current.visibility() == SecretVisibility.SELECTED) {
				items.addAll(
						compare(
								name + ".selected_repositories",
								wanted.selectedRepositories,
								current.selectedRepositories()
						)
				);
			}
		}
		if (items.isEmpty()) {
			return null;
		}
		return new DriftFix(items, () -> push(name, wanted, items));
	}

	private String key(String name) {
		return "org-" + org + "-" + name;
	}

	/**
	 * Pushes the value even when only the visibility drifted: the PUT requires
	 * {@code encrypted_value}, so there is no way to move a secret between
	 * visibilities without re-sending it.
	 */
	private FixResult push(
			String name,
			Drifty.OrgSecret wanted,
			List<DriftItem> items
	) {
		String value = secretValues.get(key(name));
		if (value == null) {
			return unfixAll(
					items,
					"no value for " + key(name) + " in DRIFTY_GITHUB_SECRETS"
			);
		}
		var ids = new ArrayList<Long>();
		for (String repository : wanted.selectedRepositories) {
			Long id = repositoryIds.get(repository);
			if (id == null) {
				return unfixAll(
						items,
						"no repository " + repository + " in " + org
				);
			}
			ids.add(id);
		}
		client.createOrUpdateOrgActionSecret(
				org,
				name,
				value,
				PklTypes.secretVisibility(wanted.visibility),
				ids
		);
		var updated = client.getOrgActionSecret(org, name);
		state.recordOrgActionSecret(
				org,
				name,
				updated.updatedAt(),
				state.hash(value)
		);
		return FixResult.success();
	}

	private static FixResult unfixAll(List<DriftItem> items, String reason) {
		return new FixResult(
				items.stream()
						.map(item -> new FixResult.Unfixed(item, reason))
						.toList()
		);
	}
```

- [ ] **Step 7: Fetch the secrets**

In `OrganizationChecker`, implement `orgSecrets`:

```java
	/**
	 * The repository names behind a {@code selected} secret cost one request
	 * each, so they are read only for the secrets that have them.
	 */
	private List<ActualOrgSecret> orgSecrets(String login) {
		return client.getOrgActionSecrets(login)
				.stream()
				.map(
						secret -> ActualTypes.orgSecret(
								secret,
								secret.visibility() == SecretVisibility.SELECTED
										? client.getOrgActionSecretRepositories(
												login,
												secret.name()
										)
												.stream()
												.map(RepositorySummaryResponse::name)
												.toList()
										: List.of()
						)
				)
				.toList();
	}
```

Register `OrgActionSecretsDriftGroup` in `createDriftGroups`, passing `repositoryIds`.

- [ ] **Step 8: Teach main the org secret keys**

In `GitHubCheck`, `collectMissingSecrets` takes the `DriftyConfig` and adds, for each organization:

```java
		for (var org : config.organizations().entrySet()) {
			for (String secretName : org.getValue().actionsSecrets.keySet()) {
				String key = "org-" + org.getKey() + "-" + secretName;
				if (!githubSecrets.containsKey(key)) {
					missingSecrets.add(key);
				}
			}
		}
```

Add a `GitHubCheckTest` case asserting that an org secret with no value is listed as `org-my-org-PAT`.

- [ ] **Step 9: Tighten the namespacing test**

Remove the Task 6 note from `DriftPathNamespacingTest` and let `everyOrgGroupNameConstantHasAGroup` assert against all four `OrgGroupName` values.

- [ ] **Step 10: Run the tests**

Run: `./mvnw test -DskipNativeTests`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -F - <<'EOF'
Check and fix organization Actions secrets

Claude-Session: https://claude.ai/code/session_014wTAEAEa9wytEzdrqpvBsH
EOF
```

---

### Task 10: Recordings, docs and native image

**Files:**
- Create: WireMock mappings under `src/test/resources/wiremock/mappings/`
- Modify: `src/test/java/io/github/arlol/githubcheck/client/GitHubClientPlaybackTest.java`, `GitHubClientRecordingTest.java`
- Modify: `SPEC.md`, `FEATURES.md`, `CLAUDE.md`, `README.md`
- Modify: `src/main/resources/META-INF/native-image/reachability-metadata.json`, `src/test/resources/META-INF/native-image/reachability-metadata.json`

- [ ] **Step 1: Record the org endpoints**

Follow `GitHubClientRecordingTest`'s existing procedure against the real organization, then add playback assertions to `GitHubClientPlaybackTest` for `getOrganization`, `getOrgActionsPermissions`, `getOrgWorkflowPermissions` and `getOrgActionSecrets`. Scrub any token or private repository name from the recorded files before committing.

- [ ] **Step 2: Update SPEC.md**

Add an `## Organizations` section after "Managed Settings" carrying both settings tables from the design spec, the group list, the `org-<org>-<secret>` key rule, and the two-section report example. Update "Configuration Model" and "Org/Account Targeting" for the nested shape: repositories live under `organizations` or `users`, and there is no `owner` field. The example config it points at is now `config/example.pkl`. Update the "State File" section with the `organizations` key. In "Future Considerations", drop org settings, keep org rulesets, and add code security configurations, custom properties, org webhooks, teams and members, runner groups, Actions variables, and the repository selection behind `enabled_repositories = "selected"`.

- [ ] **Step 3: Update FEATURES.md**

Add a numbered entry describing what shipped, in the style of the existing DONE entries.

- [ ] **Step 4: Update CLAUDE.md**

Add to "Adding or changing a managed setting":

- Repositories nest under `organizations` or `users` in `drifty.pkl`; the owner is the key, and `RepositoryState.ref()` carries it.
- Org groups name themselves in `OrgGroupName`, repository groups in `GroupName`, and `DriftGroup<N>` keeps the two scopes apart. `DriftPathNamespacingTest` fails a group whose constant is missing from either union.
- `OrganizationChecker.fetchState` sends `GET /orgs/{org}` even when `org_settings` is unmanaged, because it is how drifty learns the org exists; every other org request is guarded by its group.
- `OrgSettingsDriftGroup` sends only drifted fields and re-sends per field on a 422, for the same reason `RepoSettingsDriftGroup` does — `members_can_create_internal_repositories` on a non-Enterprise org is the new `allow_forking`.

- [ ] **Step 5: Regenerate reachability metadata**

```bash
./mvnw test -Dagent=true
./mvnw test-compile
./mvnw exec:java@reachability-metadata
./mvnw -DskipTests package
./mvnw clean test
```

- [ ] **Step 6: Full verify**

Run: `./mvnw verify`
Expected: PASS, including the native test image.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -F - <<'EOF'
Record organization endpoints and document organization support

Claude-Session: https://claude.ai/code/session_014wTAEAEa9wytEzdrqpvBsH
EOF
```

---

### Task 11: Verify the defaults against a real organization

The schema's defaults come from GitHub's documented values and its example response, which is illustrative. A setting that reports drift on an organization nobody has touched means the default is wrong, not that the org drifted.

**Files:**
- Modify: `config/drifty.pkl` (only if a default proves wrong)

- [ ] **Step 1: Write a config naming the real organization**

```pkl
amends "config/drifty.pkl"

organizations {
  ["<the org>"] {
    managed { mode = "only"; groups { "org_settings" } }
  }
}
```

- [ ] **Step 2: Run read-only**

```bash
DRIFTY_GITHUB_TOKEN=… ./mvnw exec:java -Dexec.args="--config <that file>"
```

Expected: an `[OK]` line, or `[DRIFT]` naming settings that were deliberately changed in that org.

- [ ] **Step 3: Correct any wrong default**

For each reported setting nobody has ever changed in that org, change the default in `config/drifty.pkl` to the value GitHub reports, and note the correction in the commit message. Re-run until the only drift is drift you recognise.

- [ ] **Step 4: Widen the run**

Repeat with `managed` removed, so all four groups run, and confirm no request 403s with the token you intend to use.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -F - <<'EOF'
Correct organization setting defaults against a live organization

Claude-Session: https://claude.ai/code/session_014wTAEAEa9wytEzdrqpvBsH
EOF
```

---

### Task 12: Nest the production config

The production config lives in its own repository and amends this schema from
`refs/heads/main`, so it breaks the moment this branch merges. This task is
mechanical and belongs in that repository, not this one.

**Files:**
- Modify: `/Users/arlookeeffe/Developer/github.com/arlol/drifty-arlol/drifty.pkl`

- [ ] **Step 1: Pin the amend before merging, or land both together**

Either change the `amends` URL to a commit SHA on this branch while the change
is in flight, or merge this branch and push Task 12 in the same sitting.
Leaving `refs/heads/main` unpinned across the merge means every run of the
production config fails on `Repository.owner` being unknown.

- [ ] **Step 2: Nest the repositories under the account**

Wrap the existing `repositories { … }` block in `users { ["ArloL"] { … } }`,
indent it one level, and delete every `owner = "ArloL"` line — including the one
on the shared repository template.

- [ ] **Step 3: Add the organization**

Add an `organizations` block for the org this whole change exists for, starting
with whatever its settings should be rather than what they are — the check run
is what reconciles the two.

- [ ] **Step 4: Verify read-only**

```bash
cd /Users/arlookeeffe/Developer/github.com/arlol/drifty-arlol
DRIFTY_GITHUB_TOKEN=… drifty --config drifty.pkl
```

Expected: the repository report matches what the same config produced before
the restructure, plus an Organizations section.

- [ ] **Step 5: Commit in that repository**

```bash
git add drifty.pkl
git commit -F - <<'EOF'
Nest repositories under their owner and add the organization

Claude-Session: https://claude.ai/code/session_014wTAEAEa9wytEzdrqpvBsH
EOF
```
