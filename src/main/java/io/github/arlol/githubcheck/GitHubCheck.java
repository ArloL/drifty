package io.github.arlol.githubcheck;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepositorySummaryResponse;
import io.github.arlol.githubcheck.client.Secrets;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.state.DriftyState;
import io.github.arlol.githubcheck.state.StateStore;

public class GitHubCheck {

	static void main(String[] args)
			throws IOException, InterruptedException, ExecutionException {
		if (handledVersion(args)) {
			return;
		}
		if (args.length == 1 && "--self-test".equals(args[0])) {
			System.exit(selfTest());
			return;
		}

		String token = System.getenv("DRIFTY_GITHUB_TOKEN");
		if (token == null || token.isBlank()) {
			System.err.println(
					"ERROR: DRIFTY_GITHUB_TOKEN environment variable not set"
			);
			System.exit(1);
			return;
		}

		var argsList = List.of(args);
		boolean fix = argsList.contains("--fix");
		String configArg = optionValue(argsList, "--config");
		String statePath = optionValue(argsList, "--state");

		Map<String, String> githubSecrets = loadGithubSecrets();

		Path configPath = configArg != null ? Path.of(configArg)
				: Path.of("drifty.pkl");
		if (!Files.isRegularFile(configPath)) {
			System.err.println("ERROR: config file not found: " + configPath);
			System.exit(1);
			return;
		}
		DriftyConfig config = PklConfigLoader.load(configPath.toAbsolutePath());

		Path stateFile = statePath != null ? Path.of(statePath)
				: configPath.toAbsolutePath()
						.resolveSibling("drifty-state.json");
		var stateStore = new StateStore();
		DriftyState state = stateStore.load(stateFile);

		if (fix && reportMissingSecrets(
				config.allRepositories(),
				githubSecrets
		)) {
			System.exit(1);
			return;
		}

		long startTime = System.currentTimeMillis();

		var client = new GitHubClient(token);
		var repoChecker = new RepositoryChecker(
				client,
				fix,
				githubSecrets,
				state
		);
		var orgChecker = new OrganizationChecker(
				client,
				fix,
				githubSecrets,
				state
		);
		var orgEntries = new ArrayList<CheckResult.Entry>();
		var repoEntries = new ArrayList<CheckResult.Entry>();
		long startFetch = System.currentTimeMillis();

		for (var entry : config.organizations().entrySet()) {
			String login = entry.getKey();
			System.out.println("Fetching repo list for organization: " + login);
			Optional<List<RepositorySummaryResponse>> repos = client
					.listOrgRepos(login);
			if (repos.isEmpty()) {
				orgEntries.add(CheckResult.Entry.missing(login));
				entry.getValue().repositories.forEach(
						r -> repoEntries.add(CheckResult.Entry.missing(r.name))
				);
				continue;
			}
			System.out.printf(
					"Found %d repos. Fetching details in parallel...%n",
					repos.orElseThrow().size()
			);
			// The org is checked before its repositories, and both work from
			// the same listing: an org secret's selected repositories arrive as
			// ids, and this is where their names are.
			orgEntries.add(
					orgChecker
							.check(login, entry.getValue(), repos.orElseThrow())
			);
			repoEntries.addAll(
					repoChecker.check(
							login,
							repos.orElseThrow(),
							entry.getValue().repositories
					)
			);
		}

		for (var entry : config.users().entrySet()) {
			String login = entry.getKey();
			System.out.println("Fetching repo list for user: " + login);
			List<RepositorySummaryResponse> repos = client.listUserRepos(login);
			System.out.printf(
					"Found %d repos. Fetching details in parallel...%n",
					repos.size()
			);
			repoEntries.addAll(
					repoChecker
							.check(login, repos, entry.getValue().repositories)
			);
		}

		System.out.printf(
				"Fetch complete in %.2f seconds%n%n",
				(System.currentTimeMillis() - startFetch) / 1000.0
		);

		CheckResult result = new CheckResult(orgEntries, repoEntries);
		Report.print(result);

		if (fix) {
			stateStore.save(stateFile, state);
		}

		double totalSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
		System.out
				.printf("%nTotal execution time: %.2f seconds%n", totalSeconds);

		System.exit(result.hasDrift() ? 1 : 0);
	}

	static boolean handledVersion(String[] args) {
		if (args.length != 1 || !"--version".equals(args[0])) {
			return false;
		}
		Package pkg = GitHubCheck.class.getPackage();
		String title = pkg.getImplementationTitle();
		String version = pkg.getImplementationVersion();
		System.out.println(title + " version \"" + version + "\"");
		return true;
	}

	// Network- and token-free smoke test of the libsodium/JNA path. This is
	// the code that crashes in the native image when the JNA reflection
	// metadata is missing (NoSuchMethodException on
	// com.sun.jna.Structure$FFIType.<init>()). NativeExecutableIT runs the
	// built production binary with this flag, so metadata regressions fail
	// the build instead of shipping. 32-byte all-zeros key, base64.
	static int selfTest() {
		String publicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
		String encrypted = Secrets.encryptSecret(publicKey, "drifty-self-test");
		if (encrypted == null || encrypted.isBlank()) {
			System.err.println("self-test FAILED: empty ciphertext");
			return 1;
		}
		System.out.println("self-test OK");
		return 0;
	}

	static String optionValue(List<String> argsList, String option) {
		int index = argsList.indexOf(option);
		return (index >= 0 && index + 1 < argsList.size())
				? argsList.get(index + 1)
				: null;
	}

	private static Map<String, String> loadGithubSecrets() throws IOException {
		return parseGithubSecrets(System.getenv("DRIFTY_GITHUB_SECRETS"));
	}

	static Map<String, String> parseGithubSecrets(String githubSecretsJson)
			throws IOException {
		if (githubSecretsJson == null || githubSecretsJson.isBlank()) {
			return Map.of();
		}
		return new ObjectMapper()
				.configure(
						DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
						false
				)
				.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
				.readValue(
						githubSecretsJson,
						new TypeReference<Map<String, String>>() {
						}
				);
	}

	/**
	 * Prints every secret that fix mode needs but {@code DRIFTY_GITHUB_SECRETS}
	 * does not carry.
	 *
	 * @return whether any secret value was missing
	 */
	static boolean reportMissingSecrets(
			List<Drifty.Repository> repos,
			Map<String, String> githubSecrets
	) {
		List<String> missingSecrets = collectMissingSecrets(
				repos,
				githubSecrets
		);
		if (missingSecrets.isEmpty()) {
			return false;
		}
		System.err.println(
				"ERROR: Missing secret values in DRIFTY_GITHUB_SECRETS for fix mode:"
		);
		for (String key : missingSecrets) {
			System.err.println("  " + key);
		}
		return true;
	}

	static List<String> collectMissingSecrets(
			List<Drifty.Repository> repos,
			Map<String, String> githubSecrets
	) {
		var missingSecrets = new ArrayList<String>();
		for (Drifty.Repository repo : repos) {
			addMissingSecrets(
					missingSecrets,
					githubSecrets,
					repo.actionsSecrets,
					repo.name + "-"
			);
			for (var entry : repo.environments.entrySet()) {
				addMissingSecrets(
						missingSecrets,
						githubSecrets,
						entry.getValue().secrets,
						repo.name + "-" + entry.getKey() + "-"
				);
			}
		}
		return missingSecrets;
	}

	private static void addMissingSecrets(
			List<String> missingSecrets,
			Map<String, String> githubSecrets,
			List<String> secretNames,
			String keyPrefix
	) {
		for (String secretName : secretNames) {
			String key = keyPrefix + secretName;
			if (!githubSecrets.containsKey(key)) {
				missingSecrets.add(key);
			}
		}
	}

}
