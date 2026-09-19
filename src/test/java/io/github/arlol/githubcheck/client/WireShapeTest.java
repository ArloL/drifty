package io.github.arlol.githubcheck.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;

class WireShapeTest {

	private final ObjectMapper mapper = WireShape.clientMapper();

	private List<String> wireNames(Class<?> type, boolean forReading) {
		return WireShape
				.properties(
						mapper,
						TypeFactory.defaultInstance().constructType(type),
						forReading
				)
				.stream()
				.map(WireShape.Property::wireName)
				.toList();
	}

	@Test
	void aComponentNameIsTheNameJacksonWillSend() {
		assertThat(wireNames(RepositoryUpdateRequest.class, false))
				.contains("has_issues", "web_commit_signoff_required")
				.doesNotContain("hasIssues");
	}

	@Test
	void aJsonPropertyOverridesTheNamingStrategy() {
		assertThat(wireNames(RepositoryDetailsResponse.class, true))
				.contains("private");
	}

	@Test
	void anEnumValueIsWhatJacksonSerialisesNotTheConstantName() {
		assertThat(WireShape.enumValues(mapper, RulesetEnforcement.class))
				.containsExactlyInAnyOrder("active", "disabled", "evaluate");
	}

	/**
	 * The four merge enums carry no {@code @JsonProperty} at all and rely on
	 * the Java constant name being the wire value. Uppercase is 18 of the
	 * spec's 360 enum value-sets, so these are the outliers this whole check
	 * exists to pin.
	 */
	@Test
	void anEnumWithNoJsonPropertyIsReadAsItsConstantNames() {
		assertThat(WireShape.enumValues(mapper, MergeCommitTitle.class))
				.containsExactlyInAnyOrder("PR_TITLE", "MERGE_MESSAGE");
	}

	@Test
	void aPolymorphicTypeReportsItsDiscriminatorAndSubtypes() {
		assertThat(WireShape.discriminator(Rule.class)).isEqualTo("type");
		assertThat(WireShape.subtypes(Rule.class))
				.containsEntry(
						"commit_message_pattern",
						Rule.CommitMessagePattern.class
				)
				.doesNotContainKey("unknown");
	}

	@Test
	void aRecordThatIsNotPolymorphicHasNoSubtypes() {
		assertThat(WireShape.subtypes(WebhookResponse.class)).isEmpty();
		assertThat(WireShape.discriminator(WebhookResponse.class)).isNull();
	}

}
