package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import io.github.arlol.githubcheck.config.RulePatternArgs;

@JsonTypeInfo(
		use = JsonTypeInfo.Id.NAME,
		property = "type",
		defaultImpl = Rule.Unknown.class,
		visible = true
)
// @formatter:off
@JsonSubTypes({
	@JsonSubTypes.Type(value = Rule.RequiredLinearHistory.class, name = "required_linear_history"),
	@JsonSubTypes.Type(value = Rule.NonFastForward.class, name = "non_fast_forward"),
	@JsonSubTypes.Type(value = Rule.RequiredStatusChecks.class, name = "required_status_checks"),
	@JsonSubTypes.Type(value = Rule.PullRequest.class, name = "pull_request"),
	@JsonSubTypes.Type(value = Rule.CodeScanning.class, name = "code_scanning"),
	@JsonSubTypes.Type(value = Rule.Creation.class, name = "creation"),
	@JsonSubTypes.Type(value = Rule.Update.class, name = "update"),
	@JsonSubTypes.Type(value = Rule.Deletion.class, name = "deletion"),
	@JsonSubTypes.Type(value = Rule.RequiredSignatures.class, name = "required_signatures"),
	@JsonSubTypes.Type(value = Rule.CommitMessagePattern.class, name = "commit_message_pattern"),
	@JsonSubTypes.Type(value = Rule.CommitAuthorEmailPattern.class, name = "commit_author_email_pattern"),
	@JsonSubTypes.Type(value = Rule.CommitterEmailPattern.class, name = "committer_email_pattern"),
	@JsonSubTypes.Type(value = Rule.BranchNamePattern.class, name = "branch_name_pattern"),
	@JsonSubTypes.Type(value = Rule.TagNamePattern.class, name = "tag_name_pattern"),
	@JsonSubTypes.Type(value = Rule.RequiredDeployments.class, name = "required_deployments"),
})
// @formatter:on
public sealed interface Rule
		permits Rule.RequiredLinearHistory, Rule.NonFastForward,
		Rule.RequiredStatusChecks, Rule.PullRequest, Rule.CodeScanning,
		Rule.Creation, Rule.Update, Rule.Deletion, Rule.RequiredSignatures,
		Rule.CommitMessagePattern, Rule.CommitAuthorEmailPattern,
		Rule.CommitterEmailPattern, Rule.BranchNamePattern, Rule.TagNamePattern,
		Rule.RequiredDeployments, Rule.Unknown {

	@JsonIgnore
	RulesetRuleType type();

	record RequiredLinearHistory(
	) implements Rule {

		public RulesetRuleType type() {
			return RulesetRuleType.REQUIRED_LINEAR_HISTORY;
		}

	}

	record NonFastForward(
	) implements Rule {

		public RulesetRuleType type() {
			return RulesetRuleType.NON_FAST_FORWARD;
		}

	}

	record RequiredStatusChecks(
			Parameters parameters
	) implements Rule {

		public RulesetRuleType type() {
			return RulesetRuleType.REQUIRED_STATUS_CHECKS;
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record Parameters(
				List<StatusCheck> requiredStatusChecks,
				Boolean strictRequiredStatusChecksPolicy
		) {

			public Parameters {
				requiredStatusChecks = requiredStatusChecks == null ? null
						: List.copyOf(requiredStatusChecks);
			}

		}

	}

	record PullRequest(
			Parameters parameters
	) implements Rule {

		public RulesetRuleType type() {
			return RulesetRuleType.PULL_REQUEST;
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record Parameters(
				Integer requiredApprovingReviewCount,
				Boolean dismissStaleReviewsOnPush,
				Boolean requireCodeOwnerReview,
				Boolean requireLastPushApproval
		) {
		}

	}

	record CodeScanning(
			Parameters parameters
	) implements Rule {

		public RulesetRuleType type() {
			return RulesetRuleType.CODE_SCANNING;
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record Parameters(
				List<CodeScanningTool> codeScanningTools
		) {

			public Parameters {
				codeScanningTools = codeScanningTools == null ? null
						: List.copyOf(codeScanningTools);
			}

		}

	}

	record Creation(
	) implements Rule {

		public RulesetRuleType type() {
			return RulesetRuleType.CREATION;
		}

	}

	record Deletion(
	) implements Rule {

		public RulesetRuleType type() {
			return RulesetRuleType.DELETION;
		}

	}

	record RequiredSignatures(
	) implements Rule {

		public RulesetRuleType type() {
			return RulesetRuleType.REQUIRED_SIGNATURES;
		}

	}

	record Update(
			Parameters parameters
	) implements Rule {

		public RulesetRuleType type() {
			return RulesetRuleType.UPDATE;
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record Parameters(
				Boolean updateAllowsFetchAndMerge
		) {
		}

	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record PatternParameters(
			String name,
			Boolean negate,
			RulePatternArgs.PatternOperator operator,
			String pattern
	) {
	}

	record CommitMessagePattern(
			PatternParameters parameters
	) implements Rule {

		public RulesetRuleType type() {
			return RulesetRuleType.COMMIT_MESSAGE_PATTERN;
		}

	}

	record CommitAuthorEmailPattern(
			PatternParameters parameters
	) implements Rule {

		public RulesetRuleType type() {
			return RulesetRuleType.COMMIT_AUTHOR_EMAIL_PATTERN;
		}

	}

	record CommitterEmailPattern(
			PatternParameters parameters
	) implements Rule {

		public RulesetRuleType type() {
			return RulesetRuleType.COMMITTER_EMAIL_PATTERN;
		}

	}

	record BranchNamePattern(
			PatternParameters parameters
	) implements Rule {

		public RulesetRuleType type() {
			return RulesetRuleType.BRANCH_NAME_PATTERN;
		}

	}

	record TagNamePattern(
			PatternParameters parameters
	) implements Rule {

		public RulesetRuleType type() {
			return RulesetRuleType.TAG_NAME_PATTERN;
		}

	}

	record RequiredDeployments(
			Parameters parameters
	) implements Rule {

		public RulesetRuleType type() {
			return RulesetRuleType.REQUIRED_DEPLOYMENTS;
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record Parameters(
				List<String> requiredDeploymentEnvironments
		) {

			public Parameters {
				requiredDeploymentEnvironments = requiredDeploymentEnvironments == null
						? null
						: List.copyOf(requiredDeploymentEnvironments);
			}

		}

	}

	record Unknown(
			@JsonProperty("type") String rawType
	) implements Rule {

		public RulesetRuleType type() {
			return null;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record StatusCheck(
			String context,
			Integer integrationId
	) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record CodeScanningTool(
			String tool,
			AlertsThreshold alertsThreshold,
			SecurityAlertsThreshold securityAlertsThreshold
	) {
	}

	enum AlertsThreshold {
		@JsonProperty("none")
		NONE, @JsonProperty("errors")
		ERRORS, @JsonProperty("errors_and_warnings")
		ERRORS_AND_WARNINGS, @JsonProperty("all")
		ALL
	}

	enum SecurityAlertsThreshold {
		@JsonProperty("none")
		NONE, @JsonProperty("critical")
		CRITICAL, @JsonProperty("high_or_higher")
		HIGH_OR_HIGHER, @JsonProperty("medium_or_higher")
		MEDIUM_OR_HIGHER, @JsonProperty("all")
		ALL
	}

}
