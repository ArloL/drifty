package io.github.arlol.githubcheck.config;

import java.util.Objects;

public class StatusCheckArgs {

	public static final Integer APP_ID_GITHUB_ACTIONS = 15368;
	public static final Integer APP_ID_GITHUB_ADVANCED_SECURITY = 57789;

	private final String context;
	private final Integer appId;

	public StatusCheckArgs(Builder builder) {
		this.context = builder.context;
		this.appId = builder.appId;
	}

	public String getContext() {
		return context;
	}

	public Integer getAppId() {
		return appId;
	}

	public Builder toBuilder() {
		return new Builder(this);
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public String toString() {
		if (appId != null) {
			return context + ":" + appId;
		}
		return context;
	}

	@Override
	public boolean equals(Object o) {
		if (o == null || getClass() != o.getClass())
			return false;
		StatusCheckArgs that = (StatusCheckArgs) o;
		return Objects.equals(context, that.context)
				&& Objects.equals(appId, that.appId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(context, appId);
	}

	public static final class Builder {

		private String context;
		private Integer appId;

		public Builder() {
		}

		public Builder(StatusCheckArgs statusCheckArgs) {
			this.context = statusCheckArgs.context;
			this.appId = statusCheckArgs.appId;
		}

		public Builder context(String context) {
			this.context = context;
			return this;
		}

		public Builder appId(Integer appId) {
			this.appId = appId;
			return this;
		}

		public StatusCheckArgs build() {
			return new StatusCheckArgs(this);
		}

	}

}
