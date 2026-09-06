package io.github.arlol.githubcheck.drift;

import java.util.List;
import java.util.Map;

import io.github.arlol.githubcheck.actual.ActualWebhook;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.WebhookRequest;
import io.github.arlol.githubcheck.client.WebhookResponse;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.state.DriftyState;

/**
 * Organization webhooks — {@link WebhooksDriftGroup} on the organization
 * endpoints, with the secret under {@code org-<org>-webhook-<name>} and the
 * state under the organization's record.
 */
public class OrgWebhooksDriftGroup extends DriftGroup<Drifty.OrgGroupName> {

	private final WebhookReconciler reconciler;

	public OrgWebhooksDriftGroup(
			Map<String, Drifty.Webhook> desired,
			List<ActualWebhook> actual,
			Map<String, String> secretValues,
			DriftyState state,
			GitHubClient client,
			String org
	) {
		this.reconciler = new WebhookReconciler(
				desired,
				actual,
				secretValues,
				state,
				new WebhookReconciler.Scope() {

					@Override
					public String secretKey(String name) {
						return "org-" + org + "-webhook-" + name;
					}

					@Override
					public DriftyState.SecretRecord record(String name) {
						return state.orgWebhookSecretRecord(org, name);
					}

					@Override
					public void record(
							String name,
							String updatedAt,
							String valueHash
					) {
						state.recordOrgWebhookSecret(
								org,
								name,
								updatedAt,
								valueHash
						);
					}

					@Override
					public WebhookResponse create(WebhookRequest request) {
						return client.createOrgWebhook(org, request);
					}

					@Override
					public WebhookResponse update(
							long id,
							WebhookRequest request
					) {
						return client.updateOrgWebhook(org, id, request);
					}

					@Override
					public void delete(long id) {
						client.deleteOrgWebhook(org, id);
					}

				}
		);
	}

	@Override
	public Drifty.OrgGroupName name() {
		return Drifty.OrgGroupName.ORG_WEBHOOKS;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		return reconciler.fixes();
	}

}
