package io.github.arlol.githubcheck.client;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;

public record SecurityAndAnalysis(
		StatusObject secretScanning,
		StatusObject secretScanningPushProtection,
		StatusObject advancedSecurity,
		StatusObject dependabotSecurityUpdates,
		StatusObject codeSecurity,
		StatusObject secretScanningNonProviderPatterns,
		StatusObject secretScanningAiDetection,
		StatusObject secretScanningDelegatedAlertDismissal,
		StatusObject secretScanningDelegatedBypass
) {

	public record StatusObject(
			Status status
	) {

		public enum Status {

			ENABLED, DISABLED;

			@JsonCreator
			public static Status fromValue(String value) {
				return valueOf(value.toUpperCase(Locale.ROOT));
			}

		}

	}

}
