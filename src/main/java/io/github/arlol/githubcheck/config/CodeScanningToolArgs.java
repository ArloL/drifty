package io.github.arlol.githubcheck.config;

import java.util.Objects;

import io.github.arlol.githubcheck.client.Rule;

public final class CodeScanningToolArgs {

	private final String tool;
	private final Rule.AlertsThreshold alertsThreshold;
	private final Rule.SecurityAlertsThreshold securityAlertsThreshold;

	private CodeScanningToolArgs(Builder builder) {
		this.tool = builder.tool;
		this.alertsThreshold = builder.alertsThreshold;
		this.securityAlertsThreshold = builder.securityAlertsThreshold;
	}

	public String tool() {
		return tool;
	}

	public Rule.AlertsThreshold alertsThreshold() {
		return alertsThreshold;
	}

	public Rule.SecurityAlertsThreshold securityAlertsThreshold() {
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

	public static final class Builder {

		private String tool;
		private Rule.AlertsThreshold alertsThreshold = Rule.AlertsThreshold.NONE;
		private Rule.SecurityAlertsThreshold securityAlertsThreshold = Rule.SecurityAlertsThreshold.NONE;

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

		public Builder alertsThreshold(Rule.AlertsThreshold alertsThreshold) {
			this.alertsThreshold = alertsThreshold;
			return this;
		}

		public Builder securityAlertsThreshold(
				Rule.SecurityAlertsThreshold securityAlertsThreshold
		) {
			this.securityAlertsThreshold = securityAlertsThreshold;
			return this;
		}

		public CodeScanningToolArgs build() {
			return new CodeScanningToolArgs(this);
		}

	}

}
