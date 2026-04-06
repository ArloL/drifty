package io.github.arlol.githubcheck;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import io.github.arlol.githubcheck.client.Rule;
import io.github.arlol.githubcheck.client.WorkflowPermissions;
import io.github.arlol.githubcheck.config.BranchProtectionArgs;
import io.github.arlol.githubcheck.config.CodeScanningToolArgs;
import io.github.arlol.githubcheck.config.RepositoryArgs;
import io.github.arlol.githubcheck.config.RulesetArgs;
import io.github.arlol.githubcheck.config.StatusCheckArgs;

public class GitHubCheck {

	static void main(String[] args)
			throws IOException, InterruptedException, ExecutionException {
		String token = System.getenv("DRIFTY_GITHUB_TOKEN");
		if (token == null || token.isBlank()) {
			System.err.println(
					"ERROR: DRIFTY_GITHUB_TOKEN environment variable not set"
			);
			System.exit(1);
		}

		boolean fix = List.of(args).contains("--fix");

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

		if (fix) {
			var missingSecrets = new ArrayList<String>();
			for (RepositoryArgs repo : repositories()) {
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

		var checker = new OrgChecker(token, "ArloL", fix, githubSecrets);
		CheckResult result = checker.check(repositories());
		checker.printReport(result);

		double totalSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
		System.out
				.printf("%nTotal execution time: %.2f seconds%n", totalSeconds);

		System.exit(result.hasDrift() ? 1 : 0);
	}

	static List<RepositoryArgs> repositories() {
		// Default branch protection for all public repos
		var defaultBranchProtection = BranchProtectionArgs.builder("main")
				.requiredStatusChecks(
						Set.of(
								StatusCheckArgs.builder()
										.context(
												"check-actions.required-status-check"
										)
										.appId(
												StatusCheckArgs.APP_ID_GITHUB_ACTIONS
										)
										.build(),
								StatusCheckArgs.builder()
										.context(
												"codeql-analysis.required-status-check"
										)
										.appId(
												StatusCheckArgs.APP_ID_GITHUB_ACTIONS
										)
										.build(),
								StatusCheckArgs.builder()
										.context("CodeQL")
										.appId(
												StatusCheckArgs.APP_ID_GITHUB_ADVANCED_SECURITY
										)
										.build()
						)
				)
				.enforceAdmins(true)
				.requiredLinearHistory(true)
				.build();

		// Default ruleset mirroring legacy branch protection for all public
		// repos
		var defaultRuleset = RulesetArgs.builder("main-branch-rules")
				.includePatterns("refs/heads/main")
				.requiredLinearHistory(true)
				.noForcePushes(true)
				.requiredStatusChecks(
						StatusCheckArgs.builder()
								.context("check-actions.required-status-check")
								.appId(StatusCheckArgs.APP_ID_GITHUB_ACTIONS)
								.build(),
						StatusCheckArgs.builder()
								.context(
										"codeql-analysis.required-status-check"
								)
								.appId(StatusCheckArgs.APP_ID_GITHUB_ACTIONS)
								.build(),
						StatusCheckArgs.builder()
								.context("CodeQL")
								.appId(
										StatusCheckArgs.APP_ID_GITHUB_ADVANCED_SECURITY
								)
								.build()
				)
				.requiredCodeScanning(
						CodeScanningToolArgs.builder()
								.tool("CodeQL")
								.alertsThreshold(Rule.AlertsThreshold.ERRORS)
								.securityAlertsThreshold(
										Rule.SecurityAlertsThreshold.HIGH_OR_HIGHER
								)
								.build(),
						CodeScanningToolArgs.builder()
								.tool("zizmor")
								.alertsThreshold(Rule.AlertsThreshold.ERRORS)
								.securityAlertsThreshold(
										Rule.SecurityAlertsThreshold.HIGH_OR_HIGHER
								)
								.build()
				)
				.build();

		var defaultRepository = RepositoryArgs.create("ArloL", "default")
				.rulesets(defaultRuleset)
				.branchProtections(defaultBranchProtection)
				.automatedSecurityFixes(true)
				.allowMergeCommit(false)
				.allowSquashMerge(false)
				.allowAutoMerge(true)
				.deleteBranchOnMerge(true)
				.defaultWorkflowPermissions(
						WorkflowPermissions.DefaultWorkflowPermissions.READ
				)
				.canApprovePullRequestReviews(true)
				.build();

		// Variant for repos with the main CI required status check
		var mainCiRuleset = defaultRuleset.toBuilder()
				.addRequiredStatusChecks(
						StatusCheckArgs.builder()
								.context("main.required-status-check")
								.appId(StatusCheckArgs.APP_ID_GITHUB_ACTIONS)
								.build()
				)
				.build();

		// Variant for repos with the test required status check
		var testCiRuleset = defaultRuleset.toBuilder()
				.addRequiredStatusChecks(
						StatusCheckArgs.builder()
								.context("test.required-status-check")
								.appId(StatusCheckArgs.APP_ID_GITHUB_ACTIONS)
								.build()
				)
				.build();

		// Group: GitHub Pages sites
		var pagesSite = defaultRepository.toBuilder().pages().build();
		var pagesSites = List.of(
				pagesSite.toBuilder()
						.name("abenteuer-irland")
						.description("Mum's website for Abenteuer Irland")
						.homepageUrl(
								"https://arlol.github.io/abenteuer-irland/"
						)
						.build(),
				pagesSite.toBuilder()
						.name("angular-playground")
						.description("A playground for the Angular framework")
						.homepageUrl(
								"https://arlol.github.io/angular-playground/"
						)
						.updateBranchProtection("main", builder -> {
							builder.addRequiredStatusChecks(
									StatusCheckArgs.builder()
											.context(
													"pr-check.required-status-check"
											)
											.appId(
													StatusCheckArgs.APP_ID_GITHUB_ACTIONS
											)
											.build()
							);
						})
						.rulesets(
								defaultRuleset.toBuilder()
										.addRequiredStatusChecks(
												StatusCheckArgs.builder()
														.context(
																"pr-check.required-status-check"
														)
														.appId(
																StatusCheckArgs.APP_ID_GITHUB_ACTIONS
														)
														.build()
										)
										.build()
						)
						.build(),
				pagesSite.toBuilder()
						.name("arlol.github.io")
						.description("This is the source of my GitHub page")
						.homepageUrl("https://arlol.github.io/")
						.build(),
				pagesSite.toBuilder()
						.name("bulma-playground")
						.description("A playground for the Bulma CSS framework")
						.homepageUrl(
								"https://arlol.github.io/bulma-playground/"
						)
						.build(),
				pagesSite.toBuilder()
						.name("business-english")
						.description("Mum's website for Business English")
						.homepageUrl(
								"https://arlol.github.io/business-english/"
						)
						.build(),
				pagesSite.toBuilder()
						.name(
								"demo-html5up-future-imperfect-scroll-padding-top"
						)
						.description("A quick demo of scroll-padding-top")
						.homepageUrl(
								"https://arlol.github.io/demo-html5up-future-imperfect-scroll-padding-top/"
						)
						.build(),
				pagesSite.toBuilder()
						.name("eclipse-projects")
						.description(
								"Arlo's project catalog for the Eclipse Installer"
						)
						.homepageUrl(
								"https://arlol.github.io/eclipse-projects/"
						)
						.build()
		);

		// Group: repos with a main CI required status check
		var mainCiRepo = defaultRepository.toBuilder()
				.updateBranchProtection("main", builder -> {
					builder.addRequiredStatusChecks(
							StatusCheckArgs.builder()
									.context("main.required-status-check")
									.appId(
											StatusCheckArgs.APP_ID_GITHUB_ACTIONS
									)
									.build()
					);
				})
				.rulesets(mainCiRuleset)
				.build();
		var mainCiRepos = List.of(
				mainCiRepo.toBuilder()
						.name("chorito")
						.description(
								"A tool that does some chores in your source code"
						)
						.actionsSecrets("PAT")
						.build(),
				mainCiRepo.toBuilder()
						.name("drifty")
						.description(
								"Detect and fix drift of GitHub repository settings"
						)
						.build(),
				mainCiRepo.toBuilder()
						.name("git-dora-lead-time-calculator")
						.description(
								"A project to calculate the DORA metric lead time with the info from a git repo"
						)
						.build(),
				mainCiRepo.toBuilder()
						.name("mvnx")
						.description(
								"An experiment with Maven dependencies and dynamic classloading"
						)
						.build(),
				mainCiRepo.toBuilder()
						.name("myprojects-cleaner")
						.description(
								"A java application that runs git clean in a bunch of directories"
						)
						.build(),
				mainCiRepo.toBuilder()
						.name("newlinechecker")
						.description(
								"A sample project to play with GraalVM builds on GitHub Actions"
						)
						.immutableReleases(true)
						.build(),
				mainCiRepo.toBuilder()
						.name("rss-to-mail")
						.description(
								"Read from RSS feeds and send an email for every new item"
						)
						.build(),
				mainCiRepo.toBuilder()
						.name("wait-for-ports")
						.description(
								"A command-line utility that waits until a port is open"
						)
						.build(),
				mainCiRepo.toBuilder()
						.name("webapp-classloader-test")
						.description(
								"This is a test that can be used during integration testing to check for classloader leaks"
						)
						.build(),
				mainCiRepo.toBuilder()
						.name("website-janitor")
						.description(
								"A set of tools that check websites for common misconfigurations or downtime"
						)
						.build()
		);

		// Individual repos with unique configurations
		var individual = List.of(
				defaultRepository.toBuilder()
						.name("advent-of-code")
						.description("My advent of code solutions")
						.rulesets(defaultRuleset)
						.build(),
				defaultRepository.toBuilder()
						.name("beatunes-keytocomment")
						.description(
								"A beatunes plugin that writes the key to the comment"
						)
						.rulesets(defaultRuleset)
						.build(),
				defaultRepository.toBuilder()
						.name("calver-tag-action")
						.description(
								"A GitHub Actions action that creates a new version using a CalVer-style derivative and pushes it"
						)
						.immutableReleases(true)
						.rulesets(defaultRuleset)
						.build(),
				defaultRepository.toBuilder()
						.name("corporate-python")
						.description(
								"A container for executing python in corporate environments"
						)
						.actionsSecrets(
								"DOCKER_HUB_ACCESS_TOKEN",
								"DOCKER_HUB_USERNAME"
						)
						.rulesets(defaultRuleset)
						.build(),
				defaultRepository.toBuilder()
						.name("dependabot-dockerfile-test")
						.description(
								"A test to see whether dependabot updates dockerfiles with args"
						)
						.rulesets(defaultRuleset)
						.build(),
				defaultRepository.toBuilder()
						.name("dotfiles")
						.description(
								"My collection of dotfiles used to configure my command line environments"
						)
						.rulesets(defaultRuleset)
						.build(),
				defaultRepository.toBuilder()
						.name("effortful-retrieval-questions")
						.description(
								"A collection of effortful retrieval questions of a number of articles I've read"
						)
						.rulesets(defaultRuleset)
						.build(),
				defaultRepository.toBuilder()
						.name("git-presentation-2018-10")
						.description(
								"Git Präsentation für Vorlesung Industrielle Softwareentwicklung"
						)
						.rulesets(defaultRuleset)
						.build(),
				defaultRepository.toBuilder()
						.name("homebrew-tap")
						.description(
								"A homebrew tap for my own formulas and casks"
						)
						.rulesets(defaultRuleset)
						.build(),
				defaultRepository.toBuilder()
						.name("kafka-debugger")
						.description(
								"A small jar utility to test kafka connections"
						)
						.rulesets(defaultRuleset)
						.build(),
				defaultRepository.toBuilder()
						.name("menubar-scripts")
						.description(
								"A collection of scripts that can run in e.g. xbar, swiftbar, etc."
						)
						.rulesets(defaultRuleset)
						.build(),
				defaultRepository.toBuilder()
						.name("music-stuff")
						.description("Some spotify and beatunes stuff")
						.updateBranchProtection("main", builder -> {
							builder.addRequiredStatusChecks(
									StatusCheckArgs.builder()
											.context(
													"test.required-status-check"
											)
											.appId(
													StatusCheckArgs.APP_ID_GITHUB_ACTIONS
											)
											.build()
							);
						})
						.rulesets(testCiRuleset)
						.build(),
				defaultRepository.toBuilder()
						.name("nope-amine")
						.description(
								"A firefox extension that slowly increases the time for things to load on reddit.com"
						)
						.rulesets(defaultRuleset)
						.build(),
				defaultRepository.toBuilder()
						.name("open-webui-runner")
						.description(
								"A small repo to run open-webui locally and stop it after using it"
						)
						.rulesets(defaultRuleset)
						.build(),
				defaultRepository.toBuilder()
						.name("postgres-sync-demo")
						.description(
								"A demo on how to use triggers, queues, etc. to sync the app's data somewhere else"
						)
						.rulesets(defaultRuleset)
						.build(),
				defaultRepository.toBuilder()
						.name("python-nc")
						.description(
								"A test to see if I can implement nc's proxy functionality with python"
						)
						.rulesets(defaultRuleset)
						.build(),
				defaultRepository.toBuilder()
						.name("sci-fi-movies")
						.description(
								"an app to import sci fi movies from rotten tomatoes into a database in order to run queries on them"
						)
						.rulesets(defaultRuleset)
						.build(),
				defaultRepository.toBuilder()
						.name("terraform-github")
						.description(
								"A project to manage github settings with terraform"
						)
						.environment(
								"production",
								env -> env.secrets("TF_GITHUB_TOKEN")
						)
						.rulesets(defaultRuleset)
						.build(),
				defaultRepository.toBuilder()
						.name("tsaf-parser")
						.description("Binary format exploration")
						.updateBranchProtection("main", builder -> {
							builder.addRequiredStatusChecks(
									StatusCheckArgs.builder()
											.context(
													"test.required-status-check"
											)
											.appId(
													StatusCheckArgs.APP_ID_GITHUB_ACTIONS
											)
											.build()
							);
						})
						.rulesets(testCiRuleset)
						.build(),
				defaultRepository.toBuilder()
						.name("vagrant-ssh-config")
						.description(
								"A vagrant plugin that automatically creates ssh configs for vms"
						)
						.rulesets(defaultRuleset)
						.build()
		);

		// Archived repos
		var archived = Stream
				.of(
						"actions",
						"actions-checkout-fetch-depth-demo",
						"airmac",
						"campuswoche-2018-webseiten-steuern",
						"chop-kata",
						"dotnet-http-client-reproduction",
						"gitfx",
						"graalfx",
						"gwt-dragula-test",
						"gwt-log-print-style-demo",
						"gwt-refresh-demo",
						"HalloJSX",
						"HelloCocoaHTTPServer",
						"HelloIntAirActServer",
						"HelloRoutingServer",
						"HelloServer",
						"iebox",
						"ilabwebworkshop",
						"IntAirAct",
						"IntAirAct-Performance",
						"jBrowserDriver",
						"jbrowserdriver-cucumber-integration-tests",
						"jbrowserdriver-test",
						"jdk-newinstance-leak-demo",
						"jdk8u144-classloader-leak-demo-webapp",
						"jhipster-app",
						"json-smart-dependency-resolution-test",
						"m2e-wro4j-bug-demo",
						"m2e-wro4j-bug-demo2",
						"maven-quickstart-j2objc",
						"Mirror",
						"modern-ie-vagrant",
						"MWPhotoBrowser",
						"npmrc-github-action",
						"packer-templates",
						"pico-playground",
						"postgres-query-error-demo",
						"quickstart-buck-bazel-maven",
						"selenium-xp-ie6",
						"self-hosted-gh-actions-runner",
						"spring-cloud-context-classloader-leak-demo",
						"spring-configuration-processor-metadata-bug-demo",
						"spring-security-drupal-password-encoder",
						"testcontainers-colima-github-actions",
						"toado",
						"vagrant-1",
						"vitest-link-reproduction",
						"vitest-mocking-reproduction",
						"workflow-dispatch-input-defaults"
				)
				.map(
						name -> RepositoryArgs.create("ArloL", name)
								.archived()
								.build()
				)
				.toList();

		return Stream.of(pagesSites, mainCiRepos, individual, archived)
				.flatMap(List::stream)
				.toList();
	}

}
