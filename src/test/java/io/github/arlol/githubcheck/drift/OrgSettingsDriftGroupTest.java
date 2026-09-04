package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.testsupport.Actual;
import io.github.arlol.githubcheck.testsupport.Desired;

class OrgSettingsDriftGroupTest {

	@Test
	void noDriftWhenEverythingMatches() {
		var group = new OrgSettingsDriftGroup(
				Desired.organization(),
				Actual.organization(),
				null,
				"my-org"
		);

		assertThat(group.detect().getFirst().items()).isEmpty();
	}

	@Test
	void detectsWritableDrift() {
		var group = new OrgSettingsDriftGroup(
				Desired.organization().withDescription("wanted"),
				Actual.organization(),
				null,
				"my-org"
		);

		List<DriftItem> items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).extracting(DriftItem::path)
				.containsExactly("org_settings.description");
	}

	@Test
	void checkOnlySettingIsReportedAndNotWritten() {
		var group = new OrgSettingsDriftGroup(
				Desired.organization().withMembersCanDeleteRepositories(false),
				Actual.organization(),
				null,
				"my-org"
		);

		FixResult result = group.detect().getFirst().fix().execute();

		assertThat(result.unfixedItems()).singleElement().satisfies(unfixed -> {
			assertThat(unfixed.item().path())
					.isEqualTo("org_settings.members_can_delete_repositories");
			assertThat(unfixed.reason())
					.contains("cannot be changed through the API");
		});
	}

}
