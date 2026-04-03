package io.github.arlol.githubcheck.client;

import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record PagesResponse(
		String url,
		Status status,
		String cname,
		@JsonProperty("custom_404") boolean custom404,
		String htmlUrl,
		// Absent in legacy Pages responses that predate the build_type field.
		BuildType buildType,
		Source source,
		@JsonProperty("public") boolean isPublic,
		String pendingDomainUnverifiedAt,
		ProtectedDomainState protectedDomainState,
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

	public enum Status {
		@JsonProperty("built")
		BUILT, @JsonProperty("building")
		BUILDING, @JsonProperty("errored")
		ERRORED
	}

	public enum ProtectedDomainState {
		@JsonProperty("pending")
		PENDING, @JsonProperty("verified")
		VERIFIED, @JsonProperty("unverified")
		UNVERIFIED
	}

	public record Source(
			String branch,
			String path
	) {
	}

	public record HttpsCertificate(
			State state,
			String description,
			List<String> domains,
			String expiresAt
	) {

		public HttpsCertificate {
			domains = domains == null ? null : List.copyOf(domains);
		}

		public enum State {
			@JsonProperty("new")
			NEW, @JsonProperty("authorization_created")
			AUTHORIZATION_CREATED, @JsonProperty("authorization_pending")
			AUTHORIZATION_PENDING, @JsonProperty("authorized")
			AUTHORIZED, @JsonProperty("authorization_revoked")
			AUTHORIZATION_REVOKED, @JsonProperty("issued")
			ISSUED, @JsonProperty("uploaded")
			UPLOADED, @JsonProperty("approved")
			APPROVED, @JsonProperty("errored")
			ERRORED, @JsonProperty("bad_authz")
			BAD_AUTHZ, @JsonProperty("destroy_pending")
			DESTROY_PENDING, @JsonProperty("dns_changed")
			DNS_CHANGED
		}

	}

}
