package io.github.arlol.githubcheck.client;

import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record PagesResponse(
		String url,
		String status,
		String cname,
		@JsonProperty("custom_404") boolean custom404,
		String htmlUrl,
		// Absent in legacy Pages responses that predate the build_type field.
		BuildType buildType,
		Source source,
		@JsonProperty("public") boolean isPublic,
		String pendingDomainUnverifiedAt,
		String protectedDomainState,
		// Absent when no HTTPS certificate is configured.
		HttpsCertificate httpsCertificate,
		boolean httpsEnforced
) {

	public enum BuildType {

		WORKFLOW, LEGACY;

		@JsonCreator
		public static BuildType fromValue(String value) {
			return valueOf(value.toUpperCase(Locale.ROOT));
		}

	}

	public record Source(
			String branch,
			String path
	) {
	}

	public record HttpsCertificate(
			String state,
			String description,
			List<String> domains,
			String expiresAt
	) {

		public HttpsCertificate {
			domains = domains == null ? null : List.copyOf(domains);
		}

	}

}
