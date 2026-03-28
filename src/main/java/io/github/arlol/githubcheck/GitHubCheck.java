package io.github.arlol.githubcheck;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import io.github.arlol.githubcheck.config.RepositoryArgs;
import io.github.arlol.githubcheck.config.RulesetArgs;

public class GitHubCheck {

	static void main(String[] args)
			throws IOException, InterruptedException, ExecutionException {
		String token = System.getenv("GITHUB_TOKEN");
		if (token == null || token.isBlank()) {
			System.err.println(
					"ERROR: GITHUB_TOKEN environment variable not set"
			);
			System.exit(1);
		}

		boolean fix = List.of(args).contains("--fix");

		long startTime = System.currentTimeMillis();

		var checker = new OrgChecker(token, "ArloL", fix);
		CheckResult result = checker.check(repositories());
		checker.printReport(result);

		double totalSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
		System.out
				.printf("%nTotal execution time: %.2f seconds%n", totalSeconds);

		System.exit(result.hasDrift() ? 1 : 0);
	}

	static List<RepositoryArgs> repositories() {
		// Default ruleset mirroring legacy branch protection for all public
		// repos
		var defaultRuleset = RulesetArgs.builder("main-branch-rules")
				.includePatterns("refs/heads/main")
				.requiredLinearHistory(true)
				.noForcePushes(true)
				.requiredStatusChecks(
						OrgChecker.BASE_STATUS_CHECKS.toArray(String[]::new)
				)
				.build();

		// Variant for repos with the main CI required status check
		var mainCiRuleset = defaultRuleset.toBuilder()
				.addRequiredStatusChecks("main.required-status-check")
				.build();

		// Variant for repos with the test required status check
		var testCiRuleset = defaultRuleset.toBuilder()
				.addRequiredStatusChecks("test.required-status-check")
				.build();

		// Group: GitHub Pages sites
		var pagesSite = RepositoryArgs.create("_")
				.pages()
				.rulesets(defaultRuleset)
				.build();
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
						.requiredStatusChecks("pr-check.required-status-check")
						.rulesets(
								defaultRuleset.toBuilder()
										.addRequiredStatusChecks(
												"pr-check.required-status-check"
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
		var mainCiRepo = RepositoryArgs.create("_")
				.requiredStatusChecks("main.required-status-check")
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
				RepositoryArgs.create("advent-of-code")
						.description("My advent of code solutions")
						.rulesets(defaultRuleset)
						.build(),
				RepositoryArgs.create("beatunes-keytocomment")
						.description(
								"A beatunes plugin that writes the key to the comment"
						)
						.rulesets(defaultRuleset)
						.build(),
				RepositoryArgs.create("calver-tag-action")
						.description(
								"A GitHub Actions action that creates a new version using a CalVer-style derivative and pushes it"
						)
						.rulesets(defaultRuleset)
						.build(),
				RepositoryArgs.create("corporate-python")
						.description(
								"A container for executing python in corporate environments"
						)
						.actionsSecrets(
								"DOCKER_HUB_ACCESS_TOKEN",
								"DOCKER_HUB_USERNAME"
						)
						.rulesets(defaultRuleset)
						.build(),
				RepositoryArgs.create("dependabot-dockerfile-test")
						.description(
								"A test to see whether dependabot updates dockerfiles with args"
						)
						.rulesets(defaultRuleset)
						.build(),
				RepositoryArgs.create("dotfiles")
						.description(
								"My collection of dotfiles used to configure my command line environments"
						)
						.rulesets(defaultRuleset)
						.build(),
				RepositoryArgs.create("effortful-retrieval-questions")
						.description(
								"A collection of effortful retrieval questions of a number of articles I've read"
						)
						.rulesets(defaultRuleset)
						.build(),
				RepositoryArgs.create("git-presentation-2018-10")
						.description(
								"Git Präsentation für Vorlesung Industrielle Softwareentwicklung"
						)
						.rulesets(defaultRuleset)
						.build(),
				RepositoryArgs.create("homebrew-tap")
						.description(
								"A homebrew tap for my own formulas and casks"
						)
						.rulesets(defaultRuleset)
						.build(),
				RepositoryArgs.create("kafka-debugger")
						.description(
								"A small jar utility to test kafka connections"
						)
						.rulesets(defaultRuleset)
						.build(),
				RepositoryArgs.create("menubar-scripts")
						.description(
								"A collection of scripts that can run in e.g. xbar, swiftbar, etc."
						)
						.rulesets(defaultRuleset)
						.build(),
				RepositoryArgs.create("music-stuff")
						.description("Some spotify and beatunes stuff")
						.requiredStatusChecks("test.required-status-check")
						.rulesets(testCiRuleset)
						.build(),
				RepositoryArgs.create("nope-amine")
						.description(
								"A firefox extension that slowly increases the time for things to load on reddit.com"
						)
						.rulesets(defaultRuleset)
						.build(),
				RepositoryArgs.create("open-webui-runner")
						.description(
								"A small repo to run open-webui locally and stop it after using it"
						)
						.rulesets(defaultRuleset)
						.build(),
				RepositoryArgs.create("postgres-sync-demo")
						.description(
								"A demo on how to use triggers, queues, etc. to sync the app's data somewhere else"
						)
						.rulesets(defaultRuleset)
						.build(),
				RepositoryArgs.create("python-nc")
						.description(
								"A test to see if I can implement nc's proxy functionality with python"
						)
						.rulesets(defaultRuleset)
						.build(),
				RepositoryArgs.create("sci-fi-movies")
						.description(
								"an app to import sci fi movies from rotten tomatoes into a database in order to run queries on them"
						)
						.rulesets(defaultRuleset)
						.build(),
				RepositoryArgs.create("terraform-github")
						.description(
								"A project to manage github settings with terraform"
						)
						.environment(
								"production",
								env -> env.secrets("TF_GITHUB_TOKEN")
						)
						.rulesets(defaultRuleset)
						.build(),
				RepositoryArgs.create("tsaf-parser")
						.description("Binary format exploration")
						.requiredStatusChecks("test.required-status-check")
						.rulesets(testCiRuleset)
						.build(),
				RepositoryArgs.create("vagrant-ssh-config")
						.description(
								"A vagrant plugin that automatically creates ssh configs for vms"
						)
						.rulesets(defaultRuleset)
						.build()
		);

		// Archived repos
		var archived = List.of(
				RepositoryArgs.archived("actions"),
				RepositoryArgs.archived("actions-checkout-fetch-depth-demo"),
				RepositoryArgs.archived("airmac"),
				RepositoryArgs.archived("campuswoche-2018-webseiten-steuern"),
				RepositoryArgs.archived("chop-kata"),
				RepositoryArgs.archived("dotnet-http-client-reproduction"),
				RepositoryArgs.archived("gitfx"),
				RepositoryArgs.archived("graalfx"),
				RepositoryArgs.archived("gwt-dragula-test"),
				RepositoryArgs.archived("gwt-log-print-style-demo"),
				RepositoryArgs.archived("gwt-refresh-demo"),
				RepositoryArgs.archived("HalloJSX"),
				RepositoryArgs.archived("HelloCocoaHTTPServer"),
				RepositoryArgs.archived("HelloIntAirActServer"),
				RepositoryArgs.archived("HelloRoutingServer"),
				RepositoryArgs.archived("HelloServer"),
				RepositoryArgs.archived("iebox"),
				RepositoryArgs.archived("ilabwebworkshop"),
				RepositoryArgs.archived("IntAirAct"),
				RepositoryArgs.archived("IntAirAct-Performance"),
				RepositoryArgs.archived("jBrowserDriver"),
				RepositoryArgs
						.archived("jbrowserdriver-cucumber-integration-tests"),
				RepositoryArgs.archived("jbrowserdriver-test"),
				RepositoryArgs.archived("jdk-newinstance-leak-demo"),
				RepositoryArgs
						.archived("jdk8u144-classloader-leak-demo-webapp"),
				RepositoryArgs.archived("jhipster-app"),
				RepositoryArgs
						.archived("json-smart-dependency-resolution-test"),
				RepositoryArgs.archived("m2e-wro4j-bug-demo"),
				RepositoryArgs.archived("m2e-wro4j-bug-demo2"),
				RepositoryArgs.archived("maven-quickstart-j2objc"),
				RepositoryArgs.archived("Mirror"),
				RepositoryArgs.archived("modern-ie-vagrant"),
				RepositoryArgs.archived("MWPhotoBrowser"),
				RepositoryArgs.archived("npmrc-github-action"),
				RepositoryArgs.archived("packer-templates"),
				RepositoryArgs.archived("pico-playground"),
				RepositoryArgs.archived("postgres-query-error-demo"),
				RepositoryArgs.archived("quickstart-buck-bazel-maven"),
				RepositoryArgs.archived("selenium-xp-ie6"),
				RepositoryArgs.archived("self-hosted-gh-actions-runner"),
				RepositoryArgs
						.archived("spring-cloud-context-classloader-leak-demo"),
				RepositoryArgs.archived(
						"spring-configuration-processor-metadata-bug-demo"
				),
				RepositoryArgs
						.archived("spring-security-drupal-password-encoder"),
				RepositoryArgs.archived("testcontainers-colima-github-actions"),
				RepositoryArgs.archived("toado"),
				RepositoryArgs.archived("vagrant-1"),
				RepositoryArgs.archived("vitest-link-reproduction"),
				RepositoryArgs.archived("vitest-mocking-reproduction"),
				RepositoryArgs.archived("workflow-dispatch-input-defaults")
		);

		return Stream.of(pagesSites, mainCiRepos, individual, archived)
				.flatMap(List::stream)
				.toList();
	}

}
