package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonProperty;

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
