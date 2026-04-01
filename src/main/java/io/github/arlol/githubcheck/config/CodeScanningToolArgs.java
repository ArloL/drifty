package io.github.arlol.githubcheck.config;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class CodeScanningToolArgs {

	private final String tool;
	private final AlertsThreshold alertsThreshold;
	private final SecurityAlertsThreshold securityAlertsThreshold;

	private CodeScanningToolArgs(Builder builder) {
		this.tool = builder.tool;
		this.alertsThreshold = builder.alertsThreshold;
		this.securityAlertsThreshold = builder.securityAlertsThreshold;
	}

	public String tool() {
		return tool;
	}

	public AlertsThreshold alertsThreshold() {
		return alertsThreshold;
	}

	public SecurityAlertsThreshold securityAlertsThreshold() {
		return securityAlertsThreshold;
	}

	public Builder toBuilder() {
		return new Builder(this);
	}

	@Override
	public boolean equals(Object o) {
		if (o == null || getClass() != o.getClass())
			return false;
		CodeScanningToolArgs that = (CodeScanningToolArgs) o;
		return Objects.equals(tool, that.tool)
				&& alertsThreshold == that.alertsThreshold
				&& securityAlertsThreshold == that.securityAlertsThreshold;
	}

	@Override
	public int hashCode() {
		return Objects.hash(tool, alertsThreshold, securityAlertsThreshold);
	}

	public static Builder builder() {
		return new Builder();
	}

	public enum AlertsThreshold {
		@JsonProperty("none")
		NONE, @JsonProperty("errors")
		ERRORS, @JsonProperty("errors_and_warnings")
		ERRORS_AND_WARNINGS, @JsonProperty("all")
		ALL
	}

	public enum SecurityAlertsThreshold {
		@JsonProperty("none")
		NONE, @JsonProperty("critical")
		CRITICAL, @JsonProperty("high_or_higher")
		HIGH_OR_HIGHER, @JsonProperty("medium_or_higher")
		MEDIUM_OR_HIGHER, @JsonProperty("all")
		ALL
	}

	public static final class Builder {

		private String tool;
		private AlertsThreshold alertsThreshold = AlertsThreshold.NONE;
		private SecurityAlertsThreshold securityAlertsThreshold = SecurityAlertsThreshold.NONE;

		public Builder() {
		}

		public Builder(CodeScanningToolArgs args) {
			this.tool = args.tool;
			this.alertsThreshold = args.alertsThreshold;
			this.securityAlertsThreshold = args.securityAlertsThreshold;
		}

		public Builder tool(String tool) {
			this.tool = tool;
			return this;
		}

		public Builder alertsThreshold(AlertsThreshold alertsThreshold) {
			this.alertsThreshold = alertsThreshold;
			return this;
		}

		public Builder securityAlertsThreshold(
				SecurityAlertsThreshold securityAlertsThreshold
		) {
			this.securityAlertsThreshold = securityAlertsThreshold;
			return this;
		}

		public CodeScanningToolArgs build() {
			return new CodeScanningToolArgs(this);
		}

	}

}
