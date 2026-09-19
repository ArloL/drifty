package io.github.arlol.githubcheck.drift;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import io.github.arlol.githubcheck.ActualTypes;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.RepositoryDetailsResponse;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.testsupport.Desired;

@WireMockTest
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

	private Drifty.Repository desired(String description) {
		return Desired.repository("repo")
				.withDescription(description)
				.withVisibility(Drifty.Visibility.PUBLIC)
				.withDefaultBranch("main")
				.withHasIssues(true)
				.withHasProjects(true)
				.withHasWiki(true)
				.withHasDiscussions(false)
				.withIsTemplate(false)
				.withAllowForking(true)
				.withWebCommitSignoffRequired(false)
				.withAllowMergeCommit(true)
				.withAllowSquashMerge(true)
				.withAllowRebaseMerge(true)
				.withAllowAutoMerge(false)
				.withAllowUpdateBranch(false)
				.withDeleteBranchOnMerge(false)
				.withSquashMergeCommitTitle(
						Drifty.SquashMergeCommitTitle.COMMIT_OR_PR_TITLE
				)
				.withSquashMergeCommitMessage(
						Drifty.SquashMergeCommitMessage.COMMIT_MESSAGES
				)
				.withMergeCommitTitle(Drifty.MergeCommitTitle.MERGE_MESSAGE)
				.withMergeCommitMessage(Drifty.MergeCommitMessage.PR_TITLE);
	}

	private Drifty.Repository desiredFull() {
		return desired("A great project");
	}

	private RepoSettingsDriftGroup group(
			Drifty.Repository desired,
			RepositoryDetailsResponse actual
	) {
		return new RepoSettingsDriftGroup(
				desired,
				ActualTypes.repository(actual),
				null,
				new RepoRef("owner", "repo")
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
		assertThat(mismatch.path()).isEqualTo("repo_settings.description");
		assertThat(mismatch.wanted()).isEqualTo("Desired description");
		assertThat(mismatch.got()).isEqualTo("A great project");
	}

	@Test
	void detectsVisibilityMismatch() {
		var desired = desiredFull().withVisibility(Drifty.Visibility.PRIVATE);
		var items = group(desired, parseDetails(BASE_DETAILS_JSON)).detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();
		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.FieldMismatch.class);
		var mismatch = (DriftItem.FieldMismatch) items.getFirst();
		assertThat(mismatch.path()).isEqualTo("repo_settings.visibility");
	}

	@Test
	void detectsDefaultBranchMismatch() {
		var desired = desiredFull().withDefaultBranch("develop");
		var items = group(desired, parseDetails(BASE_DETAILS_JSON)).detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();
		assertThat(items).hasSize(1);
		var mismatch = (DriftItem.FieldMismatch) items.getFirst();
		assertThat(mismatch.path()).isEqualTo("repo_settings.default_branch");
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
		var desired = Desired.repository("repo").withDescription("");
		var items = new RepoSettingsDriftGroup(
				desired,
				ActualTypes.repository(parseDetails(json)),
				null,
				new RepoRef("owner", "repo")
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
		var desired = Desired.repository("repo").withHomepageUrl("");
		var items = new RepoSettingsDriftGroup(
				desired,
				ActualTypes.repository(parseDetails(json)),
				null,
				new RepoRef("owner", "repo")
		).detect().stream().flatMap(f -> f.items().stream()).toList();
		assertThat(items).isEmpty();
	}

	@Test
	void detectsHasIssuesMismatch() {
		var desired = desiredFull().withHasIssues(false);
		var items = group(desired, parseDetails(BASE_DETAILS_JSON)).detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();
		assertThat(items).hasSize(1);
		var mismatch = (DriftItem.FieldMismatch) items.getFirst();
		assertThat(mismatch.path()).isEqualTo("repo_settings.has_issues");
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
		assertThat(mismatch.path()).isEqualTo("repo_settings.allow_forking");
	}

	@Test
	void detectsMultipleFieldMismatches() {
		var desired = desiredFull().withDescription("New description")
				.withHasIssues(false)
				.withHasProjects(false);
		var items = group(desired, parseDetails(BASE_DETAILS_JSON)).detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();
		assertThat(items).hasSize(3);
		assertThat(items).allMatch(i -> i instanceof DriftItem.FieldMismatch);
		assertThat(items.stream().map(DriftItem::path))
				.containsExactlyInAnyOrder(
						"repo_settings.description",
						"repo_settings.has_issues",
						"repo_settings.has_projects"
				);
	}

	/**
	 * Every field of {@link #BASE_DETAILS_JSON} at the opposite value, under an
	 * organization so {@code allow_forking} is compared too. Kept apart from
	 * the base fixture rather than derived from it: the point is that the
	 * twenty-one rows of the table each name a different wire field, which a
	 * fixture generated by flipping the base would not test.
	 */
	private static final String ALL_DRIFTED_DETAILS_JSON = """
			{
				"owner": {"login": "myorg", "type": "Organization"},
				"description": "stale",
				"homepage": "https://stale.example",
				"has_issues": false,
				"has_projects": false,
				"has_wiki": false,
				"has_discussions": true,
				"is_template": true,
				"allow_forking": false,
				"web_commit_signoff_required": true,
				"default_branch": "master",
				"topics": [],
				"allow_merge_commit": false,
				"allow_squash_merge": false,
				"allow_rebase_merge": false,
				"allow_update_branch": true,
				"allow_auto_merge": true,
				"delete_branch_on_merge": true,
				"squash_merge_commit_title": "PR_TITLE",
				"squash_merge_commit_message": "PR_BODY",
				"merge_commit_title": "PR_TITLE",
				"merge_commit_message": "PR_BODY",
				"visibility": "private",
				"archived": false
			}
			""";

	/**
	 * Every writable row writes the setting it compared, checked one row at a
	 * time and derived from the table itself, so a row added to it is a case
	 * added here. The organization side has the same test for the same reason.
	 * <p>
	 * The rows pair a comparison with a builder call by hand. A row that
	 * compared {@code has_wiki} and wrote {@code has_projects} passes every
	 * other test in this suite and would quietly write the wrong setting to a
	 * live repository.
	 * <p>
	 * Everything drifts here and the PATCH is refused, which is what makes each
	 * field arrive as its own request: the group re-sends them individually to
	 * find out which one GitHub actually rejected. Each of those requests is
	 * one wire field, and it has to be the field the drift item it belongs to
	 * named, carrying the value that item wanted.
	 */
	@Test
	void everyWritableSettingWritesTheFieldItCompared(WireMockRuntimeInfo wm)
			throws Exception {
		stubFor(
				patch(urlPathEqualTo("/repos/myorg/repo")).willReturn(
						aResponse().withStatus(422)
								.withBody("{\"message\": \"nope\"}")
				)
		);
		var group = new RepoSettingsDriftGroup(
				desiredFull(),
				ActualTypes.repository(parseDetails(ALL_DRIFTED_DETAILS_JSON)),
				new GitHubClient(wm.getHttpBaseUrl(), "test-token"),
				new RepoRef("myorg", "repo")
		);

		FixResult result = group.detect().getFirst().fix().execute();

		// visibility has no write and is reported with its own reason, so the
		// ones GitHub refused are exactly the writable ones.
		Map<String, JsonNode> wanted = new LinkedHashMap<>();
		for (FixResult.Unfixed unfixed : result.unfixedItems()) {
			if (unfixed.reason().contains("HTTP 422")) {
				var mismatch = (DriftItem.FieldMismatch) unfixed.item();
				wanted.put(
						wireField(
								mismatch.path()
										.substring("repo_settings.".length())
						),
						MAPPER.valueToTree(mismatch.wanted())
				);
			}
		}
		assertThat(wanted).as("writable settings").hasSize(20);
		assertThat(result.unfixedItems())
				.as("visibility is reported, never sent")
				.anySatisfy(
						unfixed -> assertThat(unfixed.item().path())
								.isEqualTo("repo_settings.visibility")
				);

		List<LoggedRequest> requests = findAll(
				patchRequestedFor(urlPathEqualTo("/repos/myorg/repo"))
		);
		assertThat(requests).hasSize(wanted.size() + 1);

		ObjectNode batch = MAPPER.createObjectNode();
		wanted.forEach(batch::set);
		assertThat(body(requests.getFirst()))
				.as("the first request carries every drifted writable setting")
				.isEqualTo(batch);

		Map<String, JsonNode> sent = new LinkedHashMap<>();
		for (LoggedRequest request : requests.subList(1, requests.size())) {
			ObjectNode body = body(request);
			assertThat(body.size()).as("fields in %s", body).isEqualTo(1);
			var field = body.fields().next();
			sent.put(field.getKey(), field.getValue());
		}
		assertThat(sent).isEqualTo(wanted);
	}

	/**
	 * A drift path is the config's name for the setting, which is GitHub's name
	 * for it in every row but one: the schema calls the field
	 * {@code homepageUrl} and the PATCH body calls it {@code homepage}. Listed
	 * here rather than relaxed away, so the test still fails on a row that
	 * writes a different setting than it compared.
	 */
	private static String wireField(String driftPath) {
		return "homepage_url".equals(driftPath) ? "homepage" : driftPath;
	}

	private static ObjectNode body(LoggedRequest request) throws Exception {
		return (ObjectNode) MAPPER.readTree(request.getBodyAsString());
	}

}
