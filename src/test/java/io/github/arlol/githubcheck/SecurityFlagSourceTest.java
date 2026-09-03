package io.github.arlol.githubcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import io.github.arlol.githubcheck.client.RepositoryDetailsResponse;
import io.github.arlol.githubcheck.client.WorkflowPermissions;
import io.github.arlol.githubcheck.drift.DriftItem;
import io.github.arlol.githubcheck.testsupport.Desired;

/**
 * "Is this security flag enabled" must have exactly one answer.
 * <p>
 * It used to have three: {@code fetchSecurityFlags} recorded it on
 * {@link RepositoryState}, {@code securityFlag} recomputed it, and
 * {@code createDriftGroups} inlined the null-chain a third time for secret
 * scanning and push protection. The three could disagree, and two of them
 * already had: the recorded fields were written and never read.
 * <p>
 * These tests pin that the state's own accessor and the drift group reach the
 * same verdict from the same response.
 */
class SecurityFlagSourceTest {

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
			.configure(
					DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
					false
			);

	private static final String DETAILS_TEMPLATE = """
			{
				"name": "repo",
				"archived": false,
				"visibility": "public",
				"default_branch": "main",
				"security_and_analysis": {
					"secret_scanning": {"status": "%s"},
					"secret_scanning_push_protection": {"status": "%s"}
				}
			}
			""";

	private static final String WORKFLOW_PERMISSIONS_JSON = """
			{
				"default_workflow_permissions": "write",
				"can_approve_pull_request_reviews": true
			}
			""";

	@Test
	void stateReportsSecretScanningAsTheResponseDoes() {
		assertThat(
				state("enabled", "disabled").securityAndAnalysis()
						.secretScanning()
		).isTrue();
		assertThat(
				state("disabled", "disabled").securityAndAnalysis()
						.secretScanning()
		).isFalse();
	}

	@Test
	void stateReportsPushProtectionAsTheResponseDoes() {
		assertThat(
				state("disabled", "enabled").securityAndAnalysis()
						.secretScanningPushProtection()
		).isTrue();
		assertThat(
				state("disabled", "disabled").securityAndAnalysis()
						.secretScanningPushProtection()
		).isFalse();
	}

	@Test
	void secretScanningGroupAgreesWithTheState() {
		assertGroupAgreesWithState("secret_scanning", "enabled", "disabled");
		assertGroupAgreesWithState("secret_scanning", "disabled", "disabled");
	}

	@Test
	void pushProtectionGroupAgreesWithTheState() {
		assertGroupAgreesWithState(
				"secret_scanning_push_protection",
				"disabled",
				"enabled"
		);
		assertGroupAgreesWithState(
				"secret_scanning_push_protection",
				"disabled",
				"disabled"
		);
	}

	/**
	 * Both settings are wanted enabled, so a group drifts exactly when the
	 * state says its flag is off. If the group derived the flag separately, the
	 * two could disagree and this would fail.
	 */
	private static void assertGroupAgreesWithState(
			String groupName,
			String secretScanning,
			String pushProtection
	) {
		RepositoryState state = state(secretScanning, pushProtection);
		boolean stateSaysEnabled = groupName.equals("secret_scanning")
				? state.securityAndAnalysis().secretScanning()
				: state.securityAndAnalysis().secretScanningPushProtection();

		var desired = Desired.repository("owner", "repo")
				.withSecretScanning(true)
				.withSecretScanningPushProtection(true);

		List<String> paths = new OrgChecker((String) null, false)
				.createDriftGroups(state, desired)
				.stream()
				.filter(group -> group.name().equals(groupName))
				.flatMap(group -> group.detect().stream())
				.flatMap(fix -> fix.items().stream())
				.map(DriftItem::path)
				.toList();

		assertThat(paths.isEmpty()).as(
				groupName + " drifted=" + !paths.isEmpty()
						+ " but the state says enabled=" + stateSaysEnabled
		).isEqualTo(stateSaysEnabled);
	}

	private static RepositoryState state(
			String secretScanning,
			String pushProtection
	) {
		var details = parse(
				DETAILS_TEMPLATE.formatted(secretScanning, pushProtection),
				RepositoryDetailsResponse.class
		);
		return new RepositoryState(
				"repo",
				ActualTypes.repository(details),
				ActualTypes.securityAndAnalysis(details),
				false,
				false,
				false,
				false,
				false,
				Map.of(),
				List.of(),
				List.of(),
				Map.of(),
				Map.of(),
				ActualTypes.workflowPermissions(
						parse(
								WORKFLOW_PERMISSIONS_JSON,
								WorkflowPermissions.class
						)
				),
				Optional.empty()
		);
	}

	private static <T> T parse(String json, Class<T> type) {
		try {
			return MAPPER.readValue(json, type);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
