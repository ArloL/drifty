package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.actual.ActualCustomProperty;

class CustomPropertyExporterTest {

	private static final SchemaDefaults DEFAULTS = SchemaDefaults
			.of(Path.of("config/drifty.pkl").toAbsolutePath().toString());

	private static ActualCustomProperty stringProperty(String name) {
		return new ActualCustomProperty(
				name,
				"string",
				false,
				null,
				List.of(),
				"",
				List.of(),
				"org_actors"
		);
	}

	/**
	 * {@code valueType} has no schema default and is always written, so "at
	 * GitHub's defaults" means only {@code valueType} appears.
	 */
	@Test
	void aStringPropertyAtGitHubsDefaultsExportsOnlyItsRequiredValueType() {
		var actual = stringProperty("team");

		var field = (PklNode.Field) CustomPropertyExporter
				.entry(actual, DEFAULTS.customProperty());

		assertThat(field.name()).isEqualTo("team");
		assertThat(PklWriter.write(field.value())).isEqualTo("""
				valueType = "string"
				""");
	}

	@Test
	void requiredDifferingFromTheDefaultIsEmitted() {
		var base = stringProperty("team");
		var actual = new ActualCustomProperty(
				base.name(),
				base.valueType(),
				true,
				base.defaultValue(),
				base.defaultValues(),
				base.description(),
				base.allowedValues(),
				base.valuesEditableBy()
		);

		var field = (PklNode.Field) CustomPropertyExporter
				.entry(actual, DEFAULTS.customProperty());

		assertThat(PklWriter.write(field.value())).isEqualTo("""
				valueType = "string"
				required = true
				""");
	}

	/**
	 * The drift group only ever compares {@code defaultValue} for a
	 * non-multi_select property; a {@code multi_select} property has its own
	 * case below exercising {@code defaultValues} instead, proving the two
	 * fields are not both written unconditionally.
	 */
	@Test
	void defaultValueIsComparedForANonMultiSelectProperty() {
		var base = stringProperty("team");
		var actual = new ActualCustomProperty(
				base.name(),
				base.valueType(),
				base.required(),
				"platform",
				base.defaultValues(),
				base.description(),
				base.allowedValues(),
				base.valuesEditableBy()
		);

		var field = (PklNode.Field) CustomPropertyExporter
				.entry(actual, DEFAULTS.customProperty());

		assertThat(PklWriter.write(field.value())).isEqualTo("""
				valueType = "string"
				defaultValue = "platform"
				""");
	}

	@Test
	void defaultValuesIsComparedForAMultiSelectPropertyInsteadOfDefaultValue() {
		var actual = new ActualCustomProperty(
				"stack",
				"multi_select",
				false,
				null,
				List.of("java", "go"),
				"",
				List.of("java", "go", "python"),
				"org_actors"
		);

		var field = (PklNode.Field) CustomPropertyExporter
				.entry(actual, DEFAULTS.customProperty());

		assertThat(PklWriter.write(field.value())).isEqualTo("""
				valueType = "multi_select"
				defaultValues {
				  "go"
				  "java"
				}
				allowedValues {
				  "go"
				  "java"
				  "python"
				}
				""");
	}

}
