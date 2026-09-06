package io.github.arlol.githubcheck.drift;

import java.util.List;
import java.util.Map;

import io.github.arlol.githubcheck.actual.ActualWebhook;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.WebhookRequest;
import io.github.arlol.githubcheck.client.WebhookResponse;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.state.DriftyState;

/**
 * Repository webhooks. Hooks GitHub has that the config does not declare are
 * deleted by {@code --fix}: a hook is nothing but its config, so nothing the
 * deletion discards is lost. See {@link WebhookReconciler} for the rest.
 */
public class WebhooksDriftGroup extends DriftGroup<Drifty.GroupName> {

	private final WebhookReconciler reconciler;

	public WebhooksDriftGroup(
			Map<String, Drifty.Webhook> desired,
			List<ActualWebhook> actual,
			Map<String, String> secretValues,
			DriftyState state,
			GitHubClient client,
			RepoRef ref
	) {
		String owner = ref.owner();
		String repo = ref.name();
		this.reconciler = new WebhookReconciler(
				desired,
				actual,
				secretValues,
				state,
				new WebhookReconciler.Scope() {

					@Override
					public String secretKey(String name) {
						return repo + "-webhook-" + name;
					}

					@Override
					public DriftyState.SecretRecord record(String name) {
						return state.webhookSecretRecord(repo, name);
					}

					@Override
					public void record(
							String name,
							String updatedAt,
							String valueHash
					) {
						state.recordWebhookSecret(
								repo,
								name,
								updatedAt,
								valueHash
						);
					}

					@Override
					public WebhookResponse create(WebhookRequest request) {
						return client.createRepoWebhook(owner, repo, request);
					}

					@Override
					public WebhookResponse update(
							long id,
							WebhookRequest request
					) {
						return client
								.updateRepoWebhook(owner, repo, id, request);
					}

					@Override
					public void delete(long id) {
						client.deleteRepoWebhook(owner, repo, id);
					}

				}
		);
	}

	@Override
	public Drifty.GroupName name() {
		return Drifty.GroupName.WEBHOOKS;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		return reconciler.fixes();
	}

}
