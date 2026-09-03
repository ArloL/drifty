package io.github.arlol.githubcheck.actual;

import io.github.arlol.githubcheck.client.WorkflowPermissions.DefaultWorkflowPermissions;

/**
 * The default {@code GITHUB_TOKEN} permissions for workflows, as set on the
 * repository. The enum is the client's: it spells GitHub's contract values and
 * {@code PklTypes} maps the config onto the same ones.
 */
public record ActualWorkflowPermissions(
		DefaultWorkflowPermissions defaultWorkflowPermissions,
		boolean canApprovePullRequestReviews
) {
}
