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
										)
								)
						)
				);
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
