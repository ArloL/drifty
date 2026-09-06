package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.arlol.githubcheck.actual.ActualCustomProperty;
import io.github.arlol.githubcheck.client.CustomPropertyRequest;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Custom property definitions on an organization. A missing or drifted
 * definition is written with one PUT, which replaces it whole. Definitions
 * GitHub has that the config does not declare are reported and left alone:
 * deleting one discards its value on every repository in the organization.
 * Enterprise-owned definitions never reach this group — the checker drops them,
 * the way it drops org rulesets from a repository's list.
 */
public class OrgCustomPropertiesDriftGroup
		extends DriftGroup<Drifty.OrgGroupName> {

	private final Map<String, Drifty.CustomProperty> desired;
	private final Map<String, ActualCustomProperty> actual;
	private final GitHubClient client;
	private final String org;

	public OrgCustomPropertiesDriftGroup(
			Map<String, Drifty.CustomProperty> desired,
			List<ActualCustomProperty> actual,
			GitHubClient client,
			String org
	) {
		this.desired = Collections
				.unmodifiableMap(new LinkedHashMap<>(desired));
		var byName = new LinkedHashMap<String, ActualCustomProperty>();
		for (ActualCustomProperty property : actual) {
			byName.put(property.name(), property);
		}
		this.actual = Collections.unmodifiableMap(byName);
		this.client = client;
		this.org = org;
	}

	@Override
	public Drifty.OrgGroupName name() {
		return Drifty.OrgGroupName.ORG_CUSTOM_PROPERTIES;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		var fixes = new ArrayList<DriftFix>();

		for (var entry : desired.entrySet()) {
			String name = entry.getKey();
			Drifty.CustomProperty wanted = entry.getValue();
			ActualCustomProperty current = actual.get(name);
			if (current == null) {
				fixes.add(
						new DriftFix(
								new DriftItem.SectionMissing(name),
								() -> write(name, wanted)
						)
				);
				continue;
			}
			fixes.add(
					new DriftFix(
							compare(name, wanted, current),
							() -> write(name, wanted)
					)
			);
		}

		for (ActualCustomProperty property : actual.values()) {
			if (!desired.containsKey(property.name())) {
				var item = new DriftItem.SectionExtra(property.name());
				fixes.add(
						new DriftFix(
								item,
								() -> FixResult.unfixed(
										item,
										"drifty does not delete custom properties: deleting one discards its value on every repository"
								)
						)
				);
			}
		}

		return fixes;
	}

	private static List<DriftItem> compare(
			String name,
			Drifty.CustomProperty wanted,
			ActualCustomProperty current
	) {
		boolean multiSelect = isMultiSelect(wanted);
		return combine(
				compare(
						name + ".value_type",
						wanted.valueType.toString(),
						current.valueType()
				),
				compare(
						name + ".required",
						wanted.required,
						current.required()
				),
				multiSelect
						? compare(
								name + ".default_value",
								wanted.defaultValues,
								current.defaultValues()
						)
						: compare(
								name + ".default_value",
								wanted.defaultValue,
								current.defaultValue()
						),
				compare(
						name + ".description",
						wanted.description == null ? "" : wanted.description,
						current.description()
				),
				compare(
						name + ".allowed_values",
						wanted.allowedValues,
						current.allowedValues()
				),
				compare(
						name + ".values_editable_by",
						wanted.valuesEditableBy.toString(),
						current.valuesEditableBy()
				)
		);
	}

	private static boolean isMultiSelect(Drifty.CustomProperty wanted) {
		return wanted.valueType == Drifty.CustomPropertyValueType.MULTI_SELECT;
	}

	private static boolean isSelect(Drifty.CustomProperty wanted) {
		return isMultiSelect(
				wanted
		) || wanted.valueType == Drifty.CustomPropertyValueType.SINGLE_SELECT;
	}

	private FixResult write(String name, Drifty.CustomProperty wanted) {
		client.putOrgCustomProperty(
				org,
				name,
				new CustomPropertyRequest(
						wanted.valueType.toString(),
						wanted.required,
						isMultiSelect(wanted)
								? (wanted.defaultValues.isEmpty() ? null
										: wanted.defaultValues)
								: wanted.defaultValue,
						wanted.description,
						isSelect(wanted) ? wanted.allowedValues : null,
						wanted.valuesEditableBy.toString()
				)
		);
		return FixResult.success();
	}

}
