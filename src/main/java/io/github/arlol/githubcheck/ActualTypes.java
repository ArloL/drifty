package io.github.arlol.githubcheck;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.github.arlol.githubcheck.actual.ActualBranchProtection;
import io.github.arlol.githubcheck.actual.ActualCodeSecurityConfiguration;
import io.github.arlol.githubcheck.actual.ActualCollaborators;
import io.github.arlol.githubcheck.actual.ActualOrgMember;
import io.github.arlol.githubcheck.actual.ActualTeam;
import io.github.arlol.githubcheck.actual.ActualCustomProperty;
import io.github.arlol.githubcheck.actual.ActualCustomPropertyValue;
import io.github.arlol.githubcheck.actual.ActualEnvironment;
import io.github.arlol.githubcheck.actual.ActualOrgActionsPermissions;
import io.github.arlol.githubcheck.actual.ActualOrgSecret;
import io.github.arlol.githubcheck.actual.ActualOrgVariable;
import io.github.arlol.githubcheck.actual.ActualOrganization;
import io.github.arlol.githubcheck.actual.ActualPages;
import io.github.arlol.githubcheck.actual.ActualRepository;
import io.github.arlol.githubcheck.actual.ActualRuleset;
import io.github.arlol.githubcheck.actual.ActualRunnerGroup;
import io.github.arlol.githubcheck.actual.ActualSecret;
import io.github.arlol.githubcheck.actual.ActualSecurityAndAnalysis;
import io.github.arlol.githubcheck.actual.ActualSelectedActions;
import io.github.arlol.githubcheck.actual.ActualVariable;
import io.github.arlol.githubcheck.actual.ActualWebhook;
import io.github.arlol.githubcheck.actual.ActualWorkflowPermissions;
import io.github.arlol.githubcheck.actual.StatusCheck;
import io.github.arlol.githubcheck.client.BranchProtectionResponse;
import io.github.arlol.githubcheck.client.BranchPolicyType;
import io.github.arlol.githubcheck.client.CodeSecurityConfigurationResponse;
import io.github.arlol.githubcheck.client.CollaboratorResponse;
import io.github.arlol.githubcheck.client.RepoTeamResponse;
import io.github.arlol.githubcheck.client.RunnerGroupResponse;
import io.github.arlol.githubcheck.client.TeamResponse;
import io.github.arlol.githubcheck.client.CodeSecurityRepositoryResponse;
import io.github.arlol.githubcheck.client.CustomPropertyResponse;
import io.github.arlol.githubcheck.client.CustomPropertyValueResponse;
import io.github.arlol.githubcheck.client.DeploymentBranchPolicyResponse;
import io.github.arlol.githubcheck.client.EnvironmentDetailsResponse;
import io.github.arlol.githubcheck.client.EnvironmentReviewerType;
import io.github.arlol.githubcheck.client.OrgActionsPermissionsResponse;
import io.github.arlol.githubcheck.client.OrgSecretResponse;
import io.github.arlol.githubcheck.client.OrgVariableResponse;
import io.github.arlol.githubcheck.client.OrganizationResponse;
import io.github.arlol.githubcheck.client.PagesResponse;
import io.github.arlol.githubcheck.client.RepositoryDetailsResponse;
import io.github.arlol.githubcheck.client.Rule;
import io.github.arlol.githubcheck.client.RulesetDetailsResponse;
import io.github.arlol.githubcheck.client.RulesetRuleType;
import io.github.arlol.githubcheck.client.Secret;
import io.github.arlol.githubcheck.client.SecurityAndAnalysis;
import io.github.arlol.githubcheck.client.SelectedActions;
import io.github.arlol.githubcheck.client.SimpleUser;
import io.github.arlol.githubcheck.client.VariableResponse;
import io.github.arlol.githubcheck.client.WebhookResponse;
import io.github.arlol.githubcheck.client.WorkflowPermissions;

/**
 * Converts GitHub's REST responses into the {@code actual.*} types the drift
 * comparison works in — the mirror of {@link PklTypes}, which does the same for
 * the Pkl-generated configuration on the desired side.
 * <p>
 * Everything that knows how GitHub serialises a repository, a ruleset, a branch
 * protection, a Pages site, an environment or a secret lives here: the list of
 * typed rule objects with their nested parameters, the {@code {"enabled":
 * bool}} and {@code {"status": "enabled"}} wrappers, the two shapes required
 * status checks come back in, the sections that are omitted rather than
 * returned empty, and the {@code null}s that stand for an empty string. Keeping
 * that in one place is what makes a change of read path — GraphQL bulk reads, a
 * new API version — a change to this class rather than to every drift group.
 */
public final class ActualTypes {

	private ActualTypes() {
	}

	// ─── Rulesets
	// ──────────────────────────────────────────────────────────

	public static ActualRuleset ruleset(RulesetDetailsResponse response) {
		Map<RulesetRuleType, Rule> rules = rulesByType(response);
		var conditions = response.conditions();
		var refName = conditions == null ? null : conditions.refName();
		var repositoryName = conditions == null ? null
				: conditions.repositoryName();
		var repositoryProperty = conditions == null ? null
				: conditions.repositoryProperty();
		return new ActualRuleset(
				response.id(),
				response.name(),
				wire(response.target()),
				wire(response.enforcement()),
				patterns(refName == null ? null : refName.include()),
				patterns(refName == null ? null : refName.exclude()),
				rules.containsKey(RulesetRuleType.CREATION),
				rules.containsKey(RulesetRuleType.DELETION),
				rules.containsKey(RulesetRuleType.UPDATE),
				updateAllowsFetchAndMerge(rules),
				rules.containsKey(RulesetRuleType.REQUIRED_SIGNATURES),
				rules.containsKey(RulesetRuleType.REQUIRED_LINEAR_HISTORY),
				rules.containsKey(RulesetRuleType.NON_FAST_FORWARD),
				strictStatusChecks(rules),
				statusChecks(rules),
				pullRequest(rules),
				codeScanningTools(rules),
				requiredDeployments(rules),
				pattern(rules, RulesetRuleType.COMMIT_MESSAGE_PATTERN),
				pattern(rules, RulesetRuleType.COMMIT_AUTHOR_EMAIL_PATTERN),
				pattern(rules, RulesetRuleType.COMMITTER_EMAIL_PATTERN),
				pattern(rules, RulesetRuleType.BRANCH_NAME_PATTERN),
				pattern(rules, RulesetRuleType.TAG_NAME_PATTERN),
				mergeQueue(rules),
				workflows(rules),
				filePathRestrictions(rules),
				maxFilePathLength(rules),
				fileExtensionRestrictions(rules),
				maxFileSize(rules),
				bypassActors(response),
				patterns(
						repositoryName == null ? null : repositoryName.include()
				),
				patterns(
						repositoryName == null ? null : repositoryName.exclude()
				),
				repositoryName != null
						&& Boolean.TRUE.equals(repositoryName.isProtected()),
				propertyConditions(
						repositoryProperty == null ? null
								: repositoryProperty.include()
				),
				propertyConditions(
						repositoryProperty == null ? null
								: repositoryProperty.exclude()
				)
		);
	}

	/**
	 * The wire spelling of a client enum — what Jackson would write — which is
	 * also what the Pkl enum's {@code toString()} gives on the desired side.
	 */
	private static String wire(Enum<?> value) {
		return value == null ? null : value.name().toLowerCase(Locale.ROOT);
	}

	private static Map<RulesetRuleType, Rule> rulesByType(
			RulesetDetailsResponse ruleset
	) {
		if (ruleset.rules() == null) {
			return Map.of();
		}
		return ruleset.rules()
				.stream()
				.filter(r -> r.type() != null)
				.collect(Collectors.toMap(Rule::type, r -> r, (a, _) -> a));
	}

	private static Set<String> patterns(List<String> patterns) {
		return patterns == null ? Set.of() : new HashSet<>(patterns);
	}

	private static Set<ActualRuleset.PropertyCondition> propertyConditions(
			List<RulesetDetailsResponse.Conditions.RepositoryProperty.PropertyCondition> conditions
	) {
		if (conditions == null) {
			return Set.of();
		}
		return conditions.stream()
				.map(
						c -> new ActualRuleset.PropertyCondition(
								c.name(),
								patterns(c.propertyValues()),
								c.source() == null ? "custom" : c.source()
						)
				)
				.collect(Collectors.toSet());
	}

	private static boolean updateAllowsFetchAndMerge(
			Map<RulesetRuleType, Rule> rules
	) {
		return rules.get(RulesetRuleType.UPDATE) instanceof Rule.Update update
				&& update.parameters() != null
				&& Boolean.TRUE.equals(
						update.parameters().updateAllowsFetchAndMerge()
				);
	}

	private static boolean strictStatusChecks(
			Map<RulesetRuleType, Rule> rules
	) {
		return rules.get(
				RulesetRuleType.REQUIRED_STATUS_CHECKS
		) instanceof Rule.RequiredStatusChecks rsc
				&& rsc.parameters() != null
				&& Boolean.TRUE.equals(
						rsc.parameters().strictRequiredStatusChecksPolicy()
				);
	}

	private static Set<StatusCheck> statusChecks(
			Map<RulesetRuleType, Rule> rules
	) {
		if (rules.get(
				RulesetRuleType.REQUIRED_STATUS_CHECKS
		) instanceof Rule.RequiredStatusChecks rsc && rsc.parameters() != null
				&& rsc.parameters().requiredStatusChecks() != null) {
			return rsc.parameters()
					.requiredStatusChecks()
					.stream()
					.map(
							sc -> new StatusCheck(
									sc.context(),
									sc.integrationId()
							)
					)
					.collect(Collectors.toSet());
		}
		return Set.of();
	}

	/**
	 * GitHub returns every parameter of a pull_request rule, so a missing one
	 * is read as the default it would have been created with.
	 */
	private static ActualRuleset.PullRequest pullRequest(
			Map<RulesetRuleType, Rule> rules
	) {
		if (!(rules.get(
				RulesetRuleType.PULL_REQUEST
		) instanceof Rule.PullRequest pr)) {
			return null;
		}
		var p = pr.parameters();
		if (p == null) {
			return new ActualRuleset.PullRequest(
					0,
					false,
					false,
					false,
					false,
					Set.of()
			);
		}
		return new ActualRuleset.PullRequest(
				p.requiredApprovingReviewCount() == null ? 0
						: p.requiredApprovingReviewCount(),
				Boolean.TRUE.equals(p.dismissStaleReviewsOnPush()),
				Boolean.TRUE.equals(p.requireCodeOwnerReview()),
				Boolean.TRUE.equals(p.requireLastPushApproval()),
				Boolean.TRUE.equals(p.requiredReviewThreadResolution()),
				patterns(p.allowedMergeMethods())
		);
	}

	private static ActualRuleset.MergeQueue mergeQueue(
			Map<RulesetRuleType, Rule> rules
	) {
		if (!(rules
				.get(RulesetRuleType.MERGE_QUEUE) instanceof Rule.MergeQueue mq)
				|| mq.parameters() == null) {
			return null;
		}
		var p = mq.parameters();
		return new ActualRuleset.MergeQueue(
				orZero(p.checkResponseTimeoutMinutes()),
				p.groupingStrategy(),
				orZero(p.maxEntriesToBuild()),
				orZero(p.maxEntriesToMerge()),
				p.mergeMethod(),
				orZero(p.minEntriesToMerge()),
				orZero(p.minEntriesToMergeWaitMinutes())
		);
	}

	private static int orZero(Integer value) {
		return value == null ? 0 : value;
	}

	private static Set<ActualRuleset.Workflow> workflows(
			Map<RulesetRuleType, Rule> rules
	) {
		if (rules.get(RulesetRuleType.WORKFLOWS) instanceof Rule.Workflows w
				&& w.parameters() != null
				&& w.parameters().workflows() != null) {
			return w.parameters()
					.workflows()
					.stream()
					.map(
							wf -> new ActualRuleset.Workflow(
									wf.path(),
									wf.repositoryId() == null ? 0
											: wf.repositoryId(),
									wf.ref()
							)
					)
					.collect(Collectors.toSet());
		}
		return Set.of();
	}

	private static Set<String> filePathRestrictions(
			Map<RulesetRuleType, Rule> rules
	) {
		return rules.get(
				RulesetRuleType.FILE_PATH_RESTRICTION
		) instanceof Rule.FilePathRestriction r && r.parameters() != null
				? patterns(r.parameters().restrictedFilePaths())
				: Set.of();
	}

	private static Set<String> fileExtensionRestrictions(
			Map<RulesetRuleType, Rule> rules
	) {
		return rules.get(
				RulesetRuleType.FILE_EXTENSION_RESTRICTION
		) instanceof Rule.FileExtensionRestriction r && r.parameters() != null
				? patterns(r.parameters().restrictedFileExtensions())
				: Set.of();
	}

	private static Integer maxFilePathLength(Map<RulesetRuleType, Rule> rules) {
		return rules.get(
				RulesetRuleType.MAX_FILE_PATH_LENGTH
		) instanceof Rule.MaxFilePathLength r && r.parameters() != null
				? r.parameters().maxFilePathLength()
				: null;
	}

	private static Integer maxFileSize(Map<RulesetRuleType, Rule> rules) {
		return rules.get(
				RulesetRuleType.MAX_FILE_SIZE
		) instanceof Rule.MaxFileSize r && r.parameters() != null
				? r.parameters().maxFileSize()
				: null;
	}

	private static Set<String> codeScanningTools(
			Map<RulesetRuleType, Rule> rules
	) {
		if (rules.get(
				RulesetRuleType.CODE_SCANNING
		) instanceof Rule.CodeScanning cs && cs.parameters() != null
				&& cs.parameters().codeScanningTools() != null) {
			return cs.parameters()
					.codeScanningTools()
					.stream()
					.map(Rule.CodeScanningTool::tool)
					.collect(Collectors.toSet());
		}
		return Set.of();
	}

	private static Set<String> requiredDeployments(
			Map<RulesetRuleType, Rule> rules
	) {
		if (rules.get(
				RulesetRuleType.REQUIRED_DEPLOYMENTS
		) instanceof Rule.RequiredDeployments rd && rd.parameters() != null
				&& rd.parameters().requiredDeploymentEnvironments() != null) {
			return new HashSet<>(
					rd.parameters().requiredDeploymentEnvironments()
			);
		}
		return Set.of();
	}

	private static String pattern(
			Map<RulesetRuleType, Rule> rules,
			RulesetRuleType type
	) {
		return switch (rules.get(type)) {
		case Rule.CommitMessagePattern r ->
			r.parameters() == null ? null : r.parameters().pattern();
		case Rule.CommitAuthorEmailPattern r ->
			r.parameters() == null ? null : r.parameters().pattern();
		case Rule.CommitterEmailPattern r ->
			r.parameters() == null ? null : r.parameters().pattern();
		case Rule.BranchNamePattern r ->
			r.parameters() == null ? null : r.parameters().pattern();
		case Rule.TagNamePattern r ->
			r.parameters() == null ? null : r.parameters().pattern();
		case null, default -> null;
		};
	}

	private static List<ActualRuleset.BypassActor> bypassActors(
			RulesetDetailsResponse ruleset
	) {
		if (ruleset.bypassActors() == null) {
			return List.of();
		}
		return ruleset.bypassActors()
				.stream()
				.map(
						a -> new ActualRuleset.BypassActor(
								String.valueOf(a.actorType()),
								a.actorId(),
								String.valueOf(a.bypassMode())
						)
				)
				.toList();
	}

	// ─── Branch protection
	// ──────────────────────────────────────────────

	public static ActualBranchProtection branchProtection(
			BranchProtectionResponse response
	) {
		// GitHub omits a section rather than returning it disabled, so every
		// wrapper here is optional.
		return new ActualBranchProtection(
				response.enforceAdmins() != null
						&& response.enforceAdmins().enabled(),
				response.requiredLinearHistory() != null
						&& response.requiredLinearHistory().enabled(),
				response.allowForcePushes() != null
						&& response.allowForcePushes().enabled(),
				response.allowDeletions() != null
						&& response.allowDeletions().enabled(),
				response.blockCreations() != null
						&& response.blockCreations().enabled(),
				response.lockBranch() != null
						&& Boolean.TRUE.equals(response.lockBranch().enabled()),
				response.allowForkSyncing() != null && Boolean.TRUE
						.equals(response.allowForkSyncing().enabled()),
				response.requiredConversationResolution() != null
						&& response.requiredConversationResolution().enabled(),
				response.requiredStatusChecks() != null
						&& response.requiredStatusChecks().strict(),
				protectionStatusChecks(response),
				pullRequestReviews(response),
				restrictions(response)
		);
	}

	/**
	 * GitHub returns required checks either as {@code checks} objects carrying
	 * an app id, or as bare {@code contexts} strings on older protections.
	 */
	private static Set<StatusCheck> protectionStatusChecks(
			BranchProtectionResponse response
	) {
		var rsc = response.requiredStatusChecks();
		if (rsc == null) {
			return Set.of();
		}
		Set<StatusCheck> checks = new HashSet<>();
		if (rsc.checks() != null && !rsc.checks().isEmpty()) {
			for (var check : rsc.checks()) {
				checks.add(new StatusCheck(check.context(), check.appId()));
			}
		} else if (rsc.contexts() != null) {
			for (var context : rsc.contexts()) {
				checks.add(new StatusCheck(context, null));
			}
		}
		return checks;
	}

	private static Optional<ActualBranchProtection.PullRequestReviews> pullRequestReviews(
			BranchProtectionResponse response
	) {
		var rpr = response.requiredPullRequestReviews();
		if (rpr == null) {
			return Optional.empty();
		}
		return Optional.of(
				new ActualBranchProtection.PullRequestReviews(
						rpr.dismissStaleReviews(),
						rpr.requireCodeOwnerReviews(),
						rpr.requiredApprovingReviewCount(),
						rpr.requireLastPushApproval(),
						actors(rpr.dismissalRestrictions()),
						actors(rpr.bypassPullRequestAllowances())
				)
		);
	}

	/**
	 * Logins and slugs out of the user, team and app objects GitHub returns.
	 */
	private static ActualBranchProtection.Actors actors(
			BranchProtectionResponse.Actors actors
	) {
		if (actors == null) {
			return ActualBranchProtection.Actors.NONE;
		}
		return new ActualBranchProtection.Actors(
				actors.users()
						.stream()
						.map(SimpleUser::login)
						.collect(Collectors.toSet()),
				actors.teams()
						.stream()
						.map(BranchProtectionResponse.Restrictions.Team::slug)
						.collect(Collectors.toSet()),
				actors.apps()
						.stream()
						.map(BranchProtectionResponse.Restrictions.App::slug)
						.collect(Collectors.toSet())
		);
	}

	private static Optional<ActualBranchProtection.Restrictions> restrictions(
			BranchProtectionResponse response
	) {
		var restrictions = response.restrictions();
		if (restrictions == null) {
			return Optional.empty();
		}
		return Optional.of(
				new ActualBranchProtection.Restrictions(
						restrictions.users() == null ? Set.<String>of()
								: restrictions.users()
										.stream()
										.map(SimpleUser::login)
										.collect(Collectors.toSet()),
						restrictions.teams()
								.stream()
								.map(
										BranchProtectionResponse.Restrictions.Team::slug
								)
								.collect(Collectors.toSet()),
						restrictions.apps()
								.stream()
								.map(
										BranchProtectionResponse.Restrictions.App::slug
								)
								.collect(Collectors.toSet())
				)
		);
	}

	static List<ActualRuleset> rulesets(
			List<RulesetDetailsResponse> responses
	) {
		var rulesets = new ArrayList<ActualRuleset>();
		responses.forEach(response -> rulesets.add(ruleset(response)));
		return rulesets;
	}

	// ─── Repository
	// ──────────────────────────────────────────────────────────

	public static ActualRepository repository(
			RepositoryDetailsResponse response
	) {
		return new ActualRepository(
				response.archived(),
				response.owner() != null && response.owner()
						.type() == SimpleUser.UserType.ORGANIZATION,
				// GitHub reports an unset description or homepage as null;
				// the config spells the same thing "".
				Objects.toString(response.description(), ""),
				Objects.toString(response.homepage(), ""),
				response.visibility(),
				response.defaultBranch(),
				response.topics() == null ? List.of() : response.topics(),
				response.hasIssues(),
				response.hasProjects(),
				response.hasWiki(),
				response.hasDiscussions(),
				response.isTemplate(),
				response.allowForking(),
				response.webCommitSignoffRequired(),
				response.allowMergeCommit(),
				response.allowSquashMerge(),
				response.allowRebaseMerge(),
				response.allowAutoMerge(),
				response.allowUpdateBranch(),
				response.deleteBranchOnMerge(),
				response.squashMergeCommitTitle(),
				response.squashMergeCommitMessage(),
				response.mergeCommitTitle(),
				response.mergeCommitMessage()
		);
	}

	/**
	 * GitHub omits the whole {@code security_and_analysis} block for some
	 * repositories and individual toggles for others; both mean off.
	 */
	public static ActualSecurityAndAnalysis securityAndAnalysis(
			RepositoryDetailsResponse response
	) {
		SecurityAndAnalysis sa = response.securityAndAnalysis();
		return new ActualSecurityAndAnalysis(
				enabled(sa, SecurityAndAnalysis::secretScanning),
				enabled(sa, SecurityAndAnalysis::secretScanningPushProtection),
				enabled(
						sa,
						SecurityAndAnalysis::secretScanningNonProviderPatterns
				),
				enabled(sa, SecurityAndAnalysis::secretScanningValidityChecks),
				enabled(sa, SecurityAndAnalysis::advancedSecurity),
				enabled(sa, SecurityAndAnalysis::secretScanningAiDetection),
				enabled(
						sa,
						SecurityAndAnalysis::secretScanningDelegatedAlertDismissal
				),
				enabled(sa, SecurityAndAnalysis::secretScanningDelegatedBypass),
				bypassReviewers(sa)
		);
	}

	private static boolean enabled(
			SecurityAndAnalysis sa,
			Function<SecurityAndAnalysis, SecurityAndAnalysis.StatusObject> toggle
	) {
		return sa != null && SecurityAndAnalysis.isEnabled(toggle.apply(sa));
	}

	private static List<ActualSecurityAndAnalysis.BypassReviewer> bypassReviewers(
			SecurityAndAnalysis sa
	) {
		if (sa == null || sa.secretScanningDelegatedBypassOptions() == null
				|| sa.secretScanningDelegatedBypassOptions()
						.reviewers() == null) {
			return List.of();
		}
		return sa.secretScanningDelegatedBypassOptions()
				.reviewers()
				.stream()
				.filter(r -> r.reviewerId() != null)
				.map(
						r -> new ActualSecurityAndAnalysis.BypassReviewer(
								String.valueOf(r.reviewerType()),
								r.reviewerId()
						)
				)
				.toList();
	}

	// ─── Pages
	// ──────────────────────────────────────────────────────────

	/**
	 * The build type comes back as an enum but the config spells it as a
	 * lower-case string, and sites that predate the field have none at all.
	 */
	public static ActualPages pages(PagesResponse response) {
		return new ActualPages(
				response.buildType() == null ? null
						: response.buildType().name().toLowerCase(Locale.ROOT),
				response.source() == null ? Optional.empty()
						: Optional.of(
								new ActualPages.Source(
										response.source().branch(),
										response.source().path()
								)
						),
				response.httpsEnforced()
		);
	}

	// ─── Environments
	// ──────────────────────────────────────────────────────────

	/**
	 * GitHub keeps the wait timer and the reviewers in typed entries of the
	 * protection-rules list and leaves the branch policy out entirely when
	 * nothing is set. The custom policies come from their own listing, which
	 * the caller only performs when the environment has them on.
	 */
	public static ActualEnvironment environment(
			EnvironmentDetailsResponse response,
			List<DeploymentBranchPolicyResponse> policies
	) {
		var policy = response.deploymentBranchPolicy();
		var reviewersRule = protectionRule(
				response,
				EnvironmentDetailsResponse.ProtectionRuleType.REQUIRED_REVIEWERS
		);
		return new ActualEnvironment(
				protectionRule(
						response,
						EnvironmentDetailsResponse.ProtectionRuleType.WAIT_TIMER
				).map(EnvironmentDetailsResponse.ProtectionRule::waitTimer)
						.orElse(0),
				reviewersRule.map(
						EnvironmentDetailsResponse.ProtectionRule::preventSelfReview
				).map(Boolean.TRUE::equals).orElse(false),
				reviewersRule.map(
						EnvironmentDetailsResponse.ProtectionRule::reviewers
				)
						.orElse(List.of())
						.stream()
						.map(ActualTypes::reviewer)
						.filter(Objects::nonNull)
						.collect(Collectors.toSet()),
				policy != null && policy.protectedBranches(),
				policy != null && policy.customBranchPolicies(),
				policies.stream()
						.map(
								p -> new ActualEnvironment.BranchPolicy(
										p.id(),
										p.type() == BranchPolicyType.TAG ? "tag"
												: "branch",
										p.name()
								)
						)
						.toList()
		);
	}

	public static ActualEnvironment environment(
			EnvironmentDetailsResponse response
	) {
		return environment(response, List.of());
	}

	private static Optional<EnvironmentDetailsResponse.ProtectionRule> protectionRule(
			EnvironmentDetailsResponse response,
			EnvironmentDetailsResponse.ProtectionRuleType type
	) {
		return response.protectionRules()
				.stream()
				.filter(rule -> rule.type() == type)
				.findFirst();
	}

	/**
	 * {@code User:<login>} or {@code Team:<slug>}, which is how the config
	 * names a reviewer; null for a reviewer GitHub returned without either.
	 */
	private static String reviewer(EnvironmentDetailsResponse.Reviewer r) {
		if (r.type() == null || r.reviewer() == null) {
			return null;
		}
		String name = r.type() == EnvironmentReviewerType.TEAM
				? r.reviewer().slug()
				: r.reviewer().login();
		return name == null ? null : reviewerKey(r.type(), name);
	}

	public static String reviewerKey(
			EnvironmentReviewerType type,
			String name
	) {
		return (type == EnvironmentReviewerType.TEAM ? "Team:" : "User:")
				+ name;
	}

	// ─── Secrets and workflow permissions
	// ──────────────────────────────────────────────────────────

	public static ActualSecret secret(Secret response) {
		return new ActualSecret(response.name(), response.updatedAt());
	}

	/**
	 * {@code insecure_ssl} is {@code "1"} for on, and {@code secret} is a
	 * placeholder when one is set — GitHub never returns the value.
	 */
	public static ActualWebhook webhook(WebhookResponse response) {
		var config = response.config();
		return new ActualWebhook(
				response.id(),
				config == null ? null : config.url(),
				config == null || config.contentType() == null ? "form"
						: config.contentType(),
				config != null && "1".equals(config.insecureSsl()),
				response.active(),
				new HashSet<>(response.events()),
				config != null && config.secret() != null
						&& !config.secret().isEmpty(),
				response.updatedAt()
		);
	}

	// ─── Collaborators, teams and members
	// ──────────────────────────────────────────────────────────

	/**
	 * The permission comes from the booleans, which spell triage and maintain
	 * where the legacy {@code permission} field and {@code role_name} do not
	 * agree with the config's vocabulary. Teams that reach the repository
	 * through the organization or enterprise are not direct grants and are left
	 * out; a response without {@code access_source} predates the field and
	 * lists direct grants only.
	 */
	public static ActualCollaborators collaborators(
			List<CollaboratorResponse> collaborators,
			List<RepoTeamResponse> teams
	) {
		var users = new LinkedHashMap<String, String>();
		for (CollaboratorResponse c : collaborators) {
			users.put(
					c.login(),
					permissionLevel(c.permissions(), c.roleName())
			);
		}
		var bySlug = new LinkedHashMap<String, String>();
		for (RepoTeamResponse t : teams) {
			if (t.accessSource() != null
					&& !"direct".equals(t.accessSource())) {
				continue;
			}
			bySlug.put(
					t.slug(),
					permissionLevel(t.permissions(), t.permission())
			);
		}
		return new ActualCollaborators(users, bySlug);
	}

	private static String permissionLevel(
			io.github.arlol.githubcheck.client.Permissions permissions,
			String fallback
	) {
		if (permissions != null) {
			return permissions.level();
		}
		return switch (fallback == null ? "" : fallback) {
		case "read" -> "pull";
		case "write" -> "push";
		default -> fallback;
		};
	}

	public static ActualTeam team(
			TeamResponse response,
			List<SimpleUser> members,
			List<SimpleUser> maintainers
	) {
		return new ActualTeam(
				response.id(),
				response.slug(),
				response.name(),
				Objects.toString(response.description(), ""),
				wire(response.privacy()),
				wire(response.notificationSetting()),
				response.parent() == null ? null : response.parent().slug(),
				members.stream()
						.map(SimpleUser::login)
						.collect(Collectors.toSet()),
				maintainers.stream()
						.map(SimpleUser::login)
						.collect(Collectors.toSet())
		);
	}

	public static List<ActualOrgMember> orgMembers(
			List<SimpleUser> admins,
			List<SimpleUser> members
	) {
		var result = new ArrayList<ActualOrgMember>();
		for (SimpleUser user : admins) {
			result.add(new ActualOrgMember(user.login(), "admin"));
		}
		for (SimpleUser user : members) {
			result.add(new ActualOrgMember(user.login(), "member"));
		}
		return result;
	}

	// ─── Code security configurations
	// ──────────────────────────────────────────────────────────

	/**
	 * The seventeen toggles land in a map keyed by GitHub's field name; a
	 * toggle the response omits reads as {@code not_set}, which is what GitHub
	 * means by leaving it out. The attached repositories are the ones whose
	 * attachment is in place or under way — a repository GitHub is still
	 * detaching or failed to attach is not one the configuration covers.
	 *
	 * @param defaultForNewRepos from the defaults listing; {@code none} when
	 *                           the configuration is not a default
	 */
	public static ActualCodeSecurityConfiguration codeSecurityConfiguration(
			CodeSecurityConfigurationResponse response,
			String defaultForNewRepos,
			List<CodeSecurityRepositoryResponse> repositories
	) {
		var settings = new LinkedHashMap<String, String>();
		settings.put("advanced_security", response.advancedSecurity());
		settings.put("dependency_graph", response.dependencyGraph());
		settings.put(
				"dependency_graph_autosubmit_action",
				response.dependencyGraphAutosubmitAction()
		);
		settings.put("dependabot_alerts", response.dependabotAlerts());
		settings.put(
				"dependabot_security_updates",
				response.dependabotSecurityUpdates()
		);
		settings.put(
				"dependabot_delegated_alert_dismissal",
				response.dependabotDelegatedAlertDismissal()
		);
		settings.put(
				"code_scanning_default_setup",
				response.codeScanningDefaultSetup()
		);
		settings.put(
				"code_scanning_delegated_alert_dismissal",
				response.codeScanningDelegatedAlertDismissal()
		);
		settings.put("secret_scanning", response.secretScanning());
		settings.put(
				"secret_scanning_push_protection",
				response.secretScanningPushProtection()
		);
		settings.put(
				"secret_scanning_delegated_bypass",
				response.secretScanningDelegatedBypass()
		);
		settings.put(
				"secret_scanning_validity_checks",
				response.secretScanningValidityChecks()
		);
		settings.put(
				"secret_scanning_non_provider_patterns",
				response.secretScanningNonProviderPatterns()
		);
		settings.put(
				"secret_scanning_generic_secrets",
				response.secretScanningGenericSecrets()
		);
		settings.put(
				"secret_scanning_delegated_alert_dismissal",
				response.secretScanningDelegatedAlertDismissal()
		);
		settings.put(
				"private_vulnerability_reporting",
				response.privateVulnerabilityReporting()
		);
		settings.replaceAll((_, value) -> value == null ? "not_set" : value);
		var runner = response.codeScanningDefaultSetupOptions();
		var autosubmit = response.dependencyGraphAutosubmitActionOptions();
		var codeScanning = response.codeScanningOptions();
		return new ActualCodeSecurityConfiguration(
				response.id(),
				response.name(),
				response.description() == null ? "" : response.description(),
				settings,
				response.enforcement(),
				autosubmit != null
						&& Boolean.TRUE.equals(autosubmit.labeledRunners()),
				runner == null || runner.runnerType() == null ? "not_set"
						: runner.runnerType(),
				runner == null ? null : runner.runnerLabel(),
				codeScanning == null ? null : codeScanning.allowAdvanced(),
				bypassReviewers(
						response.secretScanningDelegatedBypassOptions()
				),
				defaultForNewRepos == null ? "none" : defaultForNewRepos,
				repositories.stream()
						.filter(
								r -> r.repository() != null
										&& r.repository().name() != null
										&& ATTACHED.contains(r.status())
						)
						.map(r -> r.repository().name())
						.collect(Collectors.toSet())
		);
	}

	private static final Set<String> ATTACHED = Set
			.of("attached", "attaching", "enforced", "updating");

	/**
	 * A reviewer without a {@code mode} predates the field; the schema's
	 * default for it is {@code ALWAYS}.
	 */
	private static Set<ActualCodeSecurityConfiguration.BypassReviewer> bypassReviewers(
			CodeSecurityConfigurationResponse.SecretScanningDelegatedBypassOptions options
	) {
		if (options == null || options.reviewers() == null) {
			return Set.of();
		}
		return options.reviewers()
				.stream()
				.map(
						r -> new ActualCodeSecurityConfiguration.BypassReviewer(
								r.reviewerType(),
								r.reviewerId(),
								r.mode() == null ? "ALWAYS" : r.mode()
						)
				)
				.collect(Collectors.toSet());
	}

	// ─── Custom properties
	// ──────────────────────────────────────────────────────────

	/**
	 * {@code default_value} is a list for a {@code multi_select} property and a
	 * string otherwise; each lands in its own field. A null description reads
	 * as {@code ""}, and the allowed values as an empty list when absent.
	 */
	public static ActualCustomProperty customProperty(
			CustomPropertyResponse response
	) {
		return new ActualCustomProperty(
				response.propertyName(),
				response.valueType(),
				Boolean.TRUE.equals(response.required()),
				stringValue(response.defaultValue()),
				listValue(response.defaultValue()),
				response.description() == null ? "" : response.description(),
				response.allowedValues() == null ? List.of()
						: response.allowedValues(),
				response.valuesEditableBy()
		);
	}

	/** Same split: a list value is a {@code multi_select} property's. */
	public static ActualCustomPropertyValue customPropertyValue(
			CustomPropertyValueResponse response
	) {
		return new ActualCustomPropertyValue(
				response.propertyName(),
				stringValue(response.value()),
				listValue(response.value())
		);
	}

	private static String stringValue(Object value) {
		return value instanceof String s ? s : null;
	}

	private static List<String> listValue(Object value) {
		return value instanceof List<?> list
				? list.stream().map(String::valueOf).toList()
				: List.of();
	}

	public static ActualVariable variable(VariableResponse response) {
		return new ActualVariable(response.name(), response.value());
	}

	public static ActualOrgVariable orgVariable(
			OrgVariableResponse response,
			List<String> selectedRepositories
	) {
		return new ActualOrgVariable(
				response.name(),
				response.value(),
				response.visibility(),
				selectedRepositories
		);
	}

	public static ActualWorkflowPermissions workflowPermissions(
			WorkflowPermissions response
	) {
		return new ActualWorkflowPermissions(
				response.defaultWorkflowPermissions(),
				response.canApprovePullRequestReviews()
		);
	}

	// ─── Organizations
	// ──────────────────────────────────────────────────────────

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
				flag(response.hasRepositoryProjects()),
				text(response.defaultRepositoryPermission()),
				flag(response.membersCanCreateRepositories()),
				flag(response.membersCanCreatePublicRepositories()),
				flag(response.membersCanCreatePrivateRepositories()),
				flag(response.membersCanCreateInternalRepositories()),
				flag(response.membersCanCreatePages()),
				flag(response.membersCanCreatePublicPages()),
				flag(response.membersCanCreatePrivatePages()),
				flag(response.membersCanForkPrivateRepositories()),
				flag(response.webCommitSignoffRequired()),
				flag(response.deployKeysEnabledForRepositories()),
				text(response.defaultRepositoryBranch()),
				flag(response.twoFactorRequirementEnabled()),
				flag(response.membersCanDeleteRepositories()),
				flag(response.membersCanChangeRepoVisibility()),
				flag(response.membersCanInviteOutsideCollaborators()),
				flag(response.membersCanDeleteIssues()),
				flag(response.membersCanCreateTeams()),
				flag(response.membersCanViewDependencyInsights()),
				flag(response.readersCanCreateDiscussions()),
				flag(response.displayCommenterFullNameSettingEnabled())
		);
	}

	private static String text(String value) {
		return value == null ? "" : value;
	}

	private static boolean flag(Boolean value) {
		return value != null && value;
	}

	public static ActualOrgActionsPermissions orgActionsPermissions(
			OrgActionsPermissionsResponse response,
			SelectedActions selected
	) {
		return orgActionsPermissions(response, selected, List.of());
	}

	/**
	 * @param selectedRepositories the names Actions is enabled in, read only
	 *                             under {@code selected}
	 */
	public static ActualOrgActionsPermissions orgActionsPermissions(
			OrgActionsPermissionsResponse response,
			SelectedActions selected,
			List<String> selectedRepositories
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
						),
				selectedRepositories
		);
	}

	/**
	 * @param selectedRepositories the names the group is visible to, read only
	 *                             under {@code selected} visibility
	 */
	public static ActualRunnerGroup runnerGroup(
			RunnerGroupResponse response,
			List<String> selectedRepositories
	) {
		return new ActualRunnerGroup(
				response.id(),
				response.name(),
				response.visibility(),
				Boolean.TRUE.equals(response.isDefault()),
				Boolean.TRUE.equals(response.allowsPublicRepositories()),
				Boolean.TRUE.equals(response.restrictedToWorkflows()),
				new HashSet<>(response.selectedWorkflows()),
				selectedRepositories
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

}
