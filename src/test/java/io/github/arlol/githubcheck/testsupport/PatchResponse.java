package io.github.arlol.githubcheck.testsupport;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;

/**
 * What GitHub answers a PATCH of a repository or an organization with: the
 * whole entity, as it holds it afterwards.
 * <p>
 * The settings groups read that answer to confirm a write took (issue #203), so
 * a stub of {@code {}} no longer stands in for one: it says every field is
 * unset. {@link #applying} merges the request into the entity, which is a PATCH
 * that took; {@link #ignoring} answers the entity unchanged, which is a PATCH
 * GitHub accepted and did nothing with.
 */
public final class PatchResponse {

	/** A repository at the schema's defaults, as GET answers it. */
	public static final String REPOSITORY = """
			{
			  "id": 1,
			  "name": "repo",
			  "private": false,
			  "fork": false,
			  "archived": false,
			  "disabled": false,
			  "is_template": false,
			  "visibility": "public",
			  "default_branch": "main",
			  "description": null,
			  "homepage": null,
			  "topics": [],
			  "has_issues": true,
			  "has_projects": true,
			  "has_wiki": true,
			  "has_discussions": false,
			  "has_pages": false,
			  "allow_forking": true,
			  "web_commit_signoff_required": false,
			  "allow_squash_merge": true,
			  "allow_merge_commit": true,
			  "allow_rebase_merge": true,
			  "allow_auto_merge": false,
			  "delete_branch_on_merge": false,
			  "allow_update_branch": false,
			  "squash_merge_commit_title": "COMMIT_OR_PR_TITLE",
			  "squash_merge_commit_message": "COMMIT_MESSAGES",
			  "merge_commit_title": "MERGE_MESSAGE",
			  "merge_commit_message": "PR_TITLE"
			}
			""";

	/** An organization with nothing set, as GET answers it. */
	public static final String ORGANIZATION = """
			{"login": "my-org"}
			""";

	private PatchResponse() {
	}

	/** A PATCH of a repository GitHub applied in full. */
	public static ResponseDefinitionBuilder applyingToRepository() {
		return applying(REPOSITORY);
	}

	/** A PATCH of an organization GitHub applied in full. */
	public static ResponseDefinitionBuilder applyingToOrganization() {
		return applying(ORGANIZATION);
	}

	/** {@code entity} with every field the request sent set to what it sent. */
	public static ResponseDefinitionBuilder applying(String entity) {
		return aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json")
				.withTransformers("response-template")
				.withTransformerParameter("entity", entity)
				.withBody("{{{jsonMerge parameters.entity request.body}}}");
	}

	/** {@code entity} unchanged, whatever the request sent. */
	public static ResponseDefinitionBuilder ignoring(String entity) {
		return okJson(entity);
	}

}
