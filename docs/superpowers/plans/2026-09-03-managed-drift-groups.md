# Managed Drift Groups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let each repository in `drifty.pkl` declare which drift groups drifty manages, in one of two modes — everything except a named set, or only a named set.

**Architecture:** Group names become a Pkl typealias, which codegen turns into the `Drifty.GroupName` enum; `DriftGroup.name()` returns that enum instead of a `String`. A `Managed` value on each repository is read into a `ManagedGroups` predicate, which both `OrgChecker.createDriftGroups` (to drop groups) and `OrgChecker.fetchState` (to skip their HTTP requests) consult.

**Tech Stack:** Java 25, Maven, Pkl (`pkl-codegen-java`), JUnit 5, AssertJ, WireMock.

**Spec:** `docs/superpowers/specs/2026-09-03-managed-groups-design.md`

## Global Constraints

- Build and test with `./mvnw verify`. Iterate with `./mvnw test -DskipNativeTests`; run the full `./mvnw verify` once before the final push.
- Drift groups never see GitHub response types. `ActualStateBoundaryTest` fails a group or state field holding a client type other than `GitHubClient`, `RepoRef` or an enum.
- Desired-state test fixtures come from `testsupport.Desired`, which evaluates `src/test/resources/desired-defaults.pkl`. Do not add hand-written `*Args` builders.
- Pkl codegen emits enums that carry their source string and return it from `toString()` — `Drifty.Visibility.PUBLIC.toString()` is `"public"`. Report output must not change.
- Default behaviour must not change: a repository that sets no `managed` value is checked exactly as today, and `config/ArloL.pkl` needs no edit.
- Generated sources live in `target/generated-sources/pkl/java/io/github/arlol/githubcheck/pkl/Drifty.java`. Run `./mvnw generate-sources` after editing `config/drifty.pkl` to regenerate before compiling.

**Signature choice that keeps this change small:** `createDriftGroups(actual, desired)` derives its `ManagedGroups` from `desired.managed` internally rather than taking a parameter. There are 92 test call sites of `createDriftGroups`/`computeGroupDrifts`; adding a parameter would touch every one. `fetchState` has no `desired` argument and does gain a parameter, but only 5 call sites exist, all in `OrgCheckerFetchStateTest`.

---

### Task 1: Drop org-level rulesets from the fetch

Independent bug fix, unrelated to the mode feature. `listRulesets` requests `/rulesets?per_page=100`; GitHub's `includes_parents` defaults to true, so org-level rulesets come back in that list. `RulesetSourceType` is parsed into `RulesetSummaryResponse` and read nowhere, so `RulesetDriftGroup` reports each org ruleset as `extra (should not exist)` and `--fix` calls `DELETE /repos/{owner}/{repo}/rulesets/{id}` on a ruleset that endpoint cannot delete.

**Files:**
- Modify: `src/main/java/io/github/arlol/githubcheck/OrgChecker.java` (`fetchRulesets`, around line 384)
- Test: `src/test/java/io/github/arlol/githubcheck/OrgCheckerFetchStateTest.java`

**Interfaces:**
- Consumes: `RulesetSummaryResponse.sourceType()` returning `RulesetSourceType` (`REPOSITORY` or `ORGANIZATION`), already parsed.
- Produces: nothing new. `fetchRulesets` keeps its signature.

- [ ] **Step 1: Write the failing test**

Add to `OrgCheckerFetchStateTest`:

```java
	@Test
	void orgLevelRulesets_areNotFetchedOrReported() throws Exception {
		stubRepoDetails("");
		stubSecurityEndpoints();
		stubStandardEndpoints();
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/branches"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/rulesets"))
						.willReturn(okJson("""
								[
									{
										"id": 42,
										"name": "repo-rules",
										"source_type": "Repository"
									},
									{
										"id": 99,
										"name": "org-rules",
										"source_type": "Organization"
									}
								]
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/rulesets/42"))
						.willReturn(okJson("""
								{"id": 42, "name": "repo-rules", "rules": []}
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/pages"))
						.willReturn(aResponse().withStatus(404))
		);

		RepositoryState state = checker
				.fetchState(REF, summary(false, "public"));

		assertThat(state.rulesets()).singleElement()
				.satisfies(r -> assertThat(r.name()).isEqualTo("repo-rules"));
		verify(0, getRequestedFor(urlPathEqualTo("/repos/owner/repo/rulesets/99")));
	}
```

The file currently imports `aResponse`, `get`, `okJson`, `stubFor` and `urlPathEqualTo` from `WireMock`. Add two more:

```java
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -DskipNativeTests -Dtest=OrgCheckerFetchStateTest#orgLevelRulesets_areNotFetchedOrReported`

Expected: FAIL. Two rulesets come back, and WireMock reports an unmatched request for `/repos/owner/repo/rulesets/99` (there is no stub for it), or the verify fails because that detail request was made.

- [ ] **Step 3: Write minimal implementation**

In `OrgChecker.fetchRulesets`, skip organization-sourced entries:

```java
	private List<ActualRuleset> fetchRulesets(String org, String name) {
		var rulesets = new ArrayList<ActualRuleset>();
		for (var rs : client.listRulesets(org, name)) {
			if (rs.sourceType() == RulesetSourceType.ORGANIZATION) {
				// listRulesets hits /rulesets, whose includes_parents defaults
				// to true, so org rulesets arrive here. They are not the
				// repository's to reconcile: the repo endpoint cannot delete
				// one, so reporting it as extra produces a fix that always
				// fails.
				continue;
			}
			rulesets.add(
					ActualTypes.ruleset(client.getRuleset(org, name, rs.id()))
			);
		}
		return rulesets;
	}
```

Add the import:

```java
import io.github.arlol.githubcheck.client.RulesetSourceType;
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -DskipNativeTests -Dtest=OrgCheckerFetchStateTest`
Expected: PASS, including the pre-existing `publicRepo_fetchesEverything` (its stub omits `source_type`, which Jackson leaves null, so it is not `ORGANIZATION` and is kept).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/arlol/githubcheck/OrgChecker.java src/test/java/io/github/arlol/githubcheck/OrgCheckerFetchStateTest.java
git commit -m "Skip org-level rulesets when fetching repository rulesets"
```

---

### Task 2: Group names become a Pkl typealias and a Java enum

**Files:**
- Modify: `config/drifty.pkl` (add typealias near the other typealiases, around line 10)
- Modify: `src/main/java/io/github/arlol/githubcheck/drift/DriftGroup.java`
- Modify: all 23 `src/main/java/io/github/arlol/githubcheck/drift/*DriftGroup.java`
- Modify: `src/main/java/io/github/arlol/githubcheck/OrgChecker.java` (`checkOne` fix preview, around line 222)
- Test: `src/test/java/io/github/arlol/githubcheck/DriftPathNamespacingTest.java`

**Interfaces:**
- Produces: `Drifty.GroupName` enum with 23 constants; `DriftGroup.name()` returning `Drifty.GroupName`; `DriftGroup.namespaced(String)` unchanged in behaviour.

- [ ] **Step 1: Add the typealias to the schema**

In `config/drifty.pkl`, after the existing typealiases:

```pkl
/// Every drift group drifty can check. Used by Managed.groups; the union is
/// what makes a misspelled group name fail at config-eval time instead of
/// silently leaving a group unmanaged.
typealias GroupName =
  "action_secrets"
  | "advanced_security"
  | "archived"
  | "automated_security_fixes"
  | "branch_protection"
  | "code_scanning_default_setup"
  | "environment_config"
  | "environment_secrets"
  | "immutable_releases"
  | "pages"
  | "private_vulnerability_reporting"
  | "repo_settings"
  | "rulesets"
  | "secret_scanning"
  | "secret_scanning_ai_detection"
  | "secret_scanning_delegated_alert_dismissal"
  | "secret_scanning_delegated_bypass"
  | "secret_scanning_non_provider_patterns"
  | "secret_scanning_push_protection"
  | "secret_scanning_validity_checks"
  | "topics"
  | "vulnerability_alerts"
  | "workflow_permissions"
```

- [ ] **Step 2: Regenerate and confirm the enum**

Run: `./mvnw generate-sources`
Then: `grep -n "enum GroupName" -A 50 target/generated-sources/pkl/java/io/github/arlol/githubcheck/pkl/Drifty.java`
Expected: an enum with `ACTION_SECRETS("action_secrets")` … `WORKFLOW_PERMISSIONS("workflow_permissions")` and a `toString()` returning the string.

- [ ] **Step 3: Write the failing test**

Replace the group-name assertions in `DriftPathNamespacingTest` with one that pins the enum as the identity. Add:

```java
	@Test
	void everyGroupNameConstantHasAGroup() {
		List<Drifty.GroupName> names = driftGroups().stream()
				.map(DriftGroup::name)
				.toList();

		assertThat(names).doesNotHaveDuplicates()
				.containsExactlyInAnyOrder(Drifty.GroupName.values());
	}
```

`driftGroups()` is the existing private helper at `DriftPathNamespacingTest.java:118` that builds every group. The assertion runs both directions: no schema constant without a group, and no group outside the schema. That is what replaces a hand-maintained mirror list.

- [ ] **Step 4: Run test to verify it fails**

Run: `./mvnw test -DskipNativeTests -Dtest=DriftPathNamespacingTest`
Expected: FAIL to compile — `DriftGroup::name` returns `String`, not `Drifty.GroupName`.

- [ ] **Step 5: Change the abstract signature and namespacing**

In `DriftGroup.java`:

```java
	public abstract Drifty.GroupName name();
```

and change the private namespacing helper to render the enum:

```java
	private String namespaced(String path) {
		return path == null || path.isEmpty() ? name().toString()
				: name() + "." + path;
	}
```

Add the import:

```java
import io.github.arlol.githubcheck.pkl.Drifty;
```

`name() + "." + path` calls `toString()` implicitly, so paths are byte-for-byte what they are today.

- [ ] **Step 6: Change all 23 groups**

In each `*DriftGroup.java`, change the override. For example in `ActionSecretsDriftGroup.java`:

```java
	@Override
	public Drifty.GroupName name() {
		return Drifty.GroupName.ACTION_SECRETS;
	}
```

The mapping is the constant whose `toString()` equals the string the group returns today — `"secret_scanning_push_protection"` becomes `Drifty.GroupName.SECRET_SCANNING_PUSH_PROTECTION`, and so on for all 23. Every group already imports `Drifty` except the ones that do not use desired config; add the import where the compiler asks for it.

- [ ] **Step 7: Fix the fix-preview call site**

In `OrgChecker.checkOne`, `fixPreview` is a `List<String>`. Change the mapping to render the enum:

```java
			List<String> fixPreview = groupDrifts.keySet()
					.stream()
					.map(group -> group.name().toString())
					.toList();
```

- [ ] **Step 8: Run the full test suite**

Run: `./mvnw test -DskipNativeTests`
Expected: PASS. No report text changes, so no assertion on drift paths or `Would fix:` output should need editing. If one does, the enum constant is mismatched to the old string — fix the constant, not the assertion.

- [ ] **Step 9: Commit**

```bash
git add config/drifty.pkl src/main/java/io/github/arlol/githubcheck/drift src/main/java/io/github/arlol/githubcheck/OrgChecker.java src/test/java/io/github/arlol/githubcheck/DriftPathNamespacingTest.java
git commit -m "Make the Pkl schema the source of truth for drift group names"
```

---

### Task 3: The Managed schema value and the ManagedGroups predicate

**Files:**
- Modify: `config/drifty.pkl`
- Create: `src/main/java/io/github/arlol/githubcheck/drift/ManagedGroups.java`
- Create: `src/test/java/io/github/arlol/githubcheck/drift/ManagedGroupsTest.java`

**Interfaces:**
- Consumes: `Drifty.GroupName` (Task 2).
- Produces: `ManagedGroups.of(Drifty.Managed)`, `ManagedGroups.all()`, `boolean manages(Drifty.GroupName)`, `List<Drifty.GroupName> unmanaged()`.

- [ ] **Step 1: Add the schema types**

In `config/drifty.pkl`, add the mode typealias next to `GroupName`:

```pkl
typealias ManageMode = "all_except" | "only"

/// Which drift groups drifty manages for a repository. The default manages
/// everything: mode "all_except" with nothing excluded.
class Managed {
  mode: ManageMode = "all_except"
  groups: Listing<GroupName> = new {}
}
```

and the field on `Repository`, after `name`:

```pkl
  managed: Managed = new {}
```

- [ ] **Step 2: Regenerate**

Run: `./mvnw generate-sources`
Then: `grep -n "class Managed" -A 20 target/generated-sources/pkl/java/io/github/arlol/githubcheck/pkl/Drifty.java`
Expected: a `Managed` class with public final `mode` (`ManageMode`) and `groups` (`List<GroupName>`) fields.

- [ ] **Step 3: Write the failing test**

Create `src/test/java/io/github/arlol/githubcheck/drift/ManagedGroupsTest.java`:

```java
package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.pkl.Drifty;

class ManagedGroupsTest {

	@Test
	void allExcept_managesEverythingNotNamed() {
		var managed = ManagedGroups.of(
				new Drifty.Managed(
						Drifty.ManageMode.ALL_EXCEPT,
						List.of(Drifty.GroupName.ACTION_SECRETS)
				)
		);

		assertThat(managed.manages(Drifty.GroupName.ACTION_SECRETS)).isFalse();
		assertThat(managed.manages(Drifty.GroupName.REPO_SETTINGS)).isTrue();
		assertThat(managed.unmanaged())
				.containsExactly(Drifty.GroupName.ACTION_SECRETS);
	}

	@Test
	void only_managesNothingElse() {
		var managed = ManagedGroups.of(
				new Drifty.Managed(
						Drifty.ManageMode.ONLY,
						List.of(Drifty.GroupName.REPO_SETTINGS)
				)
		);

		assertThat(managed.manages(Drifty.GroupName.REPO_SETTINGS)).isTrue();
		assertThat(managed.manages(Drifty.GroupName.ACTION_SECRETS)).isFalse();
		assertThat(managed.unmanaged())
				.hasSize(Drifty.GroupName.values().length - 1)
				.doesNotContain(Drifty.GroupName.REPO_SETTINGS);
	}

	@Test
	void emptyAllExcept_managesEverything() {
		var managed = ManagedGroups
				.of(new Drifty.Managed(Drifty.ManageMode.ALL_EXCEPT, List.of()));

		assertThat(managed.unmanaged()).isEmpty();
		for (Drifty.GroupName group : Drifty.GroupName.values()) {
			assertThat(managed.manages(group)).isTrue();
		}
	}

	@Test
	void all_managesEverything() {
		assertThat(ManagedGroups.all().unmanaged()).isEmpty();
	}

}
```

The generated shapes were confirmed by running codegen against a prototype schema:

```java
public static final class Managed {
  public final ManageMode mode;
  public final List<GroupName> groups;
  public Managed(ManageMode mode, List<GroupName> groups) { ... }
}
public enum ManageMode { ALL_EXCEPT("all_except"), ONLY("only") }
public enum GroupName { ACTION_SECRETS("action_secrets"), ... }
```

`Drifty.Repository` also gains `withManaged(Managed)`, which Task 4 uses.

- [ ] **Step 4: Run test to verify it fails**

Run: `./mvnw test -DskipNativeTests -Dtest=ManagedGroupsTest`
Expected: FAIL to compile — `ManagedGroups` does not exist.

- [ ] **Step 5: Write the implementation**

Create `src/main/java/io/github/arlol/githubcheck/drift/ManagedGroups.java`:

```java
package io.github.arlol.githubcheck.drift;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Which drift groups drifty manages for one repository.
 * <p>
 * Consulted twice per repository, and both are load-bearing: once to decide
 * which groups to build, and once in {@code OrgChecker.fetchState} to decide
 * which requests to send. Skipping only the comparison would still send the
 * request, and a repository in an org someone else administers is exactly where
 * those requests return 403.
 */
public final class ManagedGroups {

	private final Set<Drifty.GroupName> managed;

	private ManagedGroups(Set<Drifty.GroupName> managed) {
		this.managed = managed;
	}

	public static ManagedGroups of(Drifty.Managed managed) {
		Set<Drifty.GroupName> named = managed.groups.isEmpty()
				? EnumSet.noneOf(Drifty.GroupName.class)
				: EnumSet.copyOf(managed.groups);
		return new ManagedGroups(switch (managed.mode) {
		case ONLY -> named;
		case ALL_EXCEPT -> {
			var rest = EnumSet.allOf(Drifty.GroupName.class);
			rest.removeAll(named);
			yield rest;
		}
		});
	}

	/** Every group, which is what a repository that declares nothing gets. */
	public static ManagedGroups all() {
		return new ManagedGroups(EnumSet.allOf(Drifty.GroupName.class));
	}

	public boolean manages(Drifty.GroupName group) {
		return managed.contains(group);
	}

	/** The groups this repository leaves alone, for the report. */
	public List<Drifty.GroupName> unmanaged() {
		var rest = EnumSet.allOf(Drifty.GroupName.class);
		rest.removeAll(managed);
		return List.copyOf(rest);
	}

}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw test -DskipNativeTests -Dtest=ManagedGroupsTest`
Expected: PASS

- [ ] **Step 7: Add a schema-rejection test**

`PklConfigLoader.load(Path)` is static, and `PklConfigLoaderTest` currently has one test loading `config/ArloL.pkl`. Add:

```java
	@Test
	void unknownGroupName_failsToEvaluate(@TempDir Path tempDir)
			throws IOException {
		Path schema = Path.of("config/drifty.pkl").toAbsolutePath();
		Path config = tempDir.resolve("drifty.pkl");
		Files.writeString(config, """
				amends "%s"

				repositories {
				  new {
				    owner = "owner"
				    name = "repo"
				    managed { mode = "only"; groups { "not_a_group" } }
				  }
				}
				""".formatted(schema));

		assertThatThrownBy(() -> PklConfigLoader.load(config))
				.hasMessageContaining("not_a_group");
	}
```

New imports for that file:

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;

import org.junit.jupiter.api.io.TempDir;
```

Also add a valid-value test in the same file, so the happy path is pinned too:

```java
	@Test
	void managedDefaultsToEverything() throws IOException {
		List<Drifty.Repository> repos = PklConfigLoader
				.load(Path.of("config/ArloL.pkl").toAbsolutePath());

		assertThat(repos).allSatisfy(
				repo -> assertThat(repo.managed.mode)
						.isEqualTo(Drifty.ManageMode.ALL_EXCEPT)
		);
		assertThat(repos)
				.allSatisfy(repo -> assertThat(repo.managed.groups).isEmpty());
	}
```

- [ ] **Step 8: Run it**

Run: `./mvnw test -DskipNativeTests -Dtest=PklConfigLoaderTest`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add config/drifty.pkl src/main/java/io/github/arlol/githubcheck/drift/ManagedGroups.java src/test/java/io/github/arlol/githubcheck/drift/ManagedGroupsTest.java src/test/java/io/github/arlol/githubcheck/PklConfigLoaderTest.java
git commit -m "Add per-repository managed drift group configuration"
```

---

### Task 4: Drop unmanaged groups in createDriftGroups

**Files:**
- Modify: `src/main/java/io/github/arlol/githubcheck/OrgChecker.java` (`createDriftGroups`, around lines 433-659)
- Test: `src/test/java/io/github/arlol/githubcheck/OrgCheckerDiffTest.java`

**Interfaces:**
- Consumes: `ManagedGroups.of(Drifty.Managed)` and `manages(Drifty.GroupName)` (Task 3); `DriftGroup.name()` (Task 2).
- Produces: no signature change. `createDriftGroups(RepositoryState, Drifty.Repository)` keeps both parameters and derives the managed set from `desired.managed`.

- [ ] **Step 1: Write the failing test**

Add to `OrgCheckerDiffTest`:

`OrgCheckerDiffTest` has a private `StateBuilder` with an `actionSecretNames(String...)` method and a `defaultDesired()` helper. A secret on the repo that the config does not declare makes `action_secrets` drift, so it is the group to exclude:

```java
	@Test
	void unmanagedGroup_producesNoDrift() {
		RepositoryState actual = new StateBuilder()
				.actionSecretNames("SOMEONE_ELSES_TOKEN")
				.build();

		assertThat(computeGroupDrifts(actual, defaultDesired()).keySet())
				.extracting(DriftGroup::name)
				.contains(Drifty.GroupName.ACTION_SECRETS);

		var desired = defaultDesired().withManaged(
				new Drifty.Managed(
						Drifty.ManageMode.ALL_EXCEPT,
						List.of(Drifty.GroupName.ACTION_SECRETS)
				)
		);

		assertThat(computeGroupDrifts(actual, desired).keySet())
				.extracting(DriftGroup::name)
				.doesNotContain(Drifty.GroupName.ACTION_SECRETS);
	}

	@Test
	void onlyMode_buildsJustTheNamedGroups() {
		RepositoryState actual = new StateBuilder().build();
		var desired = defaultDesired().withManaged(
				new Drifty.Managed(
						Drifty.ManageMode.ONLY,
						List.of(Drifty.GroupName.REPO_SETTINGS)
				)
		);

		assertThat(checker.createDriftGroups(actual, desired))
				.extracting(DriftGroup::name)
				.containsExactly(Drifty.GroupName.REPO_SETTINGS);
	}

	@Test
	void archivedShortCircuit_respectsTheMode() {
		RepositoryState actual = new StateBuilder()
				.detailsOverride("""
						{"archived": true}
						""")
				.build();
		var desired = defaultDesired().withArchived(true)
				.withManaged(
						new Drifty.Managed(
								Drifty.ManageMode.ALL_EXCEPT,
								List.of(Drifty.GroupName.ARCHIVED)
						)
				);

		assertThat(checker.createDriftGroups(actual, desired)).isEmpty();
	}
```

The first test asserts both directions in one place: `action_secrets` drifts with the default `managed`, and does not once excluded. Without the first half, a typo in the exclusion would pass for the wrong reason.

The third test pins the spec's stated consequence: an archived repository whose `archived` group is unmanaged produces no groups at all, because the short-circuit return passes through the same filter.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -DskipNativeTests -Dtest=OrgCheckerDiffTest#unmanagedGroup_producesNoDrift+onlyMode_buildsJustTheNamedGroups`
Expected: FAIL — `action_secrets` is present, and `createDriftGroups` returns all groups.

- [ ] **Step 3: Filter in one place**

In `OrgChecker.createDriftGroups`, derive the managed set at the top:

```java
		ManagedGroups managed = ManagedGroups.of(desired.managed);
```

Apply it to the archived short-circuit return:

```java
		if (desired.archived) {
			return onlyManaged(
					List.of(
							new ArchivedDriftGroup(
									true,
									actual.repository().archived(),
									client,
									ref
							)
					),
					managed
			);
		}
```

and to the final return, replacing `return groups;`:

```java
		return onlyManaged(groups, managed);
	}

	/**
	 * Drops the groups this repository does not manage.
	 * <p>
	 * One filter over the finished list, rather than a check at each of the
	 * two dozen {@code groups.add} calls: a group added later is filtered
	 * without its author having to know this feature exists.
	 */
	private static List<DriftGroup> onlyManaged(
			List<DriftGroup> groups,
			ManagedGroups managed
	) {
		return groups.stream().filter(g -> managed.manages(g.name())).toList();
	}
```

Add the import:

```java
import io.github.arlol.githubcheck.drift.ManagedGroups;
```

- [ ] **Step 4: Run the tests**

Run: `./mvnw test -DskipNativeTests -Dtest=OrgCheckerDiffTest`
Expected: PASS, including the 90 existing tests — they build `Desired.repository(...)` with the schema default `managed`, which manages everything.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/arlol/githubcheck/OrgChecker.java src/test/java/io/github/arlol/githubcheck/OrgCheckerDiffTest.java
git commit -m "Build only the drift groups a repository manages"
```

---

### Task 5: Skip the requests unmanaged groups would need

**Files:**
- Modify: `src/main/java/io/github/arlol/githubcheck/OrgChecker.java` (`checkOne` around line 193, `fetchState` around line 268, `fetchSecurityFlags`, `fetchBranchProtections`, `fetchRulesets`)
- Modify: `src/main/java/io/github/arlol/githubcheck/RepositoryState.java` (Javadoc for the now-nullable `workflowPermissions`)
- Test: `src/test/java/io/github/arlol/githubcheck/OrgCheckerFetchStateTest.java`

**Interfaces:**
- Consumes: `ManagedGroups` (Task 3).
- Produces: `fetchState(RepoRef, RepositorySummaryResponse, ManagedGroups)` — a third parameter. The 5 existing call sites are all in `OrgCheckerFetchStateTest` and pass `ManagedGroups.all()`.

- [ ] **Step 1: Write the failing test**

Add to `OrgCheckerFetchStateTest`. The point of the test is that a 403 on an unmanaged group's endpoint does not reach the caller, because the request is never sent:

```java
	@Test
	void unmanagedGroups_endpointsAreNeverRequested() throws Exception {
		stubRepoDetails("");
		stubSecurityEndpoints();
		stubStandardEndpoints();
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/actions/secrets"))
						.willReturn(aResponse().withStatus(403))
		);
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/rulesets"))
						.willReturn(aResponse().withStatus(403))
		);
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/branches"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/pages"))
						.willReturn(aResponse().withStatus(404))
		);

		ManagedGroups managed = ManagedGroups.of(
				new Drifty.Managed(
						Drifty.ManageMode.ALL_EXCEPT,
						List.of(
								Drifty.GroupName.ACTION_SECRETS,
								Drifty.GroupName.RULESETS
						)
				)
		);

		RepositoryState state = checker
				.fetchState(REF, summary(false, "public"), managed);

		assertThat(state.actionSecrets()).isEmpty();
		assertThat(state.rulesets()).isEmpty();
		verify(
				0,
				getRequestedFor(
						urlPathEqualTo("/repos/owner/repo/actions/secrets")
				)
		);
		verify(0, getRequestedFor(urlPathEqualTo("/repos/owner/repo/rulesets")));
	}
```

`stubStandardEndpoints()` already stubs the action-secrets endpoint with a success response; the explicit 403 stub above overrides it, so the test fails loudly if the request is sent.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -DskipNativeTests -Dtest=OrgCheckerFetchStateTest#unmanagedGroups_endpointsAreNeverRequested`
Expected: FAIL to compile — `fetchState` takes two arguments.

- [ ] **Step 3: Thread ManagedGroups into fetchState**

Change the signature and guard each fetch. The guards follow the spec's table:

```java
	RepositoryState fetchState(
			RepoRef ref,
			RepositorySummaryResponse summary,
			ManagedGroups managed
	) throws IOException, InterruptedException {
		String org = ref.owner();
		String name = ref.name();
		boolean archived = summary.archived();

		var details = client.getRepo(org, name);

		SecurityFlags security = archived ? SecurityFlags.NONE
				: fetchSecurityFlags(org, name, managed);

		Map<String, ActualBranchProtection> branchProtections = managed
				.manages(Drifty.GroupName.BRANCH_PROTECTION)
						? fetchBranchProtections(summary, org, name, archived)
						: Map.of();

		List<ActualSecret> secrets = managed
				.manages(Drifty.GroupName.ACTION_SECRETS)
						? secrets(client.getActionSecrets(org, name))
						: List.of();

		Map<String, ActualEnvironment> environments = new LinkedHashMap<>();
		Map<String, List<ActualSecret>> envSecrets = new LinkedHashMap<>();
		boolean wantEnvConfig = managed
				.manages(Drifty.GroupName.ENVIRONMENT_CONFIG);
		boolean wantEnvSecrets = managed
				.manages(Drifty.GroupName.ENVIRONMENT_SECRETS);
		if (wantEnvConfig || wantEnvSecrets) {
			for (EnvironmentDetailsResponse env : client
					.getEnvironments(org, name)) {
				environments.put(env.name(), ActualTypes.environment(env));
				if (wantEnvSecrets) {
					envSecrets.put(
							env.name(),
							secrets(
									client.getEnvironmentSecrets(
											org,
											name,
											env.name()
									)
							)
					);
				}
			}
		}

		var workflowPermissions = managed
				.manages(Drifty.GroupName.WORKFLOW_PERMISSIONS)
						? ActualTypes.workflowPermissions(
								client.getWorkflowPermissions(org, name)
						)
						: null;

		List<ActualRuleset> rulesets = archived
				|| !managed.manages(Drifty.GroupName.RULESETS) ? List.of()
						: fetchRulesets(org, name);

		var pages = archived || !managed.manages(Drifty.GroupName.PAGES)
				? Optional.<PagesResponse>empty()
				: client.getPages(org, name);

		// ... the return is unchanged
	}
```

The environment listing is shared by two groups: `getEnvironments` runs when either wants it, and the per-environment secret call only when `environment_secrets` is managed.

- [ ] **Step 4: Guard the security flags**

```java
	private SecurityFlags fetchSecurityFlags(
			String org,
			String name,
			ManagedGroups managed
	) {
		boolean vulnAlerts = managed
				.manages(Drifty.GroupName.VULNERABILITY_ALERTS)
				&& client.getVulnerabilityAlerts(org, name);
		boolean automatedSecurityFixes = managed
				.manages(Drifty.GroupName.AUTOMATED_SECURITY_FIXES)
				&& client.getAutomatedSecurityFixes(org, name);
		boolean immutableReleases = false;
		if (managed.manages(Drifty.GroupName.IMMUTABLE_RELEASES)) {
			var response = client.getImmutableReleases(org, name);
			immutableReleases = response.isPresent()
					&& response.orElseThrow().enabled();
		}
		boolean privateVulnerabilityReporting = managed
				.manages(Drifty.GroupName.PRIVATE_VULNERABILITY_REPORTING)
				&& client.getPrivateVulnerabilityReporting(org, name);
		boolean codeScanningDefaultSetup = managed
				.manages(Drifty.GroupName.CODE_SCANNING_DEFAULT_SETUP)
				&& client.getCodeScanningDefaultSetup(org, name);
		return new SecurityFlags(
				vulnAlerts,
				automatedSecurityFixes,
				immutableReleases,
				privateVulnerabilityReporting,
				codeScanningDefaultSetup
		);
	}
```

Java's `&&` short-circuits, so an unmanaged flag sends no request.

- [ ] **Step 5: Update the caller and the Javadoc**

In `checkOne`:

```java
			RepositoryState state = fetchState(
					ref,
					summary,
					ManagedGroups.of(desired.managed)
			);
```

In `RepositoryState`, extend the class Javadoc to say what null now means:

```java
 * {@code workflowPermissions} is null when the repository does not manage the
 * {@code workflow_permissions} group: the response is never fetched, and the
 * group that would read it is not built.
```

- [ ] **Step 6: Update the other four fetchState call sites**

In `OrgCheckerFetchStateTest`, the existing tests pass two arguments. Add the third:

```java
		RepositoryState state = checker
				.fetchState(REF, summary(false, "public"), ManagedGroups.all());
```

Run: `grep -n "fetchState(REF" src/test/java/io/github/arlol/githubcheck/OrgCheckerFetchStateTest.java` to find all of them.

- [ ] **Step 7: Run the tests**

Run: `./mvnw test -DskipNativeTests`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/github/arlol/githubcheck/OrgChecker.java src/main/java/io/github/arlol/githubcheck/RepositoryState.java src/test/java/io/github/arlol/githubcheck/OrgCheckerFetchStateTest.java
git commit -m "Skip the GitHub requests unmanaged drift groups would need"
```

---

### Task 6: Report which groups are unmanaged

**Files:**
- Modify: `src/main/java/io/github/arlol/githubcheck/CheckResult.java`
- Modify: `src/main/java/io/github/arlol/githubcheck/OrgChecker.java` (`checkOne`, `printReport` around line 781)
- Test: `src/test/java/io/github/arlol/githubcheck/CheckResultTest.java`

**Interfaces:**
- Consumes: `ManagedGroups.unmanaged()` (Task 3).
- Produces: `RepoCheckResult.unmanaged()` returning `List<String>`; static factories gain an overload rather than changing their existing signatures.

- [ ] **Step 1: Write the failing test**

Add to `CheckResultTest`:

```java
	@Test
	void okCarriesUnmanagedGroups() {
		var result = CheckResult.RepoCheckResult
				.ok("repo", List.of("action_secrets", "rulesets"));

		assertThat(result.unmanaged())
				.containsExactly("action_secrets", "rulesets");
	}

	@Test
	void unmanagedDefaultsToEmpty() {
		assertThat(CheckResult.RepoCheckResult.ok("repo").unmanaged()).isEmpty();
	}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -DskipNativeTests -Dtest=CheckResultTest`
Expected: FAIL to compile — no `unmanaged` component and no two-argument `ok`.

- [ ] **Step 3: Add the component**

In `CheckResult.RepoCheckResult`, add `List<String> unmanaged` as the last component, copy it defensively in the compact constructor alongside the others, and pass `List.of()` from every existing factory. Add the overloads that carry it:

```java
		public static RepoCheckResult ok(String name) {
			return ok(name, List.of());
		}

		public static RepoCheckResult ok(String name, List<String> unmanaged) {
			return new RepoCheckResult(
					name,
					Status.OK,
					List.of(),
					List.of(),
					List.of(),
					null,
					unmanaged
			);
		}
```

Do the same for `drift(...)`: keep the existing two- and three-argument forms delegating with `List.of()`, and add a four-argument form taking `unmanaged` last. `error`, `unknown` and `missing` pass `List.of()` — a repo that errored or is not in config has no managed set to report.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -DskipNativeTests -Dtest=CheckResultTest`
Expected: PASS

- [ ] **Step 5: Populate it from checkOne**

In `checkOne`, compute the managed set once and reuse it for both the fetch and the result:

```java
		ManagedGroups managed = ManagedGroups.of(desired.managed);
		List<String> unmanaged = managed.unmanaged()
				.stream()
				.map(Drifty.GroupName::toString)
				.toList();
```

Pass `unmanaged` into the `ok(...)` and `drift(...)` calls, and replace the `fetchState` argument built in Task 5 with this `managed`.

The `fixed(...)` path keeps `List.of()`: a `--fix` run prints FIXED/FAILED lines, and the unmanaged list would say nothing about what was applied.

- [ ] **Step 6: Print it**

In `printReport`, add the line to both the OK and DRIFT branches:

```java
			case OK -> {
				System.out.printf("[OK]      %s%n", r.name());
				printUnmanaged(r);
				printFixReports(r);
			}
```

and after the `Would fix:` block in the DRIFT branch, then:

```java
	private static void printUnmanaged(CheckResult.RepoCheckResult r) {
		if (!r.unmanaged().isEmpty()) {
			System.out.printf(
					"  Unmanaged: %s%n",
					String.join(", ", r.unmanaged())
			);
		}
	}
```

- [ ] **Step 7: Test the printed line**

`OrgCheckerCheckTest` has a `capturePrintReport(OrgChecker, CheckResult)` helper that swaps `System.out`. Add:

```java
	@Test
	void printReportNamesUnmanagedGroups() {
		var result = new CheckResult(
				List.of(
						CheckResult.RepoCheckResult
								.ok("repo", List.of("action_secrets"))
				)
		);

		String output = capturePrintReport(
				new OrgChecker((GitHubClient) null, false),
				result
		);

		assertThat(output).contains("Unmanaged: action_secrets");
	}
```

Run: `./mvnw test -DskipNativeTests -Dtest=OrgCheckerCheckTest#printReportNamesUnmanagedGroups`
Expected: PASS

- [ ] **Step 8: Run the full suite**

Run: `./mvnw test -DskipNativeTests`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add src/main/java/io/github/arlol/githubcheck/CheckResult.java src/main/java/io/github/arlol/githubcheck/OrgChecker.java src/test/java/io/github/arlol/githubcheck/CheckResultTest.java src/test/java/io/github/arlol/githubcheck/OrgCheckerCheckTest.java
git commit -m "Report which drift groups a repository leaves unmanaged"
```

---

### Task 7: Documentation and the native build

**Files:**
- Modify: `SPEC.md`
- Modify: `FEATURES.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: everything above. Produces no code.

- [ ] **Step 1: Document the feature in SPEC.md**

Add a `## Partial Management` section after `## Archived Repos`, covering: the `managed` field and its two modes; that the default manages everything; that unmanaged groups are neither fetched nor compared; and that the report prints an `Unmanaged:` line naming groups but not their values, because they were never fetched. Include the two config examples from the design doc.

In the `### Repository Rulesets` section, add that org-level rulesets are excluded: they arrive through `includes_parents` but the repository endpoint cannot delete one.

- [ ] **Step 2: Record it in FEATURES.md**

Add a numbered, struck-through DONE entry in the established style, naming `ManagedGroups`, the `GroupName` typealias, the `createDriftGroups` filter and the `fetchState` guards.

- [ ] **Step 3: Add the CLAUDE.md rule**

Under "Adding or changing a managed setting", add a bullet:

```markdown
- **A new drift group needs a `GroupName` constant.** Group names live in the
  `GroupName` typealias in `config/drifty.pkl`, and `DriftGroup.name()` returns
  the generated enum. `DriftPathNamespacingTest` fails a group whose constant is
  missing, and a `Managed.groups` entry naming a group that does not exist fails
  at config eval. If the group sends its own requests, guard them in
  `OrgChecker.fetchState` too — filtering the group alone still sends them, and
  a repository in someone else's org is where those return 403.
```

- [ ] **Step 4: Run the full build including the native image**

Run: `./mvnw verify`
Expected: PASS. The new Pkl types are project records under `io.github.arlol.*`, which the reachability-metadata allowlist already covers, so no metadata regeneration is expected. If the native test image fails on a missing registration, regenerate per the CLAUDE.md procedure rather than hand-editing the JSON.

- [ ] **Step 5: Commit and push**

```bash
git add SPEC.md FEATURES.md CLAUDE.md
git commit -m "Document partial management of drift groups"
git push -u origin claude/drifty-secret-values-orgs-b5yqk8
```

---

## Verification

After Task 7, confirm against the spec:

- A repo with no `managed` value produces the same report as before the change (the 90 existing `OrgCheckerDiffTest` cases cover this).
- `mode = "only"` with `{ "repo_settings" }` builds exactly one group.
- `mode = "all_except"` with `{ "action_secrets" }` sends no request to `/actions/secrets`.
- A misspelled group name fails at config eval, naming the bad value.
- `fetchRulesets` drops an `Organization`-sourced ruleset and never fetches its detail.
