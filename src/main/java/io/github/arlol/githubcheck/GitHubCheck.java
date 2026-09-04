package io.github.arlol.githubcheck;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import io.github.arlol.githubcheck.client.GitHubApiException;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepositorySummaryResponse;
import io.github.arlol.githubcheck.client.Secrets;
import io.github.arlol.githubcheck.drift.ManagedGroups;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.state.DriftyState;
import io.github.arlol.githubcheck.state.StateStore;

public class GitHubCheck {

	static void main(String[] args)
			throws IOException, InterruptedException, ExecutionException {
		if (handledVersion(args)) {
			return;
		}
		var argsList = List.of(args);
		if (argsList.contains("--self-test")) {
			System.exit(selfTest(optionValue(argsList, "--config")));
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

		if (fix && reportMissingSecrets(config, githubSecrets)) {
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
		long startFetch = System.currentTimeMillis();

		CheckResult result = check(config, client, orgChecker, repoChecker);

		System.out.printf(
				"Fetch complete in %.2f seconds%n%n",
				(System.currentTimeMillis() - startFetch) / 1000.0
		);

		Report.print(result);

		if (fix) {
			stateStore.save(stateFile, state);
		}

		double totalSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
		System.out
				.printf("%nTotal execution time: %.2f seconds%n", totalSeconds);

		System.exit(result.hasDrift() ? 1 : 0);
	}

	/**
	 * Checks every account the config declares, organizations first.
	 * <p>
	 * A repository listing that fails is reported against the account it
	 * belongs to, and the run continues with the next one. Letting the
	 * exception out ended the whole run instead: no report for any account, and
	 * a stack trace whose exit code 1 is the one drifty also uses for "drift
	 * detected". The 404 is the case that was handled; 403 and 500 are the ones
	 * a scoped token actually meets.
	 */
	static CheckResult check(
			DriftyConfig config,
			GitHubClient client,
			OrganizationChecker orgChecker,
			RepositoryChecker repoChecker
	) throws InterruptedException, ExecutionException {
		var orgEntries = new ArrayList<CheckResult.Entry>();
		var repoEntries = new ArrayList<CheckResult.Entry>();

		for (var entry : config.organizations().entrySet()) {
			String login = entry.getKey();
			Drifty.Organization desired = entry.getValue();
			System.out.println("Fetching repo list for organization: " + login);
			List<RepositorySummaryResponse> repos;
			try {
				Optional<List<RepositorySummaryResponse>> listed = client
						.listOrgRepos(login);
				if (listed.isEmpty()) {
					orgEntries.add(CheckResult.Entry.missing(login));
					desired.repositories.forEach(
							r -> repoEntries
									.add(CheckResult.Entry.missing(r.name))
					);
					continue;
				}
				repos = listed.orElseThrow();
			} catch (GitHubApiException e) {
				orgEntries.add(CheckResult.Entry.error(login, e.getMessage()));
				repoEntries.addAll(
						listingErrors(desired.repositories, e.getMessage())
				);
				continue;
			}
			System.out.printf(
					"Found %d repos. Fetching details in parallel...%n",
					repos.size()
			);
			// The org is checked before its repositories, and both work from
			// the same listing: an org secret's selected repositories arrive as
			// ids, and this is where their names are.
			orgEntries.add(orgChecker.check(login, desired, repos));
			repoEntries.addAll(
					repoChecker.check(login, repos, desired.repositories)
			);
		}

		for (var entry : config.users().entrySet()) {
			String login = entry.getKey();
			List<Drifty.Repository> desired = entry.getValue().repositories;
			System.out.println("Fetching repo list for user: " + login);
			List<RepositorySummaryResponse> repos;
			try {
				repos = client.listUserRepos(login);
			} catch (GitHubApiException e) {
				repoEntries.addAll(
						userListingErrors(login, desired, e.getMessage())
				);
				continue;
			}
			System.out.printf(
					"Found %d repos. Fetching details in parallel...%n",
					repos.size()
			);
			repoEntries.addAll(repoChecker.check(login, repos, desired));
		}

		return new CheckResult(orgEntries, repoEntries);
	}

	/**
	 * The listing failure reported against every repository the account
	 * declares. Errors, not MISSING: missing says GitHub answered and did not
	 * have the repository, which a listing that failed never established.
	 */
	private static List<CheckResult.Entry> listingErrors(
			List<Drifty.Repository> desired,
			String error
	) {
		return desired.stream()
				.map(repo -> CheckResult.Entry.error(repo.name, error))
				.toList();
	}

	/**
	 * The same for a personal account, which has no entry of its own in the
	 * report: a user block declaring no repository would leave the failure
	 * unsaid and the run would exit 0, so the account stands in for itself.
	 */
	private static List<CheckResult.Entry> userListingErrors(
			String login,
			List<Drifty.Repository> desired,
			String error
	) {
		return desired.isEmpty()
				? List.of(CheckResult.Entry.error(login, error))
				: listingErrors(desired, error);
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

	/**
	 * Network- and token-free smoke test of the two paths only the shipped
	 * binary can get wrong, both of them reflective and so both able to lose
	 * their native-image metadata without a single JVM test noticing: libsodium
	 * through JNA, which crashes with {@code NoSuchMethodException} on
	 * {@code com.sun.jna.Structure$FFIType.<init>()}, and — when a config path
	 * is given — a full Pkl evaluation and mapping into {@link DriftyConfig},
	 * which ends in a {@code ConversionException}. {@code NativeExecutableIT}
	 * runs the built production binary with this flag, so those regressions
	 * fail the build instead of shipping. The config is optional because a
	 * user's binary has none of its own to read; the IT passes the project's
	 * example config. The public key is a 32-byte all-zeros key, base64.
	 */
	static int selfTest(String configPath) {
		String publicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
		String encrypted = Secrets.encryptSecret(publicKey, "drifty-self-test");
		if (encrypted == null || encrypted.isBlank()) {
			System.err.println("self-test FAILED: empty ciphertext");
			return 1;
		}
		if (configPath != null) {
			DriftyConfig config;
			try {
				config = PklConfigLoader
						.load(Path.of(configPath).toAbsolutePath());
			} catch (IOException | RuntimeException e) {
				System.err.println(
						"self-test FAILED: cannot load " + configPath + ": " + e
				);
				return 1;
			}
			if (config.organizations().isEmpty() && config.users().isEmpty()) {
				System.err.println(
						"self-test FAILED: no accounts in " + configPath
				);
				return 1;
			}
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
			DriftyConfig config,
			Map<String, String> githubSecrets
	) {
		List<String> missingSecrets = collectMissingSecrets(
				config,
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

	/**
	 * The secrets fix mode needs a value for, which is only the ones it would
	 * write: an entity's own {@code managed} declaration decides that, the same
	 * way it decides which groups get built and which requests get sent.
	 * Reading the declarations alone aborted the run over secrets drifty was
	 * never going to touch — the case for excluding a secret group in the first
	 * place is a repository whose secret values you do not have.
	 */
	static List<String> collectMissingSecrets(
			DriftyConfig config,
			Map<String, String> githubSecrets
	) {
		var missingSecrets = new ArrayList<String>();
		// An org secret's key carries an "org-" prefix, because the map is flat
		// and an organization may share its name with a repository.
		for (var org : config.organizations().entrySet()) {
			if (!ManagedGroups.of(org.getValue().managed)
					.manages(Drifty.OrgGroupName.ORG_ACTION_SECRETS)) {
				continue;
			}
			addMissingSecrets(
					missingSecrets,
					githubSecrets,
					org.getValue().actionsSecrets.keySet(),
					"org-" + org.getKey() + "-"
			);
		}
		for (Drifty.Repository repo : config.allRepositories()) {
			ManagedGroups<Drifty.GroupName> managed = ManagedGroups
					.of(repo.managed);
			if (managed.manages(Drifty.GroupName.ACTION_SECRETS)) {
				addMissingSecrets(
						missingSecrets,
						githubSecrets,
						repo.actionsSecrets,
						repo.name + "-"
				);
			}
			if (!managed.manages(Drifty.GroupName.ENVIRONMENT_SECRETS)) {
				continue;
			}
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
			Collection<String> secretNames,
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
