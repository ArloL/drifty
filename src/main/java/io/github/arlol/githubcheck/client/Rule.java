package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
		use = JsonTypeInfo.Id.NAME,
		property = "type",
		defaultImpl = Rule.Unknown.class,
		visible = true
)
@JsonSubTypes(
	{ @JsonSubTypes.Type(
			value = Rule.RequiredLinearHistory.class,
			name = "required_linear_history"
	), @JsonSubTypes.Type(value = Rule.NonFastForward.class, name = "non_fast_forward"), @JsonSubTypes.Type(value = Rule.RequiredStatusChecks.class, name = "required_status_checks"), @JsonSubTypes.Type(value = Rule.PullRequest.class, name = "pull_request"), @JsonSubTypes.Type(value = Rule.CodeScanning.class, name = "code_scanning"), }
)
public sealed interface Rule permits Rule.RequiredLinearHistory,
		Rule.NonFastForward, Rule.RequiredStatusChecks, Rule.PullRequest,
		Rule.CodeScanning, Rule.Unknown {

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
			String alertsThreshold,
			String securityAlertsThreshold
	) {
	}

}
