package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonProperty;

@GitHubEndpoint(
		request = "PUT /repos/{owner}/{repo}/actions/permissions/workflow",
		response = "GET /repos/{owner}/{repo}/actions/permissions/workflow"
)
public record WorkflowPermissions(
		DefaultWorkflowPermissions defaultWorkflowPermissions,
		boolean canApprovePullRequestReviews
) {

	public enum DefaultWorkflowPermissions {

		@JsonProperty("read")
		READ,

		@JsonProperty("write")
		WRITE

	}

}
