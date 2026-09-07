package io.github.arlol.githubcheck.export;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import io.github.arlol.githubcheck.actual.ActualRuleset;
import io.github.arlol.githubcheck.actual.StatusCheck;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * A ruleset — repository or organization — as the config lines that differ from
 * the schema's defaults.
 * <p>
 * A repository ruleset and an organization ruleset share every rule but the
 * repository-selection conditions, the way {@code RulesetComparison} serves
 * both drift groups; {@code orgScope} picks {@code defaults.orgRuleset()} over
 * {@code defaults.ruleset()} and switches on the five extra fields.
 * <p>
 * Three things GitHub reports cannot be written back faithfully: a rule
 * pattern's operator and negate flag are gone by the time {@code ActualTypes}
 * flattens it to plain text, and a code scanning tool's alert thresholds never
 * reach {@link ActualRuleset} at all. Both become a note instead of an invented
 * value.
 */
public final class RulesetExporter {

	private static final String CODE_SCANNING_THRESHOLDS_NOTE = "code scanning alert thresholds are not read back; drifty compares the tool names only";

	private RulesetExporter() {
	}

	public static PklNode.Member entry(
			ActualRuleset actual,
			SchemaDefaults defaults,
			boolean orgScope
	) {
		Drifty.Ruleset base = orgScope ? defaults.orgRuleset()
				: defaults.ruleset();

		List<PklNode.Member> members = new ArrayList<>(
				Fields.members(
						Fields.field(
								"target",
								actual.target(),
								base.target.toString()
						),
						Fields.field(
								"enforcement",
								actual.enforcement(),
								base.enforcement.toString()
						)
				)
		);
		// The schema refuses these on a push or repository ruleset, so the
		// guard is on target rather than on whether the sets happen to be
		// empty.
		if (hasRefConditions(actual.target())) {
			members.addAll(
					Fields.members(
							Fields.strings(
									"includePatterns",
									actual.includePatterns(),
									base.includePatterns
							),
							Fields.strings(
									"excludePatterns",
									actual.excludePatterns(),
									base.excludePatterns
							)
					)
			);
		}
		members.addAll(
				Fields.members(
						Fields.field(
								"creation",
								actual.creation(),
								base.creation
						),
						Fields.field(
								"deletion",
								actual.deletion(),
								base.deletion
						),
						Fields.field("update", actual.update(), base.update),
						Fields.field(
								"updateAllowsFetchAndMerge",
								actual.updateAllowsFetchAndMerge(),
								base.updateAllowsFetchAndMerge
						),
						Fields.field(
								"requiredSignatures",
								actual.requiredSignatures(),
								base.requiredSignatures
						),
						Fields.field(
								"requiredLinearHistory",
								actual.requiredLinearHistory(),
								base.requiredLinearHistory
						),
						Fields.field(
								"noForcePushes",
								actual.noForcePushes(),
								base.noForcePushes
						),
						Fields.field(
								"strictRequiredStatusChecks",
								actual.strictRequiredStatusChecks(),
								base.strictRequiredStatusChecks
						),
						Fields.strings(
								"requiredDeployments",
								actual.requiredDeployments(),
								base.requiredDeployments
						),
						Fields.strings(
								"filePathRestrictions",
								actual.filePathRestrictions(),
								base.filePathRestrictions
						),
						Fields.field(
								"maxFilePathLength",
								actual.maxFilePathLength(),
								toInteger(base.maxFilePathLength)
						),
						Fields.strings(
								"fileExtensionRestrictions",
								actual.fileExtensionRestrictions(),
								base.fileExtensionRestrictions
						),
						Fields.field(
								"maxFileSize",
								actual.maxFileSize(),
								toInteger(base.maxFileSize)
						),
						Fields.objects(
								"requiredStatusChecks",
								statusChecks(actual.requiredStatusChecks())
						),
						Fields.objects(
								"requiredCodeScanning",
								codeScanning(actual.requiredCodeScanningTools())
						),
						Fields.objects(
								"workflows",
								workflows(actual.workflows())
						),
						Fields.objects(
								"bypassActors",
								bypassActors(actual.bypassActors())
						)
				)
		);
		if (!actual.requiredCodeScanningTools().isEmpty()) {
			members.add(Fields.note(CODE_SCANNING_THRESHOLDS_NOTE));
		}
		// Presence is the setting: an empty pullRequest still requires pull
		// requests before merging, so it is written even when nothing inside
		// it drifted, unlike Fields.nested's omit-when-empty.
		if (actual.pullRequest() != null) {
			members.add(
					pullRequest(
							actual.pullRequest(),
							defaults.pullRequestRule()
					)
			);
		}
		if (actual.mergeQueue() != null) {
			members.add(
					mergeQueue(actual.mergeQueue(), defaults.mergeQueueRule())
			);
		}
		if (orgScope) {
			// A second call rather than casting base: base is declared
			// Ruleset even when orgScope is true, since the base fields above
			// only need what Ruleset itself has.
			members.addAll(orgOnlyFields(defaults.orgRuleset(), actual));
		}
		members.addAll(patternNotes(actual));

		return new PklNode.Field(actual.name(), new PklNode.Obj(members));
	}

	private static boolean hasRefConditions(String target) {
		return "branch".equals(target) || "tag".equals(target);
	}

	/** The schema leaves both fields unset by default, i.e. null. */
	private static Integer toInteger(Long value) {
		return value == null ? null : value.intValue();
	}

	private static List<PklNode.Member> orgOnlyFields(
			Drifty.OrgRuleset base,
			ActualRuleset actual
	) {
		return Fields.members(
				Fields.strings(
						"repositoryNameInclude",
						actual.repositoryNameInclude(),
						base.repositoryNameInclude
				),
				Fields.strings(
						"repositoryNameExclude",
						actual.repositoryNameExclude(),
						base.repositoryNameExclude
				),
				Fields.field(
						"repositoryNameProtected",
						actual.repositoryNameProtected(),
						base.repositoryNameProtected
				),
				Fields.objects(
						"repositoryPropertyInclude",
						propertyConditions(actual.repositoryPropertyInclude())
				),
				Fields.objects(
						"repositoryPropertyExclude",
						propertyConditions(actual.repositoryPropertyExclude())
				)
		);
	}

	private static List<PklNode> statusChecks(Set<StatusCheck> checks) {
		return checks.stream()
				.sorted(Comparator.comparing(StatusCheck::context))
				.<PklNode>map(RulesetExporter::statusCheck)
				.toList();
	}

	/**
	 * {@code appId} keeps its schema default of null, so a check with none set
	 * omits the field rather than writing {@code appId = null}.
	 */
	private static PklNode.Obj statusCheck(StatusCheck check) {
		List<PklNode.Member> members = new ArrayList<>();
		members.add(required("context", check.context()));
		if (check.appId() != null) {
			members.add(required("appId", check.appId().longValue()));
		}
		return new PklNode.Obj(members);
	}

	/**
	 * {@code requiredCodeScanningTools} is a set of tool names — the thresholds
	 * {@code CodeScanningTool} also carries never reach {@link ActualRuleset},
	 * hence {@link #CODE_SCANNING_THRESHOLDS_NOTE} rather than a value for them
	 * here.
	 */
	private static List<PklNode> codeScanning(Set<String> tools) {
		return tools.stream()
				.sorted()
				.<PklNode>map(
						tool -> new PklNode.Obj(List.of(required("tool", tool)))
				)
				.toList();
	}

	private static PklNode.Member pullRequest(
			ActualRuleset.PullRequest actual,
			Drifty.PullRequestRule defaults
	) {
		List<PklNode.Member> members = new ArrayList<>(
				Fields.members(
						Fields.field(
								"requiredApprovingReviewCount",
								actual.requiredApprovingReviewCount(),
								defaults.requiredApprovingReviewCount
						),
						Fields.field(
								"dismissStaleReviewsOnPush",
								actual.dismissStaleReviewsOnPush(),
								defaults.dismissStaleReviewsOnPush
						),
						Fields.field(
								"requireCodeOwnerReview",
								actual.requireCodeOwnerReview(),
								defaults.requireCodeOwnerReview
						),
						Fields.field(
								"requireLastPushApproval",
								actual.requireLastPushApproval(),
								defaults.requireLastPushApproval
						),
						Fields.field(
								"requiredReviewThreadResolution",
								actual.requiredReviewThreadResolution(),
								defaults.requiredReviewThreadResolution
						),
						Fields.strings(
								"allowedMergeMethods",
								actual.allowedMergeMethods(),
								toStrings(defaults.allowedMergeMethods)
						)
				)
		);
		return new PklNode.Field("pullRequest", new PklNode.Obj(members));
	}

	private static PklNode.Member mergeQueue(
			ActualRuleset.MergeQueue actual,
			Drifty.MergeQueueRule defaults
	) {
		List<PklNode.Member> members = new ArrayList<>(
				Fields.members(
						Fields.field(
								"checkResponseTimeoutMinutes",
								actual.checkResponseTimeoutMinutes(),
								defaults.checkResponseTimeoutMinutes
						),
						Fields.field(
								"groupingStrategy",
								actual.groupingStrategy(),
								defaults.groupingStrategy.toString()
						),
						Fields.field(
								"maxEntriesToBuild",
								actual.maxEntriesToBuild(),
								defaults.maxEntriesToBuild
						),
						Fields.field(
								"maxEntriesToMerge",
								actual.maxEntriesToMerge(),
								defaults.maxEntriesToMerge
						),
						Fields.field(
								"mergeMethod",
								actual.mergeMethod(),
								defaults.mergeMethod.toString()
						),
						Fields.field(
								"minEntriesToMerge",
								actual.minEntriesToMerge(),
								defaults.minEntriesToMerge
						),
						Fields.field(
								"minEntriesToMergeWaitMinutes",
								actual.minEntriesToMergeWaitMinutes(),
								defaults.minEntriesToMergeWaitMinutes
						)
				)
		);
		return new PklNode.Field("mergeQueue", new PklNode.Obj(members));
	}

	private static List<String> toStrings(List<? extends Enum<?>> values) {
		return values.stream().map(Enum::toString).toList();
	}

	private static List<PklNode> workflows(
			Set<ActualRuleset.Workflow> workflows
	) {
		return workflows.stream()
				.sorted(Comparator.comparing(ActualRuleset.Workflow::toString))
				.<PklNode>map(RulesetExporter::workflow)
				.toList();
	}

	/**
	 * No schema default exists for a single workflow, so all three fields —
	 * {@code ref} included — are written even when null.
	 */
	private static PklNode.Obj workflow(ActualRuleset.Workflow workflow) {
		return new PklNode.Obj(
				List.of(
						required("path", workflow.path()),
						required("repositoryId", workflow.repositoryId()),
						required("ref", workflow.ref())
				)
		);
	}

	private static List<PklNode> bypassActors(
			List<ActualRuleset.BypassActor> actors
	) {
		return actors.stream()
				.sorted(
						Comparator
								.comparing(ActualRuleset.BypassActor::toString)
				)
				.<PklNode>map(RulesetExporter::bypassActor)
				.toList();
	}

	private static PklNode.Obj bypassActor(ActualRuleset.BypassActor actor) {
		return new PklNode.Obj(
				List.of(
						required("actorId", actor.actorId()),
						required("actorType", actor.actorType()),
						required("bypassMode", actor.bypassMode())
				)
		);
	}

	private static List<PklNode> propertyConditions(
			Set<ActualRuleset.PropertyCondition> conditions
	) {
		return conditions.stream()
				.sorted(
						Comparator.comparing(
								ActualRuleset.PropertyCondition::toString
						)
				)
				.<PklNode>map(RulesetExporter::propertyCondition)
				.toList();
	}

	/**
	 * {@code OrgRulesetDriftGroup} compares a property condition as one opaque
	 * string rather than field by field, so {@code source} is written
	 * unconditionally alongside {@code name} and {@code propertyValues} rather
	 * than diffed against its own schema default.
	 */
	private static PklNode.Obj propertyCondition(
			ActualRuleset.PropertyCondition condition
	) {
		return new PklNode.Obj(
				List.of(
						required("name", condition.name()),
						requiredStrings(
								"propertyValues",
								condition.propertyValues()
						),
						required("source", condition.source())
				)
		);
	}

	private static List<PklNode.Member> patternNotes(ActualRuleset actual) {
		List<PklNode.Member> notes = new ArrayList<>();
		patternNote(
				notes,
				"commitMessagePattern",
				actual.commitMessagePattern()
		);
		patternNote(
				notes,
				"commitAuthorEmailPattern",
				actual.commitAuthorEmailPattern()
		);
		patternNote(
				notes,
				"committerEmailPattern",
				actual.committerEmailPattern()
		);
		patternNote(notes, "branchNamePattern", actual.branchNamePattern());
		patternNote(notes, "tagNamePattern", actual.tagNamePattern());
		return notes;
	}

	private static void patternNote(
			List<PklNode.Member> notes,
			String field,
			String value
	) {
		if (value != null && !value.isEmpty()) {
			notes.add(
					Fields.note(
							field + " is set on GitHub; drifty compares the pattern text only, so its operator is not exported"
					)
			);
		}
	}

	/**
	 * {@link Fields#required(String, String)} always returns a value; unwrapped
	 * here so the nested-object builders above read as plain field lists.
	 */
	private static PklNode.Member required(String name, String value) {
		return Fields.required(name, value).orElseThrow();
	}

	private static PklNode.Member required(String name, long value) {
		return Fields.required(name, value).orElseThrow();
	}

	/**
	 * propertyValues has no schema default either, so — like name and source —
	 * it is written unconditionally, sorted for a reproducible file.
	 */
	private static PklNode.Member requiredStrings(
			String name,
			Set<String> values
	) {
		List<PklNode> elements = values.stream()
				.sorted()
				.<PklNode>map(PklNode.Scalar::of)
				.toList();
		return new PklNode.Field(name, new PklNode.Listing(elements));
	}

}
