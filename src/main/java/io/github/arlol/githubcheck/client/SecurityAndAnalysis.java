package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SecurityAndAnalysis(
		StatusObject secretScanning,
		StatusObject secretScanningPushProtection,
		StatusObject advancedSecurity,
		StatusObject dependabotSecurityUpdates,
		StatusObject codeSecurity,
		StatusObject secretScanningNonProviderPatterns,
		StatusObject secretScanningValidityChecks,
		StatusObject secretScanningAiDetection,
		StatusObject secretScanningDelegatedAlertDismissal,
		StatusObject secretScanningDelegatedBypass
) {

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private StatusObject secretScanning;
		private StatusObject secretScanningPushProtection;
		private StatusObject advancedSecurity;
		private StatusObject dependabotSecurityUpdates;
		private StatusObject codeSecurity;
		private StatusObject secretScanningNonProviderPatterns;
		private StatusObject secretScanningValidityChecks;
		private StatusObject secretScanningAiDetection;
		private StatusObject secretScanningDelegatedAlertDismissal;
		private StatusObject secretScanningDelegatedBypass;

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
					secretScanningDelegatedBypass
			);
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
