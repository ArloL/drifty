package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.FetchFailures;
import io.github.arlol.githubcheck.RepositoryState;
import io.github.arlol.githubcheck.actual.ActualBranchProtection;
import io.github.arlol.githubcheck.actual.ActualCollaborators;
import io.github.arlol.githubcheck.actual.ActualCustomPropertyValue;
import io.github.arlol.githubcheck.actual.ActualEnvironment;
import io.github.arlol.githubcheck.actual.ActualPages;
import io.github.arlol.githubcheck.actual.ActualRepository;
import io.github.arlol.githubcheck.actual.ActualRuleset;
import io.github.arlol.githubcheck.actual.ActualSecret;
import io.github.arlol.githubcheck.actual.ActualSecurityAndAnalysis;
import io.github.arlol.githubcheck.actual.ActualSecurityAndAnalysis.BypassReviewer;
import io.github.arlol.githubcheck.actual.ActualVariable;
import io.github.arlol.githubcheck.actual.ActualWebhook;
import io.github.arlol.githubcheck.actual.ActualWorkflowPermissions;
import io.github.arlol.githubcheck.client.MergeCommitMessage;
import io.github.arlol.githubcheck.client.MergeCommitTitle;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.RepositoryVisibility;
import io.github.arlol.githubcheck.client.SquashMergeCommitMessage;
import io.github.arlol.githubcheck.client.SquashMergeCommitTitle;
import io.github.arlol.githubcheck.client.WorkflowPermissions.DefaultWorkflowPermissions;

class RepositoryExporterTest {

	private static final SchemaDefaults DEFAULTS = SchemaDefaults
			.of(Path.of("config/drifty.pkl").toAbsolutePath().toString());

	/**
	 * GitHub's defaults for a freshly created, organization-owned repository.
	 */
	private static ActualRepository defaultRepository() {
		return new ActualRepository(
				false,
				true,
				"",
				"",
				RepositoryVisibility.PUBLIC,
				"main",
				List.of(),
				true,
				true,
				true,
				false,
				false,
				true,
				false,
				true,
				true,
				true,
				false,
				false,
				false,
				SquashMergeCommitTitle.COMMIT_OR_PR_TITLE,
				SquashMergeCommitMessage.COMMIT_MESSAGES,
				MergeCommitTitle.MERGE_MESSAGE,
				MergeCommitMessage.PR_TITLE
		);
	}

	private static ActualSecurityAndAnalysis defaultSecurityAndAnalysis() {
		return new ActualSecurityAndAnalysis(
				true,
				true,
				false,
				false,
				false,
				false,
				false,
				false,
				List.of()
		);
	}

	private static ActualWorkflowPermissions defaultWorkflowPermissions() {
		return new ActualWorkflowPermissions(
				DefaultWorkflowPermissions.WRITE,
				true
		);
	}

	private static RepositoryState state(
			ActualRepository repository,
			ActualSecurityAndAnalysis securityAndAnalysis,
			boolean vulnerabilityAlerts,
			boolean automatedSecurityFixes,
			boolean immutableReleases,
			boolean privateVulnerabilityReporting,
			boolean codeScanningDefaultSetup,
			ActualWorkflowPermissions workflowPermissions
	) {
		return new RepositoryState(
				new RepoRef("acme", "api"),
				repository,
				securityAndAnalysis,
				vulnerabilityAlerts,
				automatedSecurityFixes,
				immutableReleases,
				privateVulnerabilityReporting,
				codeScanningDefaultSetup,
				Map.of(),
				List.of(),
				List.of(),
				Map.of(),
				Map.of(),
				workflowPermissions,
				Optional.empty(),
				List.of(),
				Map.of(),
				List.of(),
				List.of(),
				null
		);
	}

	/**
	 * A branch protection, webhook or similar collection is empty by schema
	 * default, so every field here starts empty and a test sets only the one
	 * collection it exercises — the positional {@link RepositoryState}
	 * constructor otherwise forces every case to restate all eleven.
	 */
	private static final class CollectionsBuilder {

		private Map<String, ActualBranchProtection> branchProtections = Map
				.of();
		private List<ActualRuleset> rulesets = List.of();
		private List<ActualSecret> actionSecrets = List.of();
		private Map<String, ActualEnvironment> environments = Map.of();
		private Map<String, List<ActualSecret>> environmentSecrets = Map.of();
		private Optional<ActualPages> pages = Optional.empty();
		private List<ActualVariable> actionVariables = List.of();
		private Map<String, List<ActualVariable>> environmentVariables = Map
				.of();
		private List<ActualWebhook> webhooks = List.of();
		private List<ActualCustomPropertyValue> customPropertyValues = List
				.of();
		private ActualCollaborators collaborators;

		CollectionsBuilder branchProtections(
				Map<String, ActualBranchProtection> value
		) {
			this.branchProtections = value;
			return this;
		}

		CollectionsBuilder rulesets(List<ActualRuleset> value) {
			this.rulesets = value;
			return this;
		}

		CollectionsBuilder actionSecrets(List<ActualSecret> value) {
			this.actionSecrets = value;
			return this;
		}

		CollectionsBuilder environments(Map<String, ActualEnvironment> value) {
			this.environments = value;
			return this;
		}

		CollectionsBuilder environmentSecrets(
				Map<String, List<ActualSecret>> value
		) {
			this.environmentSecrets = value;
			return this;
		}

		CollectionsBuilder pages(Optional<ActualPages> value) {
			this.pages = value;
			return this;
		}

		CollectionsBuilder actionVariables(List<ActualVariable> value) {
			this.actionVariables = value;
			return this;
		}

		CollectionsBuilder environmentVariables(
				Map<String, List<ActualVariable>> value
		) {
			this.environmentVariables = value;
			return this;
		}

		CollectionsBuilder webhooks(List<ActualWebhook> value) {
			this.webhooks = value;
			return this;
		}

		CollectionsBuilder customPropertyValues(
				List<ActualCustomPropertyValue> value
		) {
			this.customPropertyValues = value;
			return this;
		}

		CollectionsBuilder collaborators(ActualCollaborators value) {
			this.collaborators = value;
			return this;
		}

		RepositoryState build() {
			return new RepositoryState(
					new RepoRef("acme", "api"),
					defaultRepository(),
					defaultSecurityAndAnalysis(),
					true,
					false,
					false,
					false,
					false,
					branchProtections,
					rulesets,
					actionSecrets,
					environments,
					environmentSecrets,
					defaultWorkflowPermissions(),
					pages,
					actionVariables,
					environmentVariables,
					webhooks,
					customPropertyValues,
					collaborators
			);
		}

	}

	private static ActualBranchProtection defaultBranchProtection() {
		return new ActualBranchProtection(
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				Set.of(),
				Optional.empty(),
				Optional.empty()
		);
	}

	/**
	 * A repository at GitHub's defaults, at rest on every field this exporter
	 * covers.
	 */
	private static RepositoryState defaultState() {
		return state(
				defaultRepository(),
				defaultSecurityAndAnalysis(),
				true,
				false,
				false,
				false,
				false,
				defaultWorkflowPermissions()
		);
	}

	@Test
	void aRepositoryAtGitHubsDefaultsExportsOnlyName() {
		PklNode entry = RepositoryExporter
				.entry(defaultState(), List.of(), DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo("""
				name = "api"
				""");
	}

	/**
	 * A group the token could not read is a note where that group's section
	 * would otherwise sit, not an empty section indistinguishable from "GitHub
	 * has nothing there" — the same rule {@code AccountExporterTest} pins for
	 * an organization. {@code action_secrets} and {@code collaborators} are
	 * picked because they sit at opposite ends of {@code collectionMembers}:
	 * this also proves a note earlier in the method doesn't swallow or reorder
	 * one that comes later.
	 */
	@Test
	void aGroupFailureBecomesANoteWhereTheGroupWouldSit() {
		var failures = List.of(
				new FetchFailures.Failure("action_secrets", "read failed"),
				new FetchFailures.Failure("collaborators", "read failed")
		);

		PklNode entry = RepositoryExporter
				.entry(defaultState(), failures, DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo("""
				name = "api"
				// action_secrets: read failed
				// collaborators: read failed
				""");
	}

	@Test
	void aChangedMergeSettingExportsNameAndThatSetting() {
		ActualRepository base = defaultRepository();
		ActualRepository changed = new ActualRepository(
				base.archived(),
				base.organizationOwned(),
				base.description(),
				base.homepage(),
				base.visibility(),
				base.defaultBranch(),
				base.topics(),
				base.hasIssues(),
				base.hasProjects(),
				base.hasWiki(),
				base.hasDiscussions(),
				base.isTemplate(),
				base.allowForking(),
				base.webCommitSignoffRequired(),
				base.allowMergeCommit(),
				base.allowSquashMerge(),
				base.allowRebaseMerge(),
				base.allowAutoMerge(),
				base.allowUpdateBranch(),
				base.deleteBranchOnMerge(),
				SquashMergeCommitTitle.PR_TITLE,
				base.squashMergeCommitMessage(),
				base.mergeCommitTitle(),
				base.mergeCommitMessage()
		);
		RepositoryState state = state(
				changed,
				defaultSecurityAndAnalysis(),
				true,
				false,
				false,
				false,
				false,
				defaultWorkflowPermissions()
		);

		PklNode entry = RepositoryExporter.entry(state, List.of(), DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo("""
				name = "api"
				squashMergeCommitTitle = "PR_TITLE"
				""");
	}

	@Test
	void anArchivedRepositoryExportsArchivedAndTheNoteWithNoSecurityFields() {
		ActualRepository base = defaultRepository();
		ActualRepository archived = new ActualRepository(
				true,
				base.organizationOwned(),
				base.description(),
				base.homepage(),
				base.visibility(),
				base.defaultBranch(),
				base.topics(),
				base.hasIssues(),
				base.hasProjects(),
				base.hasWiki(),
				base.hasDiscussions(),
				base.isTemplate(),
				base.allowForking(),
				base.webCommitSignoffRequired(),
				base.allowMergeCommit(),
				base.allowSquashMerge(),
				base.allowRebaseMerge(),
				base.allowAutoMerge(),
				base.allowUpdateBranch(),
				base.deleteBranchOnMerge(),
				base.squashMergeCommitTitle(),
				base.squashMergeCommitMessage(),
				base.mergeCommitTitle(),
				base.mergeCommitMessage()
		);
		// Values that differ from every default, so a broken archived guard
		// would leak them as real drift instead of staying silent — not
		// values the checker actually failed to read: security_and_analysis
		// and workflow permissions are fetched regardless of archived state,
		// they are just never compared once archived = true (see the class
		// comment).
		ActualSecurityAndAnalysis driftedSecurity = new ActualSecurityAndAnalysis(
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				List.of()
		);
		ActualWorkflowPermissions driftedWorkflowPermissions = new ActualWorkflowPermissions(
				DefaultWorkflowPermissions.READ,
				false
		);
		RepositoryState state = state(
				archived,
				driftedSecurity,
				false,
				false,
				false,
				false,
				false,
				driftedWorkflowPermissions
		);

		PklNode entry = RepositoryExporter.entry(state, List.of(), DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo(
				"""
						name = "api"
						archived = true
						// drifty checks only archived on an archived repository, so its other settings
						// are neither compared nor exported
						"""
		);
	}

	@Test
	void aPrivateRepositoryExportsVisibilityAsAFieldAndANote() {
		ActualRepository base = defaultRepository();
		ActualRepository priv = new ActualRepository(
				base.archived(),
				base.organizationOwned(),
				base.description(),
				base.homepage(),
				RepositoryVisibility.PRIVATE,
				base.defaultBranch(),
				base.topics(),
				base.hasIssues(),
				base.hasProjects(),
				base.hasWiki(),
				base.hasDiscussions(),
				base.isTemplate(),
				base.allowForking(),
				base.webCommitSignoffRequired(),
				base.allowMergeCommit(),
				base.allowSquashMerge(),
				base.allowRebaseMerge(),
				base.allowAutoMerge(),
				base.allowUpdateBranch(),
				base.deleteBranchOnMerge(),
				base.squashMergeCommitTitle(),
				base.squashMergeCommitMessage(),
				base.mergeCommitTitle(),
				base.mergeCommitMessage()
		);
		RepositoryState state = state(
				priv,
				defaultSecurityAndAnalysis(),
				true,
				false,
				false,
				false,
				false,
				defaultWorkflowPermissions()
		);

		PklNode entry = RepositoryExporter.entry(state, List.of(), DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo(
				"""
						name = "api"
						visibility = "private"
						// visibility is private on GitHub; drifty reports it but never changes it:
						// public to private breaks forks, private to public exposes code
						"""
		);
	}

	@Test
	void allowForkingIsOmittedForANonOrganizationOwnedRepository() {
		ActualRepository base = defaultRepository();
		// RepoSettingsDriftGroup only compares allow_forking on an
		// organization-owned repository, so a personal repository's export
		// must not invent that comparison even when the value differs.
		ActualRepository personal = new ActualRepository(
				base.archived(),
				false,
				base.description(),
				base.homepage(),
				base.visibility(),
				base.defaultBranch(),
				base.topics(),
				base.hasIssues(),
				base.hasProjects(),
				base.hasWiki(),
				base.hasDiscussions(),
				base.isTemplate(),
				false,
				base.webCommitSignoffRequired(),
				base.allowMergeCommit(),
				base.allowSquashMerge(),
				base.allowRebaseMerge(),
				base.allowAutoMerge(),
				base.allowUpdateBranch(),
				base.deleteBranchOnMerge(),
				base.squashMergeCommitTitle(),
				base.squashMergeCommitMessage(),
				base.mergeCommitTitle(),
				base.mergeCommitMessage()
		);
		RepositoryState state = state(
				personal,
				defaultSecurityAndAnalysis(),
				true,
				false,
				false,
				false,
				false,
				defaultWorkflowPermissions()
		);

		PklNode entry = RepositoryExporter.entry(state, List.of(), DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo("""
				name = "api"
				""");
	}

	@Test
	void topicsAreEmittedSorted() {
		ActualRepository base = defaultRepository();
		ActualRepository withTopics = new ActualRepository(
				base.archived(),
				base.organizationOwned(),
				base.description(),
				base.homepage(),
				base.visibility(),
				base.defaultBranch(),
				List.of("zebra", "alpha"),
				base.hasIssues(),
				base.hasProjects(),
				base.hasWiki(),
				base.hasDiscussions(),
				base.isTemplate(),
				base.allowForking(),
				base.webCommitSignoffRequired(),
				base.allowMergeCommit(),
				base.allowSquashMerge(),
				base.allowRebaseMerge(),
				base.allowAutoMerge(),
				base.allowUpdateBranch(),
				base.deleteBranchOnMerge(),
				base.squashMergeCommitTitle(),
				base.squashMergeCommitMessage(),
				base.mergeCommitTitle(),
				base.mergeCommitMessage()
		);
		RepositoryState state = state(
				withTopics,
				defaultSecurityAndAnalysis(),
				true,
				false,
				false,
				false,
				false,
				defaultWorkflowPermissions()
		);

		PklNode entry = RepositoryExporter.entry(state, List.of(), DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo("""
				name = "api"
				topics = new Listing {
				  "alpha"
				  "zebra"
				}
				""");
	}

	@Test
	void secretScanningDelegatedBypassReviewersAreEmittedSorted() {
		ActualSecurityAndAnalysis security = new ActualSecurityAndAnalysis(
				true,
				true,
				false,
				false,
				false,
				false,
				false,
				true,
				List.of(
						new BypassReviewer("TEAM", 2L),
						new BypassReviewer("ROLE", 1L)
				)
		);
		RepositoryState state = state(
				defaultRepository(),
				security,
				true,
				false,
				false,
				false,
				false,
				defaultWorkflowPermissions()
		);

		PklNode entry = RepositoryExporter.entry(state, List.of(), DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo("""
				name = "api"
				secretScanningDelegatedBypass = true
				secretScanningDelegatedBypassReviewers {
				  new {
				    reviewerId = 1
				    reviewerType = "ROLE"
				  }
				  new {
				    reviewerId = 2
				    reviewerType = "TEAM"
				  }
				}
				""");
	}

	@Test
	void workflowPermissionsAreExportedWhenTheyDiffer() {
		RepositoryState state = state(
				defaultRepository(),
				defaultSecurityAndAnalysis(),
				true,
				false,
				false,
				false,
				false,
				new ActualWorkflowPermissions(
						DefaultWorkflowPermissions.READ,
						false
				)
		);

		PklNode entry = RepositoryExporter.entry(state, List.of(), DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo("""
				name = "api"
				defaultWorkflowPermissions = "read"
				canApprovePullRequestReviews = false
				""");
	}

	@Test
	void nullWorkflowPermissionsAreGuardedNotDereferenced() {
		RepositoryState state = state(
				defaultRepository(),
				defaultSecurityAndAnalysis(),
				true,
				false,
				false,
				false,
				false,
				null
		);

		PklNode entry = RepositoryExporter.entry(state, List.of(), DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo("""
				name = "api"
				""");
	}

	@Test
	void branchProtectionsAreKeyedByPatternAndSorted() {
		RepositoryState state = new CollectionsBuilder()
				.branchProtections(
						Map.of(
								"release/*",
								new ActualBranchProtection(
										true,
										false,
										false,
										false,
										false,
										false,
										false,
										false,
										false,
										Set.of(),
										Optional.empty(),
										Optional.empty()
								),
								"main",
								defaultBranchProtection()
						)
				)
				.build();

		PklNode entry = RepositoryExporter.entry(state, List.of(), DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo("""
				name = "api"
				branchProtections {
				  ["main"] {
				  }
				  ["release/*"] {
				    enforceAdmins = true
				  }
				}
				""");
	}

	@Test
	void rulesetsGoThroughRulesetExporterWithRepositoryScope() {
		// repositoryNameProtected only exists in the schema on OrgRuleset, so
		// RulesetExporter only writes it when orgScope is true. Setting it
		// here and expecting it absent is what proves the attach step passes
		// false rather than true — a fixture with every field at its default
		// would render identically either way.
		ActualRuleset withRepositoryNameProtected = new ActualRuleset(
				1L,
				"main",
				"branch",
				"active",
				Set.of(),
				Set.of(),
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				Set.of(),
				null,
				Set.of(),
				Set.of(),
				null,
				null,
				null,
				null,
				null,
				null,
				Set.of(),
				Set.of(),
				null,
				Set.of(),
				null,
				List.of(),
				Set.of(),
				Set.of(),
				true,
				Set.of(),
				Set.of()
		);
		RepositoryState state = new CollectionsBuilder()
				.rulesets(List.of(withRepositoryNameProtected))
				.build();

		PklNode entry = RepositoryExporter.entry(state, List.of(), DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo("""
				name = "api"
				rulesets {
				  ["main"] {
				  }
				}
				""");
	}

	@Test
	void environmentsCarryTheirOwnSecretsAndVariablesByName() {
		RepositoryState state = new CollectionsBuilder().environments(
				Map.of("production", new ActualEnvironment(10, false, false))
		)
				.environmentSecrets(
						Map.of(
								"production",
								List.of(
										new ActualSecret(
												"token",
												"2024-01-01T00:00:00Z"
										)
								)
						)
				)
				.environmentVariables(
						Map.of(
								"production",
								List.of(new ActualVariable("region", "eu"))
						)
				)
				.build();

		PklNode entry = RepositoryExporter.entry(state, List.of(), DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo(
				"""
						name = "api"
						environments {
						  ["production"] {
						    secrets = new Listing {
						      "token"
						    }
						    // secret values are never returned by GitHub; supply them through
						    // DRIFTY_GITHUB_SECRETS
						    variables {
						      ["region"] = "eu"
						    }
						    waitTimer = 10
						  }
						}
						"""
		);
	}

	@Test
	void webhooksGoThroughWebhookExporterSortedByUrl() {
		RepositoryState state = new CollectionsBuilder()
				.webhooks(
						List.of(
								new ActualWebhook(
										2L,
										"https://z.example.com/hook",
										"form",
										false,
										true,
										Set.of("push"),
										false,
										null
								),
								new ActualWebhook(
										1L,
										"https://a.example.com/hook",
										"form",
										false,
										true,
										Set.of("push"),
										false,
										null
								)
						)
				)
				.build();

		PklNode entry = RepositoryExporter.entry(state, List.of(), DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo("""
				name = "api"
				webhooks {
				  ["a.example.com/hook"] {
				    url = "https://a.example.com/hook"
				  }
				  ["z.example.com/hook"] {
				    url = "https://z.example.com/hook"
				  }
				}
				""");
	}

	@Test
	void actionsSecretsAreListedByNameWithTheValueNote() {
		RepositoryState state = new CollectionsBuilder().actionSecrets(
				List.of(
						new ActualSecret("beta", "2024-01-01T00:00:00Z"),
						new ActualSecret("alpha", "2024-01-01T00:00:00Z")
				)
		).build();

		PklNode entry = RepositoryExporter.entry(state, List.of(), DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo(
				"""
						name = "api"
						actionsSecrets = new Listing {
						  "alpha"
						  "beta"
						}
						// secret values are never returned by GitHub; supply them through
						// DRIFTY_GITHUB_SECRETS
						"""
		);
	}

	@Test
	void noActionsSecretsMeansNoNote() {
		RepositoryState state = defaultState();

		PklNode entry = RepositoryExporter.entry(state, List.of(), DEFAULTS);

		assertThat(PklWriter.write(entry))
				.doesNotContain("DRIFTY_GITHUB_SECRETS");
	}

	@Test
	void actionsVariablesAreMappedNameToValueSorted() {
		RepositoryState state = new CollectionsBuilder()
				.actionVariables(
						List.of(
								new ActualVariable("zebra", "z"),
								new ActualVariable("alpha", "a")
						)
				)
				.build();

		PklNode entry = RepositoryExporter.entry(state, List.of(), DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo("""
				name = "api"
				actionsVariables {
				  ["alpha"] = "a"
				  ["zebra"] = "z"
				}
				""");
	}

	@Test
	void pagesIsEmittedAsAnObjectWhenPresent() {
		RepositoryState state = new CollectionsBuilder()
				.pages(
						Optional.of(
								new ActualPages(
										"legacy",
										Optional.of(
												new ActualPages.Source(
														"main",
														"/docs"
												)
										),
										true
								)
						)
				)
				.build();

		PklNode entry = RepositoryExporter.entry(state, List.of(), DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo("""
				name = "api"
				pages {
				  buildType = "legacy"
				  sourceBranch = "main"
				  sourcePath = "/docs"
				}
				""");
	}

	@Test
	void noPagesMeansNoPagesField() {
		RepositoryState state = defaultState();

		PklNode entry = RepositoryExporter.entry(state, List.of(), DEFAULTS);

		assertThat(PklWriter.write(entry)).doesNotContain("pages");
	}

	/**
	 * {@code CustomPropertiesDriftGroup} keeps single-valued and multi-select
	 * property values in two separate maps because the schema does; a value
	 * whose {@code values()} list is non-empty routes to
	 * {@code customMultiSelectProperties} and everything else with a non-null
	 * {@code value()} routes to {@code customProperties} — both in the same
	 * call, so a routing bug in one direction cannot hide behind the other
	 * being absent.
	 */
	@Test
	void customPropertiesSplitSingleValuedFromMultiSelect() {
		RepositoryState state = new CollectionsBuilder().customPropertyValues(
				List.of(
						new ActualCustomPropertyValue(
								"team",
								"platform",
								List.of()
						),
						new ActualCustomPropertyValue(
								"environments",
								null,
								List.of("staging", "production")
						),
						new ActualCustomPropertyValue("unset", null, List.of())
				)
		).build();

		PklNode entry = RepositoryExporter.entry(state, List.of(), DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo("""
				name = "api"
				customProperties {
				  ["team"] = "platform"
				}
				customMultiSelectProperties {
				  ["environments"] {
				    "production"
				    "staging"
				  }
				}
				""");
	}

	/**
	 * {@code ActualCollaborators} already carries each permission in the
	 * config's own vocabulary, so this checks the string is written through
	 * unchanged rather than translated the way a client enum would be.
	 */
	@Test
	void collaboratorsAndTeamPermissionsUseTheGivenPermissionStringDirectly() {
		RepositoryState state = new CollectionsBuilder()
				.collaborators(
						new ActualCollaborators(
								Map.of("alice", "admin"),
								Map.of("platform", "push")
						)
				)
				.build();

		PklNode entry = RepositoryExporter.entry(state, List.of(), DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo("""
				name = "api"
				collaborators {
				  ["alice"] = "admin"
				}
				teamPermissions {
				  ["platform"] = "push"
				}
				""");
	}

	@Test
	void nullCollaboratorsIsGuardedNotDereferenced() {
		// CollectionsBuilder defaults collaborators to null, the way
		// RepositoryState.collaborators() is null when its group was not
		// read.
		RepositoryState state = new CollectionsBuilder().build();

		PklNode entry = RepositoryExporter.entry(state, List.of(), DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo("""
				name = "api"
				""");
	}

	/**
	 * Once {@code archived = true}, {@code RepositoryChecker.createDriftGroups}
	 * runs only {@code ArchivedDriftGroup} and skips every other group — branch
	 * protections included — so a value here would never be compared against
	 * this file again; exporting it would add a field nothing acts on.
	 */
	@Test
	void archivedRepositorySkipsEveryCollectionToo() {
		ActualRepository base = defaultRepository();
		ActualRepository archived = new ActualRepository(
				true,
				base.organizationOwned(),
				base.description(),
				base.homepage(),
				base.visibility(),
				base.defaultBranch(),
				base.topics(),
				base.hasIssues(),
				base.hasProjects(),
				base.hasWiki(),
				base.hasDiscussions(),
				base.isTemplate(),
				base.allowForking(),
				base.webCommitSignoffRequired(),
				base.allowMergeCommit(),
				base.allowSquashMerge(),
				base.allowRebaseMerge(),
				base.allowAutoMerge(),
				base.allowUpdateBranch(),
				base.deleteBranchOnMerge(),
				base.squashMergeCommitTitle(),
				base.squashMergeCommitMessage(),
				base.mergeCommitTitle(),
				base.mergeCommitMessage()
		);
		RepositoryState notArchived = new CollectionsBuilder()
				.webhooks(
						List.of(
								new ActualWebhook(
										1L,
										"https://example.com/hook",
										"json",
										false,
										true,
										Set.of("push"),
										false,
										null
								)
						)
				)
				.build();
		RepositoryState state = new RepositoryState(
				notArchived.ref(),
				archived,
				notArchived.securityAndAnalysis(),
				notArchived.vulnerabilityAlerts(),
				notArchived.automatedSecurityFixes(),
				notArchived.immutableReleases(),
				notArchived.privateVulnerabilityReporting(),
				notArchived.codeScanningDefaultSetup(),
				notArchived.branchProtections(),
				notArchived.rulesets(),
				notArchived.actionSecrets(),
				notArchived.environments(),
				notArchived.environmentSecrets(),
				notArchived.workflowPermissions(),
				notArchived.pages(),
				notArchived.actionVariables(),
				notArchived.environmentVariables(),
				notArchived.webhooks(),
				notArchived.customPropertyValues(),
				notArchived.collaborators()
		);

		PklNode entry = RepositoryExporter.entry(state, List.of(), DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo(
				"""
						name = "api"
						archived = true
						// drifty checks only archived on an archived repository, so its other settings
						// are neither compared nor exported
						"""
		);
	}

}
