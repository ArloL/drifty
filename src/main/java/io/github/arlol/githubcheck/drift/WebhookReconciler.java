package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.arlol.githubcheck.actual.ActualWebhook;
import io.github.arlol.githubcheck.client.WebhookRequest;
import io.github.arlol.githubcheck.client.WebhookResponse;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.state.DriftyState;

/**
 * What the repository and organization webhook groups share: matching config
 * entries to hooks by url, comparing the fields GitHub returns, detecting
 * secret drift through the state file, and writing a hook back in one request.
 * The two groups supply the endpoints and the state accessors.
 * <p>
 * A hook's secret is the one field GitHub never returns, so it is handled the
 * way Actions secrets are: the state file records the {@code updated_at}
 * observed after the last push and the salted hash of the value pushed. Every
 * drift on a hook is fixed with one PATCH carrying the whole desired config,
 * secret included when the config declares one, because the config object
 * replaces what GitHub had and an omitted secret would clear it.
 */
final class WebhookReconciler {

	/** The endpoints and state of one scope, repository or organization. */
	interface Scope {

		/** The DRIFTY_GITHUB_SECRETS key of a hook's secret. */
		String secretKey(String name);

		DriftyState.SecretRecord record(String name);

		void record(String name, String updatedAt, String valueHash);

		WebhookResponse create(WebhookRequest request);

		WebhookResponse update(long id, WebhookRequest request);

		void delete(long id);

	}

	private final Map<String, Drifty.Webhook> desired;
	private final Map<String, ActualWebhook> actualByUrl;
	private final Map<String, String> secretValues;
	private final DriftyState state;
	private final Scope scope;

	WebhookReconciler(
			Map<String, Drifty.Webhook> desired,
			List<ActualWebhook> actual,
			Map<String, String> secretValues,
			DriftyState state,
			Scope scope
	) {
		this.desired = new LinkedHashMap<>(desired);
		var byUrl = new LinkedHashMap<String, ActualWebhook>();
		for (ActualWebhook hook : actual) {
			byUrl.putIfAbsent(hook.url(), hook);
		}
		this.actualByUrl = byUrl;
		this.secretValues = Map.copyOf(secretValues);
		this.state = state;
		this.scope = scope;
	}

	List<DriftFix> fixes() {
		var fixes = new ArrayList<DriftFix>();
		var urls = new HashSet<String>();

		for (var entry : desired.entrySet()) {
			String name = entry.getKey();
			Drifty.Webhook wanted = entry.getValue();
			if (!urls.add(wanted.url)) {
				var item = new DriftItem.FieldMismatch(
						name + ".url",
						wanted.url,
						"the url of another webhook in the config"
				);
				fixes.add(
						new DriftFix(
								item,
								() -> FixResult.unfixed(
										item,
										"two webhooks share a url; the url is what identifies a hook on GitHub"
								)
						)
				);
				continue;
			}
			ActualWebhook current = actualByUrl.get(wanted.url);
			if (current == null) {
				fixes.add(
						new DriftFix(
								new DriftItem.SectionMissing(name),
								() -> write(name, wanted, null)
						)
				);
				continue;
			}
			List<DriftItem> items = compare(name, wanted, current);
			fixes.add(new DriftFix(items, () -> write(name, wanted, current)));
		}

		for (ActualWebhook hook : actualByUrl.values()) {
			if (!urls.contains(hook.url())) {
				fixes.add(
						new DriftFix(
								new DriftItem.SectionExtra(hook.url()),
								() -> {
									scope.delete(hook.id());
									return FixResult.success();
								}
						)
				);
			}
		}
		return fixes;
	}

	private List<DriftItem> compare(
			String name,
			Drifty.Webhook wanted,
			ActualWebhook current
	) {
		var items = new ArrayList<DriftItem>(
				DriftGroup.combine(
						DriftGroup.compare(
								name + ".content_type",
								wanted.contentType.toString(),
								current.contentType()
						),
						DriftGroup.compare(
								name + ".insecure_ssl",
								wanted.insecureSsl,
								current.insecureSsl()
						),
						DriftGroup.compare(
								name + ".active",
								wanted.active,
								current.active()
						),
						DriftGroup.compare(
								name + ".events",
								new HashSet<>(wanted.events),
								current.events()
						)
				)
		);
		String secretPath = name + ".secret";
		if (!wanted.secret) {
			if (current.hasSecret()) {
				items.add(new DriftItem.FieldMismatch(secretPath, false, true));
			}
			return items;
		}
		if (!current.hasSecret()) {
			items.add(new DriftItem.SectionMissing(secretPath));
			return items;
		}
		var record = scope.record(name);
		if (record == null) {
			items.add(new DriftItem.SecretMissingBaseline(secretPath));
		} else if (!Objects.equals(record.updatedAt(), current.updatedAt())) {
			items.add(
					new DriftItem.SecretChanged(
							secretPath,
							record.updatedAt(),
							current.updatedAt()
					)
			);
		} else {
			var value = secretValues.get(scope.secretKey(name));
			if (value != null
					&& !record.valueHash().equals(state.hash(value))) {
				items.add(new DriftItem.SecretValueChanged(secretPath));
			}
		}
		return items;
	}

	/**
	 * Creates or replaces the hook and, when it carries a secret, records what
	 * was pushed. The items the fix covered are reported unfixed when the
	 * secret value is not available, since nothing can be written without it.
	 */
	private FixResult write(
			String name,
			Drifty.Webhook wanted,
			ActualWebhook current
	) {
		String value = null;
		if (wanted.secret) {
			String key = scope.secretKey(name);
			value = secretValues.get(key);
			if (value == null) {
				throw new IllegalStateException(
						"no value for " + key + " in DRIFTY_GITHUB_SECRETS"
				);
			}
		}
		var request = new WebhookRequest(
				current == null ? "web" : null,
				new WebhookRequest.Config(
						wanted.url,
						wanted.contentType.toString(),
						value,
						wanted.insecureSsl ? "1" : "0"
				),
				wanted.events,
				wanted.active
		);
		WebhookResponse written = current == null ? scope.create(request)
				: scope.update(current.id(), request);
		if (value != null) {
			scope.record(name, written.updatedAt(), state.hash(value));
		}
		return FixResult.success();
	}

}
