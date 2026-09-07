package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class FieldsTest {

	@Test
	void aValueEqualToItsDefaultIsOmitted() {
		assertThat(Fields.field("description", "", "")).isEmpty();
		assertThat(Fields.field("hasIssues", true, true)).isEmpty();
	}

	@Test
	void aValueDifferentFromItsDefaultIsEmitted() {
		assertThat(Fields.field("description", "docs", "")).contains(
				new PklNode.Field("description", PklNode.Scalar.of("docs"))
		);
	}

	@Test
	void aRequiredFieldIsEmittedEvenWhenItLooksLikeADefault() {
		assertThat(Fields.required("name", ""))
				.contains(new PklNode.Field("name", PklNode.Scalar.of("")));
	}

	/**
	 * The one shape common to a variable's value, a collaborator's permission
	 * and an organization member's role: a mapping key paired with GitHub's
	 * current value, unconditionally — there is no schema default for a key
	 * that does not exist yet to compare against and drop the entry for.
	 */
	@Test
	void anEntryPairsANameWithAScalarValueUnconditionally() {
		assertThat(Fields.entry("region", "us-east-1")).isEqualTo(
				new PklNode.Field("region", PklNode.Scalar.of("us-east-1"))
		);
	}

	@Test
	void aRequiredNullableFieldWritesAnExplicitNullRatherThanDroppingIt() {
		assertThat(Fields.required("actorId", (Long) null)).contains(
				new PklNode.Field("actorId", PklNode.Scalar.nullValue())
		);
		assertThat(Fields.required("actorId", (Long) 7L))
				.contains(new PklNode.Field("actorId", PklNode.Scalar.of(7L)));
	}

	@Test
	void nullableIntegersCompareBothWays() {
		assertThat(Fields.field("maxFileSize", (Integer) null, (Integer) null))
				.isEmpty();
		assertThat(Fields.field("maxFileSize", (Integer) 100, (Integer) null))
				.isPresent();
		assertThat(Fields.field("maxFileSize", (Integer) null, (Integer) 100))
				.isPresent();
	}

	@Test
	void stringCollectionsCompareWithoutOrderAndEmitSorted() {
		assertThat(Fields.strings("topics", Set.of("b", "a"), Set.of("a", "b")))
				.isEmpty();
		assertThat(Fields.strings("topics", Set.of("b", "a"), Set.<String>of()))
				.contains(
						new PklNode.Field(
								"topics",
								new PklNode.Listing(
										List.of(
												PklNode.Scalar.of("a"),
												PklNode.Scalar.of("b")
										),
										true
								)
						)
				);
	}

	/**
	 * {@code Webhook.events} is the one schema listing whose default is
	 * non-empty; an amending render would union the two rather than replace it,
	 * wiring a hook to an event the account never configured. See
	 * {@code Fields.strings}'s own javadoc and {@code PklWriter}'s
	 * {@code Listing} case.
	 */
	@Test
	void stringCollectionsAreRenderedAsAReplacementNotAnAmendment() {
		var field = (PklNode.Field) Fields
				.strings("events", Set.of("pull_request"), Set.of("push"))
				.orElseThrow();

		assertThat(PklWriter.write(new PklNode.Obj(List.of(field))))
				.isEqualTo("""
						events = new Listing {
						  "pull_request"
						}
						""");
	}

	/**
	 * Unlike {@link #stringCollectionsAreRenderedAsAReplacementNotAnAmendment},
	 * an object listing keeps amendment rendering: replacing one without an
	 * explicit element type hands every element the untyped {@code Dynamic}
	 * class, which the schema's typed listings refuse. See
	 * {@code Fields.objects}'s own javadoc.
	 */
	@Test
	void objectListingsAreRenderedAsAnAmendmentNotAReplacement() {
		var element = new PklNode.Obj(
				List.of(new PklNode.Field("name", PklNode.Scalar.of("api")))
		);
		var field = (PklNode.Field) Fields
				.objects("repositories", List.of(element))
				.orElseThrow();

		assertThat(PklWriter.write(new PklNode.Obj(List.of(field))))
				.isEqualTo("""
						repositories {
						  new {
						    name = "api"
						  }
						}
						""");
	}

	@Test
	void emptyContainersAreOmitted() {
		assertThat(Fields.objects("repositories", List.of())).isEmpty();
		assertThat(Fields.mapping("teams", List.of())).isEmpty();
		assertThat(Fields.nested("pullRequest", List.of())).isEmpty();
	}

	@Test
	void membersDropsTheOmittedOnes() {
		List<PklNode.Member> members = Fields.members(
				Fields.field("description", "docs", ""),
				Fields.field("hasIssues", true, true)
		);

		assertThat(members).extracting(m -> ((PklNode.Field) m).name())
				.containsExactly("description");
	}

	@Test
	void enumEqualToDefaultIsOmitted() {
		assertThat(Fields.field("visibility", TestEnum.PUBLIC, "PUBLIC"))
				.isEmpty();
	}

	@Test
	void enumDifferentFromDefaultIsEmitted() {
		assertThat(
				Fields.field("visibility", TestEnum.PRIVATE, "PUBLIC")
		).contains(
				new PklNode.Field("visibility", PklNode.Scalar.of("PRIVATE"))
		);
	}

	@Test
	void nullEnumAgainstNonNullDefaultEmitsNullAndRendersCorrectly() {
		var field = Fields.field("visibility", (TestEnum) null, "PUBLIC");

		assertThat(field).isPresent();
		var member = field.orElseThrow();
		assertThat(member).isInstanceOf(PklNode.Field.class);

		// Verify the node can be rendered without NPE
		var rendered = PklWriter.write(new PklNode.Obj(List.of(member)));

		assertThat(rendered).contains("visibility = null");
	}

	enum TestEnum {
		PUBLIC, PRIVATE
	}

}
