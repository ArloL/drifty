package io.github.arlol.githubcheck.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import io.github.arlol.githubcheck.client.ApiContract.SchemaNode;
import io.github.arlol.githubcheck.client.ContractComparison.Finding;
import io.github.arlol.githubcheck.client.ContractComparison.Options;

/**
 * The checker's own tests, against a hand-written fixture rather than the 705
 * KB contract: a test that fails when GitHub changes its spec is testing
 * GitHub, not the walk.
 */
class ContractComparisonTest {

	private static final String ENDPOINT = "PATCH /example/{id}";
	private static final Predicate<String> URLS_AND_IDS = name -> name
			.endsWith("_url") || "id".equals(name);

	private static final ApiContract FIXTURE = ApiContract
			.load(Path.of("src/test/resources/contract-fixture.json"));

	private static final SchemaNode REQUEST = FIXTURE
			.schema(ENDPOINT, "request");
	private static final SchemaNode RESPONSE = FIXTURE
			.schema(ENDPOINT, "response");

	// ─── Fixtures ───────────────────────────────────────────────────────────

	record Correct(
			MergeCommitTitle mergeCommitTitle,
			Boolean hasIssues,
			String description
	) {
	}

	record Miscased(
			String hasIssue
	) {
	}

	enum LowercaseTitle {
		@JsonProperty("pr_title")
		PR_TITLE, @JsonProperty("merge_message")
		MERGE_MESSAGE
	}

	record LowercaseEnum(
			LowercaseTitle mergeCommitTitle
	) {
	}

	enum HalfTitle {
		@JsonProperty("PR_TITLE")
		PR_TITLE
	}

	record HalfEnum(
			HalfTitle mergeCommitTitle
	) {
	}

	record WrongType(
			Boolean name
	) {
	}

	record PrimitiveCount(
			int count
	) {
	}

	record Config(
			String secret
	) {
	}

	record WithConfig(
			Config config
	) {
	}

	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = true)
	@JsonSubTypes(
		{ @JsonSubTypes.Type(value = CreationRule.class, name = "creation") }
	)
	interface ExampleRule {
	}

	record CreationRule(
			String type
	) implements ExampleRule {
	}

	record WithRules(
			List<ExampleRule> rules
	) {
	}

	// ─── Helpers ────────────────────────────────────────────────────────────

	private List<String> paths(
			SchemaNode schema,
			Class<?> root,
			Options options
	) {
		return new ContractComparison(ENDPOINT, direction(schema), options)
				.compare(schema, root)
				.stream()
				.map(Finding::path)
				.toList();
	}

	private static String direction(SchemaNode schema) {
		return schema == REQUEST ? "request" : "response";
	}

	private static Options reverse(String... unmanaged) {
		return new Options(true, Set.of(unmanaged), Set.of(), URLS_AND_IDS);
	}

	// ─── Forward direction ──────────────────────────────────────────────────

	@Test
	void aRecordThatMatchesTheSpecReportsNothing() {
		assertThat(paths(REQUEST, Correct.class, Options.forward())).isEmpty();
	}

	@Test
	void aMiscasedComponentIsReported() {
		assertThat(paths(REQUEST, Miscased.class, Options.forward()))
				.containsExactly("has_issue");
	}

	@Test
	void aJavaEnumConstantTheSpecDoesNotCarryIsReported() {
		assertThat(paths(REQUEST, LowercaseEnum.class, Options.forward()))
				.contains(
						"merge_commit_title#pr_title",
						"merge_commit_title#merge_message"
				);
	}

	@Test
	void aSpecEnumValueTheJavaEnumLacksIsReportedEvenGoingForward() {
		// Not a gap to schedule: Jackson throws on a value it has no constant
		// for, so this is a crash in the wild rather than a missing feature.
		assertThat(paths(REQUEST, HalfEnum.class, Options.forward()))
				.containsExactly("merge_commit_title#MERGE_MESSAGE");
	}

	@Test
	void aBooleanAgainstAStringPropertyIsReported() {
		assertThat(paths(RESPONSE, WrongType.class, Options.forward()))
				.containsExactly("name");
	}

	@Test
	void aPrimitiveAgainstANullablePropertyIsReported() {
		assertThat(paths(RESPONSE, PrimitiveCount.class, Options.forward()))
				.containsExactly("count");
	}

	// ─── Reverse direction ──────────────────────────────────────────────────

	@Test
	void aSpecPropertyTheRecordOmitsIsReportedOnlyInReverseMode() {
		assertThat(paths(REQUEST, Correct.class, Options.forward())).isEmpty();
		assertThat(paths(REQUEST, Correct.class, reverse()))
				.containsExactly("billing_email");
	}

	@Test
	void anUnmanagedEntrySuppressesOneFindingAndNoOther() {
		assertThat(
				paths(
						REQUEST,
						Correct.class,
						reverse(
								"billing_email — drifty does not manage billing"
						)
				)
		).isEmpty();
	}

	@Test
	void anUndocumentedEntrySuppressesOneFindingAndNoOther() {
		Options options = new Options(
				false,
				Set.of(),
				Set.of("has_issue — GitHub returns it; the spec omits it"),
				URLS_AND_IDS
		);
		assertThat(paths(REQUEST, Miscased.class, options)).isEmpty();
	}

	@Test
	void anExclusionNothingMatchedIsReportedAsUnused() {
		ContractComparison comparison = new ContractComparison(
				ENDPOINT,
				"request",
				reverse("billing_email — real", "no_such_field — stale")
		);
		comparison.compare(REQUEST, Correct.class);

		assertThat(comparison.unusedExclusions())
				.containsExactly("no_such_field");
		assertThat(comparison.consumedExclusions())
				.containsExactly("billing_email");
	}

	// ─── The root metadata rule ─────────────────────────────────────────────

	@Test
	void theRootMetadataRuleHidesARootUrlAndAnId() {
		assertThat(paths(RESPONSE, WithConfig.class, reverse()))
				.doesNotContain("html_url", "id");
	}

	/**
	 * A webhook's {@code config.url} is the payload URL — a managed setting at
	 * depth 1. Scoping the rule to the root is what keeps it visible.
	 */
	@Test
	void theRootMetadataRuleDoesNotHideANestedUrl() {
		assertThat(paths(RESPONSE, WithConfig.class, reverse()))
				.contains("config.url");
	}

	// ─── Polymorphism ───────────────────────────────────────────────────────

	@Test
	void aSubtypeIsComparedAgainstItsOwnBranchNotTheUnion() {
		// `creation` matches, so nothing about it is reported; `deletion` has
		// no subtype, which is reported whatever the direction, because the
		// catch-all swallows it and then nothing compares it.
		assertThat(paths(RESPONSE, WithRules.class, Options.forward()))
				.containsExactly("rules[deletion]");
	}

	@Test
	void aMissingSubtypeCanBeDeclaredUnmanaged() {
		assertThat(
				paths(
						RESPONSE,
						WithRules.class,
						new Options(
								false,
								Set.of(
										"rules[deletion] — drifty does not manage it"
								),
								Set.of(),
								URLS_AND_IDS
						)
				)
		).isEmpty();
	}

}
