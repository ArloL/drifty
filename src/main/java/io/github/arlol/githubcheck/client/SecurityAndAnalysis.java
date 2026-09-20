package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SecurityAndAnalysis(
		@Nullable StatusObject secretScanning,
		@Nullable StatusObject secretScanningPushProtection,
		@Nullable StatusObject advancedSecurity,
		@Nullable StatusObject dependabotSecurityUpdates,
		@Nullable StatusObject codeSecurity,
		@Nullable StatusObject secretScanningNonProviderPatterns,
		@Nullable StatusObject secretScanningValidityChecks,
		@Nullable StatusObject secretScanningAiDetection,
		@Nullable StatusObject secretScanningDelegatedAlertDismissal,
		@Nullable StatusObject secretScanningDelegatedBypass,
		@Nullable DelegatedBypassOptions secretScanningDelegatedBypassOptions
) {

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Whether a security toggle is on. GitHub omits the whole block for some
	 * repositories and individual toggles for others, and both mean "off" —
	 * which is why this is the single place that decides it.
	 */
	public static boolean isEnabled(@Nullable StatusObject statusObject) {
		return statusObject != null
				&& statusObject.status() == StatusObject.Status.ENABLED;
	}

	public static final class Builder {

		private @Nullable StatusObject secretScanning;
		private @Nullable StatusObject secretScanningPushProtection;
		private @Nullable StatusObject advancedSecurity;
		private @Nullable StatusObject dependabotSecurityUpdates;
		private @Nullable StatusObject codeSecurity;
		private @Nullable StatusObject secretScanningNonProviderPatterns;
		private @Nullable StatusObject secretScanningValidityChecks;
		private @Nullable StatusObject secretScanningAiDetection;
		private @Nullable StatusObject secretScanningDelegatedAlertDismissal;
		private @Nullable StatusObject secretScanningDelegatedBypass;
		private @Nullable DelegatedBypassOptions secretScanningDelegatedBypassOptions;

		private Builder() {
		}

		public Builder secretScanning(boolean enabled) {
			this.secretScanning = StatusObject.of(enabled);
			return this;
		}

		public Builder secretScanningPushProtection(boolean enabled) {
			this.secretScanningPushProtection = StatusObject.of(enabled);
			return this;
		}

		public Builder advancedSecurity(boolean enabled) {
			this.advancedSecurity = StatusObject.of(enabled);
			return this;
		}

		public Builder dependabotSecurityUpdates(boolean enabled) {
			this.dependabotSecurityUpdates = StatusObject.of(enabled);
			return this;
		}

		public Builder codeSecurity(boolean enabled) {
			this.codeSecurity = StatusObject.of(enabled);
			return this;
		}

		public Builder secretScanningNonProviderPatterns(boolean enabled) {
			this.secretScanningNonProviderPatterns = StatusObject.of(enabled);
			return this;
		}

		public Builder secretScanningValidityChecks(boolean enabled) {
			this.secretScanningValidityChecks = StatusObject.of(enabled);
			return this;
		}

		public Builder secretScanningAiDetection(boolean enabled) {
			this.secretScanningAiDetection = StatusObject.of(enabled);
			return this;
		}

		public Builder secretScanningDelegatedAlertDismissal(boolean enabled) {
			this.secretScanningDelegatedAlertDismissal = StatusObject
					.of(enabled);
			return this;
		}

		public Builder secretScanningDelegatedBypass(boolean enabled) {
			this.secretScanningDelegatedBypass = StatusObject.of(enabled);
			return this;
		}

		public Builder secretScanningDelegatedBypassReviewers(
				List<BypassReviewer> reviewers
		) {
			this.secretScanningDelegatedBypassOptions = new DelegatedBypassOptions(
					reviewers
			);
			return this;
		}

		public SecurityAndAnalysis build() {
			return new SecurityAndAnalysis(
					secretScanning,
					secretScanningPushProtection,
					advancedSecurity,
					dependabotSecurityUpdates,
					codeSecurity,
					secretScanningNonProviderPatterns,
					secretScanningValidityChecks,
					secretScanningAiDetection,
					secretScanningDelegatedAlertDismissal,
					secretScanningDelegatedBypass,
					secretScanningDelegatedBypassOptions
			);
		}

	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record DelegatedBypassOptions(
			List<BypassReviewer> reviewers
	) {

		public DelegatedBypassOptions {
			reviewers = reviewers == null ? null : List.copyOf(reviewers);
		}

	}

	public record BypassReviewer(
			Long reviewerId,
			ReviewerType reviewerType
	) {

		public enum ReviewerType {
			TEAM, ROLE
		}

	}

	public record StatusObject(
			Status status
	) {

		public static StatusObject of(boolean enabled) {
			return new StatusObject(enabled ? Status.ENABLED : Status.DISABLED);
		}

		public enum Status {

			@JsonProperty("enabled")
			ENABLED,

			@JsonProperty("disabled")
			DISABLED

		}

	}

}
