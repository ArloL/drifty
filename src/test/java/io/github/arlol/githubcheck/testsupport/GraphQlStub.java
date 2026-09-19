package io.github.arlol.githubcheck.testsupport;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.client.MappingBuilder;

/**
 * The one query {@code fetchState} sends for rulesets, branch protections,
 * collaborators and vulnerability alerts, answered with nothing.
 * <p>
 * Most tests care about some other group and only need this not to fail, so
 * they stub the four sections empty; the ones that are about these groups
 * answer with their own payload.
 */
public final class GraphQlStub {

	private GraphQlStub() {
	}

	/**
	 * No rulesets, no protections, no collaborators, and vulnerability alerts
	 * on — which is where GitHub leaves a repository nobody has configured, and
	 * what the four requests this replaced used to answer.
	 */
	public static MappingBuilder atDefaults() {
		return answering(sections(null, null, null, true));
	}

	public static MappingBuilder answering(String body) {
		return post(urlPathEqualTo("/graphql")).willReturn(okJson(body));
	}

	/**
	 * The same, for one repository only — the query names it in its body, which
	 * is the only place it appears.
	 */
	public static MappingBuilder answeringFor(String repository, String body) {
		return answering(body).withRequestBody(
				containing("name: \\\"" + repository + "\\\"")
		);
	}

	/**
	 * The four sections with only the ones named filled in. Each argument is
	 * the section's JSON body, or null for empty.
	 */
	public static String sections(
			String rulesets,
			String branchProtections,
			String collaborators,
			boolean vulnerabilityAlerts
	) {
		return """
				{"data": {
				  "rs": {"rulesets": {"nodes": [%s]}},
				  "bp": {"branchProtectionRules": {"nodes": [%s]}},
				  "co": {"collaborators": {"edges": [%s]}},
				  "va": {"hasVulnerabilityAlertsEnabled": %s}
				}}
				""".formatted(
				rulesets == null ? "" : rulesets,
				branchProtections == null ? "" : branchProtections,
				collaborators == null ? "" : collaborators,
				vulnerabilityAlerts
		);
	}

}
