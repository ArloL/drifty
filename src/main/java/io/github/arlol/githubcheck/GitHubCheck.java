package io.github.arlol.githubcheck;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import io.github.arlol.githubcheck.config.RepositoryArgs;
import io.github.arlol.githubcheck.state.DriftyState;
import io.github.arlol.githubcheck.state.StateStore;

public class GitHubCheck {

	static void main(String[] args)
			throws IOException, InterruptedException, ExecutionException {
		if (args.length == 1 && "--version".equals(args[0])) {
			Package pkg = GitHubCheck.class.getPackage();
			String title = pkg.getImplementationTitle();
			String version = pkg.getImplementationVersion();
			System.out.println(title + " version \"" + version + "\"");
			return;
		}
		String token = System.getenv("DRIFTY_GITHUB_TOKEN");
		if (token == null || token.isBlank()) {
			System.err.println(
					"ERROR: DRIFTY_GITHUB_TOKEN environment variable not set"
			);
			System.exit(1);
		}

		var argsList = List.of(args);
		boolean fix = argsList.contains("--fix");
		int pklIndex = argsList.indexOf("--pkl");
		String pklPath = (pklIndex >= 0 && pklIndex + 1 < argsList.size())
				? argsList.get(pklIndex + 1)
				: null;
		int stateIndex = argsList.indexOf("--state");
		String statePath = (stateIndex >= 0 && stateIndex + 1 < argsList.size())
				? argsList.get(stateIndex + 1)
				: null;

		Map<String, String> githubSecrets = Map.of();
		String githubSecretsJson = System.getenv("DRIFTY_GITHUB_SECRETS");
		if (githubSecretsJson != null && !githubSecretsJson.isBlank()) {
			githubSecrets = new ObjectMapper()
					.configure(
							DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
							false
					)
					.setPropertyNamingStrategy(
							PropertyNamingStrategies.SNAKE_CASE
					)
					.readValue(
							githubSecretsJson,
							new TypeReference<Map<String, String>>() {
							}
					);
		}

		Path configPath = pklPath != null ? Path.of(pklPath)
				: Path.of("drifty.pkl");
		if (!Files.isRegularFile(configPath)) {
			System.err.println("ERROR: config file not found: " + configPath);
			System.exit(1);
		}
		List<RepositoryArgs> repos = PklConfigLoader
				.load(configPath.toAbsolutePath());

		Path stateFile = statePath != null ? Path.of(statePath)
				: configPath.toAbsolutePath()
						.resolveSibling("drifty-state.json");
		var stateStore = new StateStore();
		DriftyState state = stateStore.load(stateFile);

		if (fix) {
			var missingSecrets = new ArrayList<String>();
			for (RepositoryArgs repo : repos) {
				for (String secretName : repo.actionsSecrets()) {
					String key = repo.name() + "-" + secretName;
					if (!githubSecrets.containsKey(key)) {
						missingSecrets.add(key);
					}
				}
				for (var entry : repo.environments().entrySet()) {
					String envName = entry.getKey();
					for (String secretName : entry.getValue().secrets()) {
						String key = repo.name() + "-" + envName + "-"
								+ secretName;
						if (!githubSecrets.containsKey(key)) {
							missingSecrets.add(key);
						}
					}
				}
			}
			if (!missingSecrets.isEmpty()) {
				System.err.println(
						"ERROR: Missing secret values in DRIFTY_GITHUB_SECRETS for fix mode:"
				);
				for (String key : missingSecrets) {
					System.err.println("  " + key);
				}
				System.exit(1);
			}
		}

		long startTime = System.currentTimeMillis();

		var checker = new OrgChecker(token, "ArloL", fix, githubSecrets, state);
		CheckResult result = checker.check(repos);
		checker.printReport(result);

		if (fix) {
			stateStore.save(stateFile, state);
		}

		double totalSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
		System.out
				.printf("%nTotal execution time: %.2f seconds%n", totalSeconds);

		System.exit(result.hasDrift() ? 1 : 0);
	}

}
