package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import io.github.arlol.githubcheck.client.MergeCommitMessage;
import io.github.arlol.githubcheck.client.MergeCommitTitle;
import io.github.arlol.githubcheck.client.RepositoryDetailsResponse;
import io.github.arlol.githubcheck.client.RepositoryVisibility;
import io.github.arlol.githubcheck.client.SquashMergeCommitMessage;
import io.github.arlol.githubcheck.client.SquashMergeCommitTitle;
import io.github.arlol.githubcheck.config.RepositoryArgs;

class RepoSettingsDriftGroupTest {

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.configure(
					DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
					false
			);

	private static RepositoryDetailsResponse parseDetails(String json) {
		try {
			return MAPPER.readValue(json, RepositoryDetailsResponse.class);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static final String BASE_DETAILS_JSON = """
			{
				"description": "A great project",
				"homepage": "",
				"has_issues": true,
				"has_projects": true,
				"has_wiki": true,
				"has_discussions": false,
				"is_template": false,
				"allow_forking": true,
				"web_commit_signoff_required": false,
				"default_branch": "main",
				"topics": [],
				"allow_merge_commit": true,
				"allow_squash_merge": true,
				"allow_rebase_merge": true,
				"allow_update_branch": false,
				"allow_auto_merge": false,
				"delete_branch_on_merge": false,
				"squash_merge_commit_title": "COMMIT_OR_PR_TITLE",
				"squash_merge_commit_message": "COMMIT_MESSAGES",
				"merge_commit_title": "MERGE_MESSAGE",
				"merge_commit_message": "PR_TITLE",
				"visibility": "public",
				"archived": false
			}
			""";

	private RepositoryArgs desired(String description) {
		return RepositoryArgs.create("owner", "repo")
				.description(description)
				.visibility(RepositoryVisibility.PUBLIC)
				.defaultBranch("main")
				.hasIssues(true)
				.hasProjects(true)
				.hasWiki(true)
				.hasDiscussions(false)
				.isTemplate(false)
				.allowForking(true)
				.webCommitSignoffRequired(false)
				.allowMergeCommit(true)
				.allowSquashMerge(true)
				.allowRebaseMerge(true)
				.allowAutoMerge(false)
				.allowUpdateBranch(false)
				.deleteBranchOnMerge(false)
				.squashMergeCommitTitle(
						SquashMergeCommitTitle.COMMIT_OR_PR_TITLE
				)
				.squashMergeCommitMessage(
						SquashMergeCommitMessage.COMMIT_MESSAGES
				)
				.mergeCommitTitle(MergeCommitTitle.MERGE_MESSAGE)
				.mergeCommitMessage(MergeCommitMessage.PR_TITLE)
				.build();
	}

	private RepositoryArgs desiredFull() {
		return desired("A great project");
	}

	private RepoSettingsDriftGroup group(
			RepositoryArgs desired,
			RepositoryDetailsResponse actual
	) {
		return new RepoSettingsDriftGroup(
				desired,
				actual,
				null,
				"owner",
				"repo"
		);
	}

	@Test
	void noDrift_allSettingsMatch() {
		var items = group(desiredFull(), parseDetails(BASE_DETAILS_JSON))
				.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();
		assertThat(items).isEmpty();
	}

	@Test
	void detectsDescriptionMismatch() {
		var items = group(
				desired("Desired description"),
				parseDetails(BASE_DETAILS_JSON)
		).detect().stream().flatMap(f -> f.items().stream()).toList();
		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.FieldMismatch.class);
		var mismatch = (DriftItem.FieldMismatch) items.getFirst();
		assertThat(mismatch.path()).isEqualTo("description");
		assertThat(mismatch.wanted()).isEqualTo("Desired description");
		assertThat(mismatch.got()).isEqualTo("A great project");
	}

	@Test
	void detectsVisibilityMismatch() {
		var desired = desiredFull().toBuilder()
				.visibility(RepositoryVisibility.PRIVATE)
				.build();
		var items = group(desired, parseDetails(BASE_DETAILS_JSON)).detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();
		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.FieldMismatch.class);
		var mismatch = (DriftItem.FieldMismatch) items.getFirst();
		assertThat(mismatch.path()).isEqualTo("visibility");
	}

	@Test
	void detectsDefaultBranchMismatch() {
		var desired = desiredFull().toBuilder()
				.defaultBranch("develop")
				.build();
		var items = group(desired, parseDetails(BASE_DETAILS_JSON)).detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();
		assertThat(items).hasSize(1);
		var mismatch = (DriftItem.FieldMismatch) items.getFirst();
		assertThat(mismatch.path()).isEqualTo("default_branch");
	}

	@Test
	void nullDescriptionHandledAsEmpty() {
		var json = """
				{
					"description": null,
					"homepage": "",
					"has_issues": true,
					"has_projects": true,
					"has_wiki": true,
					"has_discussions": false,
					"is_template": false,
					"allow_forking": true,
					"web_commit_signoff_required": false,
					"default_branch": "main",
					"topics": [],
					"allow_merge_commit": true,
					"allow_squash_merge": true,
					"allow_rebase_merge": true,
					"allow_update_branch": false,
					"allow_auto_merge": false,
					"delete_branch_on_merge": false,
					"squash_merge_commit_title": "COMMIT_OR_PR_TITLE",
					"squash_merge_commit_message": "COMMIT_MESSAGES",
					"merge_commit_title": "MERGE_MESSAGE",
					"merge_commit_message": "PR_TITLE",
					"visibility": "public",
					"archived": false
				}
				""";
		var desired = RepositoryArgs.create("owner", "repo")
				.description("")
				.build();
		var items = new RepoSettingsDriftGroup(
				desired,
				parseDetails(json),
				null,
				"owner",
				"repo"
		).detect().stream().flatMap(f -> f.items().stream()).toList();
		assertThat(items).isEmpty();
	}

	@Test
	void nullHomepageHandledAsEmpty() {
		var json = """
				{
					"description": "",
					"homepage": null,
					"has_issues": true,
					"has_projects": true,
					"has_wiki": true,
					"has_discussions": false,
					"is_template": false,
					"allow_forking": true,
					"web_commit_signoff_required": false,
					"default_branch": "main",
					"topics": [],
					"allow_merge_commit": true,
					"allow_squash_merge": true,
					"allow_rebase_merge": true,
					"allow_update_branch": false,
					"allow_auto_merge": false,
					"delete_branch_on_merge": false,
					"squash_merge_commit_title": "COMMIT_OR_PR_TITLE",
					"squash_merge_commit_message": "COMMIT_MESSAGES",
					"merge_commit_title": "MERGE_MESSAGE",
					"merge_commit_message": "PR_TITLE",
					"visibility": "public",
					"archived": false
				}
				""";
		var desired = RepositoryArgs.create("owner", "repo")
				.homepageUrl("")
				.build();
		var items = new RepoSettingsDriftGroup(
				desired,
				parseDetails(json),
				null,
				"owner",
				"repo"
		).detect().stream().flatMap(f -> f.items().stream()).toList();
		assertThat(items).isEmpty();
	}

	@Test
	void detectsHasIssuesMismatch() {
		var desired = desiredFull().toBuilder().hasIssues(false).build();
		var items = group(desired, parseDetails(BASE_DETAILS_JSON)).detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();
		assertThat(items).hasSize(1);
		var mismatch = (DriftItem.FieldMismatch) items.getFirst();
		assertThat(mismatch.path()).isEqualTo("has_issues");
	}

	@Test
	void allowForkingIgnoredForUserOwnedRepo() {
		var json = """
				{
					"owner": {"login": "user1", "type": "User"},
					"description": "A great project",
					"homepage": "",
					"has_issues": true,
					"has_projects": true,
					"has_wiki": true,
					"has_discussions": false,
					"is_template": false,
					"allow_forking": false,
					"web_commit_signoff_required": false,
					"default_branch": "main",
					"topics": [],
					"allow_merge_commit": true,
					"allow_squash_merge": true,
					"allow_rebase_merge": true,
					"allow_update_branch": false,
					"allow_auto_merge": false,
					"delete_branch_on_merge": false,
					"squash_merge_commit_title": "COMMIT_OR_PR_TITLE",
					"squash_merge_commit_message": "COMMIT_MESSAGES",
					"merge_commit_title": "MERGE_MESSAGE",
					"merge_commit_message": "PR_TITLE",
					"visibility": "public",
					"archived": false
				}
				""";
		// desired has allowForking=true but actual has false; no drift expected
		// for user-owned repos
		var items = group(desiredFull(), parseDetails(json)).detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();
		assertThat(items).isEmpty();
	}

	@Test
	void allowForkingCheckedForOrgOwnedRepo() {
		var json = """
				{
					"owner": {"login": "myorg", "type": "Organization"},
					"description": "A great project",
					"homepage": "",
					"has_issues": true,
					"has_projects": true,
					"has_wiki": true,
					"has_discussions": false,
					"is_template": false,
					"allow_forking": false,
					"web_commit_signoff_required": false,
					"default_branch": "main",
					"topics": [],
					"allow_merge_commit": true,
					"allow_squash_merge": true,
					"allow_rebase_merge": true,
					"allow_update_branch": false,
					"allow_auto_merge": false,
					"delete_branch_on_merge": false,
					"squash_merge_commit_title": "COMMIT_OR_PR_TITLE",
					"squash_merge_commit_message": "COMMIT_MESSAGES",
					"merge_commit_title": "MERGE_MESSAGE",
					"merge_commit_message": "PR_TITLE",
					"visibility": "public",
					"archived": false
				}
				""";
		// desired has allowForking=true but actual has false; drift expected
		// for org-owned repos
		var items = group(desiredFull(), parseDetails(json)).detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();
		assertThat(items).hasSize(1);
		var mismatch = (DriftItem.FieldMismatch) items.getFirst();
		assertThat(mismatch.path()).isEqualTo("allow_forking");
	}

	@Test
	void detectsMultipleFieldMismatches() {
		var desired = desiredFull().toBuilder()
				.description("New description")
				.hasIssues(false)
				.hasProjects(false)
				.build();
		var items = group(desired, parseDetails(BASE_DETAILS_JSON)).detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();
		assertThat(items).hasSize(3);
		assertThat(items).allMatch(i -> i instanceof DriftItem.FieldMismatch);
		assertThat(items.stream().map(DriftItem::path))
				.containsExactlyInAnyOrder(
						"description",
						"has_issues",
						"has_projects"
				);
	}

}
