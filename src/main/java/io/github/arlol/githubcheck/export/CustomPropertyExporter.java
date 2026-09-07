package io.github.arlol.githubcheck.export;

import java.util.List;

import io.github.arlol.githubcheck.actual.ActualCustomProperty;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * A custom property definition, as the config lines that differ from the
 * schema's defaults.
 * <p>
 * {@code description} has no schema default — {@code Drifty.CustomProperty
 * .description} is simply {@code null} — but
 * {@code OrgCustomPropertiesDriftGroup} compares GitHub's {@code ""} against a
 * config's {@code null} normalized to {@code ""}, so {@code ""} is the
 * effective default used here too.
 * <p>
 * {@code defaultValue} and {@code defaultValues} are two representations of the
 * same setting — a single value for every type but {@code multi_select}, a list
 * for that one type alone — and the drift group only ever compares the one the
 * property's own {@code valueType} selects. Emitting both unconditionally would
 * invent a comparison the group never makes, so which one is written is picked
 * the same way.
 */
public final class CustomPropertyExporter {

	private static final String NO_DESCRIPTION = "";

	private CustomPropertyExporter() {
	}

	public static PklNode.Member entry(
			ActualCustomProperty actual,
			Drifty.CustomProperty defaults
	) {
		boolean multiSelect = "multi_select".equals(actual.valueType());
		List<PklNode.Member> members = Fields.members(
				Fields.required("valueType", actual.valueType()),
				Fields.field("required", actual.required(), defaults.required),
				multiSelect
						? Fields.strings(
								"defaultValues",
								actual.defaultValues(),
								defaults.defaultValues
						)
						: Fields.field(
								"defaultValue",
								actual.defaultValue(),
								defaults.defaultValue
						),
				Fields.field(
						"description",
						actual.description(),
						NO_DESCRIPTION
				),
				Fields.strings(
						"allowedValues",
						actual.allowedValues(),
						defaults.allowedValues
				),
				Fields.field(
						"valuesEditableBy",
						actual.valuesEditableBy(),
						defaults.valuesEditableBy.toString()
				)
		);
		return new PklNode.Field(actual.name(), new PklNode.Obj(members));
	}

}
