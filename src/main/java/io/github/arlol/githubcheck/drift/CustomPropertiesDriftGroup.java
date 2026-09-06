package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.arlol.githubcheck.actual.ActualCustomPropertyValue;
import io.github.arlol.githubcheck.client.CustomPropertyValuesRequest;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Custom property values on a repository. Only the properties the config names
 * are compared: which properties exist is the organization's schema to decide,
 * so a value GitHub has for a property the config does not mention is not
 * drift. The fix is one PATCH listing the drifted properties.
 */
public class CustomPropertiesDriftGroup extends DriftGroup<Drifty.GroupName> {

	private final Map<String, String> desired;
	private final Map<String, List<String>> desiredMultiSelect;
	private final Map<String, ActualCustomPropertyValue> actual;
	private final GitHubClient client;
	private final RepoRef ref;

	public CustomPropertiesDriftGroup(
			Map<String, String> desired,
			Map<String, List<String>> desiredMultiSelect,
			List<ActualCustomPropertyValue> actual,
			GitHubClient client,
			RepoRef ref
	) {
		this.desired = Collections
				.unmodifiableMap(new LinkedHashMap<>(desired));
		this.desiredMultiSelect = Collections
				.unmodifiableMap(new LinkedHashMap<>(desiredMultiSelect));
		var byName = new LinkedHashMap<String, ActualCustomPropertyValue>();
		for (ActualCustomPropertyValue value : actual) {
			byName.put(value.name(), value);
		}
		this.actual = Collections.unmodifiableMap(byName);
		this.client = client;
		this.ref = ref;
	}

	@Override
	public Drifty.GroupName name() {
		return Drifty.GroupName.CUSTOM_PROPERTIES;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		var items = new ArrayList<DriftItem>();
		var drifted = new ArrayList<CustomPropertyValuesRequest.Property>();

		for (var entry : desired.entrySet()) {
			String name = entry.getKey();
			ActualCustomPropertyValue current = actual.get(name);
			var item = ocompare(
					name,
					entry.getValue(),
					current == null ? null : current.value()
			);
			if (item.isPresent()) {
				items.add(item.orElseThrow());
				drifted.add(
						new CustomPropertyValuesRequest.Property(
								name,
								entry.getValue()
						)
				);
			}
		}
		for (var entry : desiredMultiSelect.entrySet()) {
			String name = entry.getKey();
			ActualCustomPropertyValue current = actual.get(name);
			var item = ocompare(
					name,
					entry.getValue(),
					current == null ? List.<String>of() : current.values()
			);
			if (item.isPresent()) {
				items.add(item.orElseThrow());
				drifted.add(
						new CustomPropertyValuesRequest.Property(
								name,
								entry.getValue()
						)
				);
			}
		}

		if (items.isEmpty()) {
			return List.of();
		}
		return List.of(new DriftFix(items, () -> {
			client.updateRepoCustomPropertyValues(
					ref.owner(),
					ref.name(),
					new CustomPropertyValuesRequest(drifted)
			);
			return FixResult.success();
		}));
	}

}
