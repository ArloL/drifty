package io.github.arlol.githubcheck;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import io.github.arlol.githubcheck.client.GitHubApiException;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.OrganizationResponse;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.RepositorySummaryResponse;
import io.github.arlol.githubcheck.client.SimpleUser;
import io.github.arlol.githubcheck.drift.ManagedGroups;
import io.github.arlol.githubcheck.export.AccountExporter;
import io.github.arlol.githubcheck.export.DriftyFileExporter;
import io.github.arlol.githubcheck.export.PklNode;
import io.github.arlol.githubcheck.export.RepositoryExporter;
import io.github.arlol.githubcheck.export.SchemaDefaults;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.state.DriftyState;

/**
 * Drives {@code drifty --export}: resolves each login to an organization or a
 * personal account, reads everything {@link OrganizationChecker} and
 * {@link RepositoryChecker} can reach with every group managed, and writes the
 * result through {@link DriftyFileExporter}.
 * <p>
 * Every group read here goes through a {@link FetchFailures#collecting()}
 * instance rather than the {@link FetchFailures#STRICT} a check or fix run
 * uses: a token missing one scope should produce a file with a note where that
 * group would sit, not abort the whole account the way a 403 aborts an entry in
 * {@code GitHubCheck.check}.
 * <p>
 * {@link RepositoryChecker#checkOne} is not used here, because it does not
 * catch {@link GitHubApiException} at all (a pre-existing gap, not this class's
 * to fix) — {@code fetchState} is called directly instead, wrapped in its own
 * {@code try/catch} per repository, so one repository's 403 does not discard
 * every other repository the account has.
 * <p>
 * The file is written only when at least one login produced an entry: writing
 * an empty {@code organizations {}} because every login failed would silently
 * discard whatever a previous good run left at {@code --out}.
 */
final class ExportRunner {

	private ExportRunner() {
	}

	static int run(
			GitHubClient client,
			List<String> logins,
			Path out,
			String schemaUri
	) throws IOException {
		SchemaDefaults defaults = SchemaDefaults.of(schemaUri);
		List<PklNode.Member> organizations = new ArrayList<>();
		List<PklNode.Member> users = new ArrayList<>();
		List<FetchFailures.Failure> unreadableGroups = new ArrayList<>();
		boolean anyFailed = false;

		for (String login : logins) {
			try {
				Optional<OrganizationResponse> organization = client
						.getOrganization(login);
				if (organization.isPresent()) {
					organizations.add(
							exportOrganization(
									client,
									login,
									defaults,
									unreadableGroups
							)
					);
				} else {
					Optional<PklNode.Member> userEntry = exportUserIfOwnAccount(
							client,
							login,
							defaults,
							unreadableGroups
					);
					if (userEntry.isPresent()) {
						users.add(userEntry.orElseThrow());
					} else {
						anyFailed = true;
					}
				}
			} catch (GitHubApiException e) {
				System.err.println("ERROR: " + login + ": " + e.getMessage());
				anyFailed = true;
			}
		}

		reportUnreadableGroups(unreadableGroups);

		// Every login failed outright (or none was given): writing would
		// overwrite a good export.pkl from an earlier run with an empty
		// organizations {}, which is worse than leaving it untouched.
		if (organizations.isEmpty() && users.isEmpty()) {
			return anyFailed ? 1 : 0;
		}

		String text = DriftyFileExporter.file(
				schemaUri,
				version(),
				Instant.now(),
				organizations,
				users
		);
		Path outDir = out.toAbsolutePath().getParent();
		if (outDir != null) {
			Files.createDirectories(outDir);
		}
		Files.writeString(out, text);
		System.out.printf("Wrote %s (%d lines)%n", out, text.lines().count());

		return anyFailed ? 1 : 0;
	}

	/**
	 * The file's own {@code //} notes already say this per group, but a
	 * scripted {@code drifty --export acme && drifty --fix} never reads the
	 * file — this is what makes a token missing a scope visible without opening
	 * it. Exit code 0 stands regardless: gaps inside a file are comments, not
	 * failures, per the spec.
	 */
	private static void reportUnreadableGroups(
			List<FetchFailures.Failure> unreadableGroups
	) {
		if (unreadableGroups.isEmpty()) {
			return;
		}
		String names = unreadableGroups.stream()
				.map(FetchFailures.Failure::group)
				.distinct()
				.sorted()
				.collect(Collectors.joining(", "));
		System.err.printf(
				"%d group(s) could not be read and are noted in the file instead: %s%n",
				unreadableGroups.size(),
				names
		);
	}

	/**
	 * {@code /user/repos} serves the authenticated user alone, so a login that
	 * is not an organization is exportable only when it is the token's own
	 * account — otherwise this would silently emit the token owner's
	 * repositories under somebody else's name.
	 *
	 * @return the {@code users} entry, or empty when the login is a personal
	 *         account that does not belong to this token — already reported to
	 *         stderr, so the caller only needs to know exporting it failed
	 */
	private static Optional<PklNode.Member> exportUserIfOwnAccount(
			GitHubClient client,
			String login,
			SchemaDefaults defaults,
			List<FetchFailures.Failure> unreadableGroups
	) {
		SimpleUser authenticatedUser = client.getAuthenticatedUser();
		if (!login.equals(authenticatedUser.login())) {
			System.err.println(
					"ERROR: " + login
							+ " is not an organization, and a personal account can only be exported by its own token"
			);
			return Optional.empty();
		}
		System.out.println("Fetching repo list for user: " + login);
		List<RepositorySummaryResponse> repos = client.listUserRepos(login);
		System.out.printf("Found %d repos.%n", repos.size());
		List<PklNode> repositories = exportRepositories(
				client,
				login,
				repos,
				defaults,
				unreadableGroups
		);
		return Optional.of(AccountExporter.user(login, repositories));
	}

	private static PklNode.Member exportOrganization(
			GitHubClient client,
			String login,
			SchemaDefaults defaults,
			List<FetchFailures.Failure> unreadableGroups
	) {
		System.out.println("Fetching repo list for organization: " + login);
		var orgChecker = new OrganizationChecker(
				client,
				false,
				Map.of(),
				new DriftyState(),
				FetchFailures.collecting()
		);
		OrganizationState state = orgChecker.fetchState(
				login,
				ManagedGroups.all(Drifty.OrgGroupName.class)
		);
		unreadableGroups.addAll(orgChecker.fetchFailures());
		List<RepositorySummaryResponse> repos = client.listOrgRepos(login)
				.orElse(List.of());
		System.out.printf("Found %d repos.%n", repos.size());
		List<PklNode> repositories = exportRepositories(
				client,
				login,
				repos,
				defaults,
				unreadableGroups
		);
		return AccountExporter.organization(
				state,
				orgChecker.fetchFailures(),
				repositories,
				defaults
		);
	}

	/**
	 * One repository at a time, each through its own fresh
	 * {@link RepositoryChecker} and its own {@link FetchFailures#collecting()}:
	 * reusing one collector across the loop would attribute one repository's
	 * group failures to whichever repository rendered next, since
	 * {@code Failure} carries only a group name, not which repository it
	 * belongs to. A group's read failing does not abort the repository either
	 * way, and a repository whose own details ({@code GET
	 * /repos/{owner}/{repo}}, never wrapped by {@link FetchFailures}) cannot be
	 * read at all becomes a note in the listing instead of losing every other
	 * repository's export.
	 * <p>
	 * Sorted by name before fetching anything: GitHub's own listing order is
	 * not stable across runs, and every other collection this export writes is
	 * sorted for the same reason — two exports of an unchanged account must
	 * produce the identical file.
	 */
	private static List<PklNode> exportRepositories(
			GitHubClient client,
			String owner,
			List<RepositorySummaryResponse> repos,
			SchemaDefaults defaults,
			List<FetchFailures.Failure> unreadableGroups
	) {
		var sorted = repos.stream()
				.sorted(Comparator.comparing(RepositorySummaryResponse::name))
				.toList();
		var entries = new ArrayList<PklNode>();
		for (RepositorySummaryResponse summary : sorted) {
			var repositoryChecker = new RepositoryChecker(
					client,
					false,
					Map.of(),
					new DriftyState(),
					FetchFailures.collecting()
			);
			try {
				RepositoryState state = repositoryChecker.fetchState(
						new RepoRef(owner, summary.name()),
						summary,
						ManagedGroups.all(Drifty.GroupName.class)
				);
				unreadableGroups.addAll(repositoryChecker.fetchFailures());
				entries.add(
						RepositoryExporter.entry(
								state,
								repositoryChecker.fetchFailures(),
								defaults
						)
				);
			} catch (GitHubApiException e) {
				entries.add(
						new PklNode.Note(
								summary.name() + ": "
										+ FetchFailures.Collecting
												.firstLine(e.getMessage())
						)
				);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				entries.add(
						new PklNode.Note(summary.name() + ": " + e.getMessage())
				);
			} catch (IOException e) {
				entries.add(
						new PklNode.Note(summary.name() + ": " + e.getMessage())
				);
			}
		}
		return List.copyOf(entries);
	}

	/**
	 * The manifest attribute {@code handledVersion} also reads, which is
	 * {@code null} under surefire because no manifest is on the test classpath
	 * — so the header degrades to a word that is still honest rather than the
	 * literal text "drifty null".
	 */
	private static String version() {
		String version = GitHubCheck.class.getPackage()
				.getImplementationVersion();
		return version == null ? "unknown" : version;
	}

}
