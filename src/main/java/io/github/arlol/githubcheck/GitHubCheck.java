package io.github.arlol.githubcheck;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
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
import io.github.arlol.githubcheck.export.PklNode;
import io.github.arlol.githubcheck.export.PklWriter;
import io.github.arlol.githubcheck.export.SchemaDefaults;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.state.DriftyState;
import io.github.arlol.githubcheck.state.StateStore;

public class GitHubCheck {

	/** Arguments that stand alone. */
	static final List<String> BOOLEAN_FLAGS = List
			.of("--fix", "--self-test", "--version", "--help", "-h");

	/** Arguments that take the one argument after them. */
	static final List<String> VALUE_OPTIONS = List.of(
			"--config",
			"--state",
			"--out",
			"--schema",
			"--max-concurrent-requests"
	);

	/** The one argument that takes a list — see {@link #exportLogins}. */
	static final String EXPORT = "--export";

	static void main(String[] args)
			throws IOException, InterruptedException, ExecutionException {
		var argsList = List.of(args);
		OptionalInt answered = answerFromArgumentsAlone(argsList);
		if (answered.isPresent()) {
			System.exit(answered.orElseThrow());
			return;
		}
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
		int permits;
		try {
			permits = maxConcurrentRequests(argsList);
		} catch (IllegalArgumentException e) {
			System.err.println("ERROR: " + e.getMessage());
			System.exit(1);
			return;
		}

		if (argsList.contains("--export")) {
			List<String> exportLogins = exportLogins(argsList);
			if (exportLogins.isEmpty()) {
				System.err
						.println("ERROR: --export requires at least one login");
				System.exit(1);
				return;
			}
			if (fix || configArg != null) {
				System.err.println(
						"ERROR: --export takes no --config and no --fix"
				);
				System.exit(1);
				return;
			}
			String schema = optionValue(argsList, "--schema");
			Path out = Path.of(
					optionValue(argsList, "--out") == null ? "export.pkl"
							: optionValue(argsList, "--out")
			);
			Path exportStateFile = stateFile(statePath, out);
			var exportStore = new StateStore();
			// Loaded and saved rather than built fresh: a new DriftyState
			// written over this file would take every secret baseline with
			// it.
			DriftyState state = exportStore.load(exportStateFile);
			int exitCode;
			try {
				exitCode = ExportRunner.run(
						new GitHubClient(token, state, permits),
						exportLogins,
						out,
						schema == null ? BundledSchema.MAIN_SCHEMA_URI : schema
				);
			} finally {
				// The save runs whether or not ExportRunner.run returns: past
				// its own per-login catch it still declares IOException, and
				// an unwritable --out or a full disk throws past everything
				// above after already caching responses for however many
				// logins it got through. Not saving here would mean a retried
				// export starts that cold again instead of resuming warm. The
				// export path records no secrets of its own, so what is at
				// risk is the response cache, not any secret baseline.
				exportStore.save(exportStateFile, state);
			}
			System.exit(exitCode);
			return;
		}

		Map<String, String> githubSecrets = loadGithubSecrets();

		Path configPath = configArg != null ? Path.of(configArg)
				: Path.of("drifty.pkl");
		if (!Files.isRegularFile(configPath)) {
			System.err.println("ERROR: config file not found: " + configPath);
			System.exit(1);
			return;
		}
		long startTime = System.currentTimeMillis();

		Path stateFile = stateFile(statePath, configPath);
		var stateStore = new StateStore();
		DriftyState state = stateStore.load(stateFile);

		// The state is the client's response cache as well as the secret
		// baselines: a GET drifty has seen before is asked conditionally, and
		// GitHub charges no rate limit for the 304 that comes back.
		var client = new GitHubClient(token, state, permits);
		// Built and connected before the config is evaluated, not after: the
		// handshake and the Pkl evaluation have nothing to say to each other,
		// and the run's first wave of requests would otherwise each hold a
		// permit through it.
		client.warmUp();

		DriftyConfig config = PklConfigLoader.load(configPath.toAbsolutePath());

		if (fix && reportMissingSecrets(config, githubSecrets)) {
			System.exit(1);
			return;
		}

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

		// Unconditional: a plain check used to record nothing worth saving,
		// back when the state held only secret baselines and only --fix wrote
		// one. It now also fills the response cache across the whole run just
		// by reading, so gating this on --fix left every check cold.
		stateStore.save(stateFile, state);

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
			//
			// Running the two at once would overlap the org's three round trips
			// with the repositories' — tempting, since the org's are pure head
			// — but --fix writes in this order for a reason. A repository
			// cannot be granted access to a team the org has not created yet,
			// cannot carry a value for a custom property whose definition does
			// not exist yet, and a code security configuration attaches to
			// repositories whose own security groups are writing the same
			// settings. Each of the two fetch phases is already fanned out
			// internally; the phases themselves are sequenced by those writes.
			orgEntries.add(orgChecker.check(login, desired, repos));
			repoEntries.addAll(
					repoChecker.check(login, repos, desired.repositories)
			);
		}

		for (var entry : config.users().entrySet()) {
			String login = entry.getKey();
			List<Drifty.Repository> desired = entry.getValue().repositories;
			System.out.println("Fetching repo list for user: " + login);
			// The listing goes out on its own thread and the config's
			// repositories start at once, so the listing fails inside the
			// check rather than before it — which is why the check is inside
			// this try, and it is still the listing that failed. Nothing a
			// repository does reaches this arm, because checkOne catches
			// GitHubApiException itself.
			try {
				var listing = client.listUserReposAsync(login);
				System.out.println(
						"Fetching details while the listing finishes..."
				);
				repoEntries.addAll(repoChecker.check(login, listing, desired));
			} catch (GitHubApiException e) {
				repoEntries.addAll(
						userListingErrors(login, desired, e.getMessage())
				);
			}
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

	/**
	 * The exit code for an invocation the arguments answer on their own, before
	 * drifty needs a token, a config or a network — or empty to carry on with
	 * the run. The three are ordered: {@code --help} describes every other
	 * argument so it wins over refusing them, and an argument drifty does not
	 * recognise refuses the invocation rather than being dropped from a
	 * {@code --version} that would otherwise print.
	 */
	static OptionalInt answerFromArgumentsAlone(List<String> argsList) {
		if (handledHelp(argsList)) {
			return OptionalInt.of(0);
		}
		if (reportUnknownArguments(argsList)) {
			return OptionalInt.of(1);
		}
		if (handledVersion(argsList)) {
			return OptionalInt.of(0);
		}
		return OptionalInt.empty();
	}

	static boolean handledHelp(List<String> argsList) {
		if (!argsList.contains("--help") && !argsList.contains("-h")) {
			return false;
		}
		System.out.print(usage());
		return true;
	}

	/**
	 * Whether the invocation has to be refused, having said which arguments
	 * refused it. {@code main} exits 1 on a true, before a request is sent.
	 */
	static boolean reportUnknownArguments(List<String> argsList) {
		List<String> unknown = unknownArguments(argsList);
		if (unknown.isEmpty()) {
			return false;
		}
		System.err.println(
				"ERROR: unrecognised argument"
						+ (unknown.size() == 1 ? ": " : "s: ")
						+ String.join(" ", unknown)
		);
		System.err.println(
				"Run 'drifty --help' for the arguments drifty does recognise."
		);
		return true;
	}

	static boolean handledVersion(List<String> argsList) {
		if (!argsList.contains("--version")) {
			return false;
		}
		Package pkg = GitHubCheck.class.getPackage();
		String title = pkg.getImplementationTitle();
		String version = pkg.getImplementationVersion();
		System.out.println(title + " version \"" + version + "\"");
		return true;
	}

	/**
	 * The arguments drifty would otherwise discard, in the order they were
	 * given. {@code main} refuses the whole invocation when this is not empty,
	 * because every one of those arguments changes what the user asked for
	 * without changing what drifty does: {@code --fixx} checks and exits 1 on
	 * drift, which reads exactly like a fix that found nothing to do, and
	 * {@code --confg other.pkl} reads the very file the flag was meant to
	 * replace. That is issue #140.
	 * <p>
	 * An option swallows whatever follows it, flag-shaped or not, because
	 * {@link #optionValue} does: reporting {@code --fix} in
	 * {@code --config --fix} as unrecognised would reject an invocation whose
	 * config path the rest of the run still reads. {@code --export} swallows
	 * arguments the same way {@link #exportLogins} collects them, up to the
	 * next one starting with {@code --}.
	 */
	static List<String> unknownArguments(List<String> argsList) {
		var unknown = new ArrayList<String>();
		for (int i = 0; i < argsList.size(); i++) {
			String arg = argsList.get(i);
			if (BOOLEAN_FLAGS.contains(arg)) {
				continue;
			}
			if (VALUE_OPTIONS.contains(arg)) {
				i++;
				continue;
			}
			if (EXPORT.equals(arg)) {
				while (i + 1 < argsList.size()
						&& !argsList.get(i + 1).startsWith("--")) {
					i++;
				}
				continue;
			}
			unknown.add(arg);
		}
		return List.copyOf(unknown);
	}

	/**
	 * The only place a user is told which arguments exist.
	 * {@code usage_namesEveryArgumentDriftyAccepts} checks it against
	 * {@link #BOOLEAN_FLAGS}, {@link #VALUE_OPTIONS} and {@link #EXPORT}, so a
	 * new argument cannot be accepted without being described here.
	 */
	static String usage() {
		return """
				drifty - report and fix drift between a GitHub account's \
				settings and a Pkl config.

				Usage:
				  drifty [--fix] [--config <path>] [--state <path>]
				  drifty --export <login> [<login> ...] [--out <path>] [--schema <uri>]
				  drifty --self-test [--config <path>]
				  drifty --version
				  drifty --help

				Options:
				  --fix            Apply every fixable change instead of only reporting it.
				  --config <path>  Config to check against. Default: ./drifty.pkl
				  --state <path>   Secret baselines and cached responses to read and
				                   write. Default: drifty-state.json beside the config,
				                   or beside --out when exporting.
				  --export <login> Write the named accounts' current settings as a
				                   starting config instead of checking anything. Takes
				                   neither --config nor --fix.
				  --out <path>     Where --export writes. Default: ./export.pkl
				  --schema <uri>   The schema --export amends and omits defaults from.
				                   Default: config/drifty.pkl on drifty's main branch.
				  --max-concurrent-requests <n>
				                   How many requests may be in flight at once, 1 to 100.
				                   Default: 100, which is GitHub's limit for one token.
				                   Lower it to leave room for anything else using the
				                   same token; it costs about a tenth of a check.
				  --self-test      Run the token- and network-free smoke test and exit.
				  --version        Print the version and exit.
				  --help, -h       Print this and exit.

				Environment:
				  DRIFTY_GITHUB_TOKEN    Required, except by --help, --version and
				                         --self-test. Needs the repo, admin:org and
				                         workflow scopes.
				  DRIFTY_GITHUB_SECRETS  A JSON object of secret values. --fix needs
				                         one for every managed secret, and names the
				                         keys it is missing before writing anything.

				Exit codes:
				  0  No drift, or the requested export, version or self-test succeeded.
				  1  Drift found, a fix or export failed, or the arguments were refused.
				""";
	}

	/**
	 * Network- and token-free smoke test of the paths only the shipped binary
	 * can get wrong. {@code NativeExecutableIT} runs the built production
	 * binary with this flag, so a regression here fails the build instead of
	 * shipping.
	 * <p>
	 * Two of the three are reflective and so both able to lose their
	 * native-image metadata without a single JVM test noticing: libsodium
	 * through JNA, which crashes with {@code NoSuchMethodException} on
	 * {@code com.sun.jna.Structure$FFIType.<init>()}, and — when a config path
	 * is given — a full Pkl evaluation and mapping into {@link DriftyConfig},
	 * which ends in a {@code ConversionException}. The config is optional
	 * because a user's binary has none of its own to read; the IT passes the
	 * project's example config.
	 * <p>
	 * The third, {@code --export}'s render path, is not reflective — it is
	 * covered here because it is otherwise untested against the production
	 * binary at all: the acceptance test that exercises it needs a GitHub token
	 * and network access, neither of which a self-test may assume. It evaluates
	 * no schema, deliberately: that half of {@code --export} (loading and
	 * mapping Pkl) is the same code the config branch above already runs, and a
	 * self-test that reached the network for it would stop being one.
	 * <p>
	 * The public key is a 32-byte all-zeros key, base64.
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
		String exported = PklWriter
				.write(
						new PklNode.Obj(
								List.of(
										new PklNode.Field(
												"displayName",
												PklNode.Scalar
														.of("drifty-self-test")
										)
								)
						)
				);
		if (!exported.contains("displayName = \"drifty-self-test\"")) {
			System.err.println("self-test FAILED: export render");
			return 1;
		}
		System.out.println("self-test OK");
		return 0;
	}

	/**
	 * Every argument after {@code --export} up to the next {@code --}-prefixed
	 * one — an option's own value never starts with {@code --}, so this is the
	 * same rule {@code --config path --fix} argument parsing already relies on
	 * elsewhere in this method, just applied to a variable-length list instead
	 * of a single value.
	 */
	static List<String> exportLogins(List<String> argsList) {
		int index = argsList.indexOf("--export");
		if (index < 0) {
			return List.of();
		}
		var logins = new ArrayList<String>();
		for (int i = index + 1; i < argsList.size()
				&& !argsList.get(i).startsWith("--"); i++) {
			logins.add(argsList.get(i));
		}
		return List.copyOf(logins);
	}

	/**
	 * Where the state file lives: what {@code --state} names, or
	 * {@code drifty-state.json} beside the file the run is built around — the
	 * config a check reads, the file an export writes.
	 * <p>
	 * The two anchors agree on the case that matters. An export writes
	 * {@code export.pkl} and leaves its cache next to it, which is exactly
	 * where {@code drifty --config export.pkl} then looks, so the first check
	 * after an export is already warm.
	 */
	static Path stateFile(String statePath, Path beside) {
		return statePath != null ? Path.of(statePath)
				: beside.toAbsolutePath().resolveSibling("drifty-state.json");
	}

	/**
	 * How many requests may be in flight at once. A token given to drifty is
	 * normally drifty's alone, so {@code GitHubClient} defaults to the whole
	 * hundred GitHub documents rather than to a margin under it. A token drifty
	 * shares is what wants the margin back, and lowering it costs about a tenth
	 * of a check. Drifty cannot know whether the token is shared and the person
	 * running it can.
	 */
	static int maxConcurrentRequests(List<String> argsList) {
		String value = optionValue(argsList, "--max-concurrent-requests");
		if (value == null) {
			return GitHubClient.MAX_CONCURRENT_REQUESTS;
		}
		int requested;
		try {
			requested = Integer.parseInt(value);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(
					"--max-concurrent-requests must be a number, not " + value,
					e
			);
		}
		if (requested < 1 || requested > GitHubClient.CONCURRENCY_CEILING) {
			throw new IllegalArgumentException(
					"--max-concurrent-requests must be between 1 and "
							+ GitHubClient.CONCURRENCY_CEILING + ", not "
							+ requested
			);
		}
		return requested;
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
			var managed = ManagedGroups.of(org.getValue().managed);
			if (managed.manages(Drifty.OrgGroupName.ORG_ACTION_SECRETS)) {
				addMissingSecrets(
						missingSecrets,
						githubSecrets,
						org.getValue().actionsSecrets.keySet(),
						"org-" + org.getKey() + "-"
				);
			}
			if (managed.manages(Drifty.OrgGroupName.ORG_WEBHOOKS)) {
				addMissingSecrets(
						missingSecrets,
						githubSecrets,
						webhookSecretNames(org.getValue().webhooks),
						"org-" + org.getKey() + "-webhook-"
				);
			}
		}
		for (Drifty.Repository repo : config.allRepositories()) {
			// The same narrowing RepositoryChecker.checkOne applies, for the
			// same reason: a repository the config wants archived is compared
			// on `archived` alone, so none of its declared secrets is ever
			// written and demanding a value for one aborts a --fix over work
			// it was never going to do.
			ManagedGroups<Drifty.GroupName> managed = repo.archived
					? ManagedGroups.of(repo.managed)
							.and(RepositoryStateReader.ARCHIVED_ONLY)
					: ManagedGroups.of(repo.managed);
			if (managed.manages(Drifty.GroupName.ACTION_SECRETS)) {
				addMissingSecrets(
						missingSecrets,
						githubSecrets,
						repo.actionsSecrets,
						repo.name + "-"
				);
			}
			if (managed.manages(Drifty.GroupName.WEBHOOKS)) {
				addMissingSecrets(
						missingSecrets,
						githubSecrets,
						webhookSecretNames(repo.webhooks),
						repo.name + "-webhook-"
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

	/** The names of the hooks whose config declares a secret. */
	private static List<String> webhookSecretNames(
			Map<String, Drifty.Webhook> webhooks
	) {
		return webhooks.entrySet()
				.stream()
				.filter(entry -> entry.getValue().secret)
				.map(Map.Entry::getKey)
				.toList();
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
