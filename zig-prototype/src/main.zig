//! main.zig — entry point, repository config, and the comptime mergeArgs helper
//!
//! Translation notes vs. Java GitHubCheck:
//!   System.getenv()               → std.process.getEnvVarOwned()
//!   List.of(args).contains("--fix")→ scan args slice
//!   OrgChecker.repositories()     → comptime slice literal (no heap alloc)
//!   Builder pattern + toBuilder() → mergeArgs() comptime struct merge
//!   System.exit(1)                → std.process.exit(1)

const std = @import("std");
const models = @import("models.zig");
const GitHubClient = @import("github_client.zig").GitHubClient;
const OrgChecker = @import("org_checker.zig").OrgChecker;

pub fn main() !void {
    // GeneralPurposeAllocator: detects leaks in Debug mode (enabled by default
    // for zig build / zig build run). Switch to std.heap.c_allocator for
    // production if you want malloc performance without the GPA overhead.
    var gpa_state = std.heap.GeneralPurposeAllocator(.{}){};
    defer _ = gpa_state.deinit();
    const gpa = gpa_state.allocator();

    // ── CLI args ──────────────────────────────────────────────────────────
    const args = try std.process.argsAlloc(gpa);
    defer std.process.argsFree(gpa, args);

    var do_fix = false;
    for (args[1..]) |arg| {
        if (std.mem.eql(u8, arg, "--fix")) do_fix = true;
    }

    // ── Environment variables ─────────────────────────────────────────────
    const token = std.process.getEnvVarOwned(gpa, "DRIFTY_GITHUB_TOKEN") catch {
        std.debug.print(
            "ERROR: DRIFTY_GITHUB_TOKEN environment variable not set\n",
            .{},
        );
        std.process.exit(1);
    };
    defer gpa.free(token);

    // DRIFTY_ORG defaults to "ArloL" matching the Java implementation.
    const org = std.process.getEnvVarOwned(gpa, "DRIFTY_ORG") catch
        try gpa.dupe(u8, "ArloL");
    defer gpa.free(org);

    // ── Run ───────────────────────────────────────────────────────────────
    const start_ms = std.time.milliTimestamp();

    var client = GitHubClient.init(gpa, token);
    defer client.deinit();

    var checker = OrgChecker.init(gpa, &client, org, do_fix);
    const result = try checker.check(&repositories);

    OrgChecker.printReport(result);

    const elapsed = @as(f64, @floatFromInt(std.time.milliTimestamp() - start_ms)) / 1000.0;
    std.debug.print("\nTotal execution time: {d:.2}s\n", .{elapsed});

    std.process.exit(if (result.hasDrift()) 1 else 0);
}

// ── Repository configuration ──────────────────────────────────────────────
//
// Java: GitHubCheck.repositories() builds a List<RepositoryArgs> at runtime
//       using a fluent builder DSL and toBuilder() to derive variants.
//
// Zig:  `comptime` means this slice is evaluated entirely at compile time —
//       the resulting array lives in the binary's read-only data segment.
//       No heap allocation, no runtime builder execution.
//
//       mergeArgs(base, overrides) is the Zig equivalent of toBuilder():
//       it copies `base`, then sets only the fields present in `overrides`
//       using inline comptime reflection.
//
// App-ID constants matching StatusCheckArgs in the Java code:
const APP_ID_GITHUB_ACTIONS: i64 = 15368;
const APP_ID_GITHUB_ADVANCED_SECURITY: i64 = 65;

// Default required status checks shared across most repos.
const default_checks = [_]models.StatusCheckArgs{
    .{ .context = "check-actions.required-status-check", .app_id = APP_ID_GITHUB_ACTIONS },
    .{ .context = "codeql-analysis.required-status-check", .app_id = APP_ID_GITHUB_ACTIONS },
    .{ .context = "CodeQL", .app_id = APP_ID_GITHUB_ADVANCED_SECURITY },
};

// Default code scanning tools.
const default_code_scanning = [_]models.CodeScanningToolArgs{
    .{ .tool = "CodeQL", .alerts_threshold = "errors", .security_alerts_threshold = "high_or_higher" },
    .{ .tool = "zizmor", .alerts_threshold = "errors", .security_alerts_threshold = "high_or_higher" },
};

// Default branch protection for the "main" branch.
const default_branch_protection = models.BranchProtectionArgs{
    .branch = "main",
    .required_status_checks = &default_checks,
    .enforce_admins = true,
    .required_linear_history = true,
};

// Default ruleset mirroring the legacy branch protection.
const default_ruleset = models.RulesetArgs{
    .name = "main-branch-rules",
    .include_patterns = &.{"refs/heads/main"},
    .required_linear_history = true,
    .no_force_pushes = true,
    .required_status_checks = &default_checks,
    .required_code_scanning = &default_code_scanning,
};

// Variant: adds the main CI required status check.
const main_ci_checks = default_checks ++ [_]models.StatusCheckArgs{
    .{ .context = "main.required-status-check", .app_id = APP_ID_GITHUB_ACTIONS },
};
const main_ci_ruleset = models.RulesetArgs{
    .name = "main-branch-rules",
    .include_patterns = &.{"refs/heads/main"},
    .required_linear_history = true,
    .no_force_pushes = true,
    .required_status_checks = &main_ci_checks,
    .required_code_scanning = &default_code_scanning,
};
const main_ci_branch_protection = models.BranchProtectionArgs{
    .branch = "main",
    .required_status_checks = &main_ci_checks,
    .enforce_admins = true,
    .required_linear_history = true,
};

// Variant: adds the test CI required status check.
const test_ci_checks = default_checks ++ [_]models.StatusCheckArgs{
    .{ .context = "test.required-status-check", .app_id = APP_ID_GITHUB_ACTIONS },
};
const test_ci_ruleset = models.RulesetArgs{
    .name = "main-branch-rules",
    .include_patterns = &.{"refs/heads/main"},
    .required_linear_history = true,
    .no_force_pushes = true,
    .required_status_checks = &test_ci_checks,
    .required_code_scanning = &default_code_scanning,
};
const test_ci_branch_protection = models.BranchProtectionArgs{
    .branch = "main",
    .required_status_checks = &test_ci_checks,
    .enforce_admins = true,
    .required_linear_history = true,
};

// Variant: adds the PR check required status check (for angular-playground).
const pr_ci_checks = default_checks ++ [_]models.StatusCheckArgs{
    .{ .context = "pr-check.required-status-check", .app_id = APP_ID_GITHUB_ACTIONS },
};
const pr_ci_ruleset = models.RulesetArgs{
    .name = "main-branch-rules",
    .include_patterns = &.{"refs/heads/main"},
    .required_linear_history = true,
    .no_force_pushes = true,
    .required_status_checks = &pr_ci_checks,
    .required_code_scanning = &default_code_scanning,
};
const pr_ci_branch_protection = models.BranchProtectionArgs{
    .branch = "main",
    .required_status_checks = &pr_ci_checks,
    .enforce_admins = true,
    .required_linear_history = true,
};

// Base config applied to all non-archived repos.
const default_repo = models.RepositoryArgs{
    .name = "",   // overridden per-repo
    .automated_security_fixes = true,
    .allow_merge_commit = false,
    .allow_squash_merge = false,
    .allow_rebase_merge = false,
    .allow_auto_merge = true,
    .delete_branch_on_merge = true,
    .default_workflow_permissions = "read",
    .can_approve_pull_request_reviews = true,
    .branch_protections = &.{default_branch_protection},
    .rulesets = &.{default_ruleset},
};

// comptime struct merge: copy `base`, overwrite fields present in `overrides`.
// Replaces Java's toBuilder() + .build() pattern. Evaluated at compile time —
// zero runtime cost.
fn mergeArgs(
    comptime base: models.RepositoryArgs,
    comptime overrides: anytype,
) models.RepositoryArgs {
    var result = base;
    inline for (std.meta.fields(@TypeOf(overrides))) |field| {
        @field(result, field.name) = @field(overrides, field.name);
    }
    return result;
}

// ── Repository list ───────────────────────────────────────────────────────
//
// This mirrors GitHubCheck.repositories() in the Java source.
// The `comptime` block ensures the entire list is resolved at compile time.

const repositories: []const models.RepositoryArgs = comptime blk: {
    // ── Pages sites ───────────────────────────────────────────────────────
    const pages_site = mergeArgs(default_repo, .{ .pages = true });

    const pages_sites = [_]models.RepositoryArgs{
        mergeArgs(pages_site, .{
            .name = "abenteuer-irland",
            .description = "Mum's website for Abenteuer Irland",
            .homepage_url = "https://arlol.github.io/abenteuer-irland/",
        }),
        mergeArgs(pages_site, .{
            .name = "angular-playground",
            .description = "A playground for the Angular framework",
            .homepage_url = "https://arlol.github.io/angular-playground/",
            .branch_protections = &.{pr_ci_branch_protection},
            .rulesets = &.{pr_ci_ruleset},
        }),
        mergeArgs(pages_site, .{
            .name = "arlol.github.io",
            .description = "This is the source of my GitHub page",
            .homepage_url = "https://arlol.github.io/",
        }),
        mergeArgs(pages_site, .{
            .name = "bulma-playground",
            .description = "A playground for the Bulma CSS framework",
            .homepage_url = "https://arlol.github.io/bulma-playground/",
        }),
        mergeArgs(pages_site, .{
            .name = "business-english",
            .description = "Mum's website for Business English",
            .homepage_url = "https://arlol.github.io/business-english/",
        }),
        mergeArgs(pages_site, .{
            .name = "eclipse-projects",
            .description = "Arlo's project catalog for the Eclipse Installer",
            .homepage_url = "https://arlol.github.io/eclipse-projects/",
        }),
    };

    // ── Main CI repos ─────────────────────────────────────────────────────
    const main_ci_repo = mergeArgs(default_repo, .{
        .branch_protections = &.{main_ci_branch_protection},
        .rulesets = &.{main_ci_ruleset},
    });

    const main_ci_repos = [_]models.RepositoryArgs{
        mergeArgs(main_ci_repo, .{
            .name = "chorito",
            .description = "A tool that does some chores in your source code",
            .actions_secrets = &.{"PAT"},
        }),
        mergeArgs(main_ci_repo, .{
            .name = "drifty",
            .description = "Detect and fix drift of GitHub repository settings",
        }),
        mergeArgs(main_ci_repo, .{
            .name = "git-dora-lead-time-calculator",
            .description = "A project to calculate the DORA metric lead time with the info from a git repo",
        }),
        mergeArgs(main_ci_repo, .{
            .name = "mvnx",
            .description = "An experiment with Maven dependencies and dynamic classloading",
        }),
        mergeArgs(main_ci_repo, .{
            .name = "myprojects-cleaner",
            .description = "A java application that runs git clean in a bunch of directories",
        }),
        mergeArgs(main_ci_repo, .{
            .name = "newlinechecker",
            .description = "A sample project to play with GraalVM builds on GitHub Actions",
            .immutable_releases = true,
        }),
        mergeArgs(main_ci_repo, .{
            .name = "rss-to-mail",
            .description = "Read from RSS feeds and send an email for every new item",
        }),
        mergeArgs(main_ci_repo, .{
            .name = "wait-for-ports",
            .description = "A command-line utility that waits until a port is open",
        }),
        mergeArgs(main_ci_repo, .{
            .name = "webapp-classloader-test",
            .description = "This is a test that can be used during integration testing to check for classloader leaks",
        }),
        mergeArgs(main_ci_repo, .{
            .name = "website-janitor",
            .description = "A set of tools that check websites for common misconfigurations or downtime",
        }),
    };

    // ── Individual repos with unique configurations ────────────────────────
    const individual = [_]models.RepositoryArgs{
        mergeArgs(default_repo, .{ .name = "advent-of-code", .description = "My advent of code solutions" }),
        mergeArgs(default_repo, .{ .name = "beatunes-keytocomment", .description = "A beatunes plugin that writes the key to the comment" }),
        mergeArgs(default_repo, .{
            .name = "calver-tag-action",
            .description = "A GitHub Actions action that creates a new version using a CalVer-style derivative and pushes it",
            .immutable_releases = true,
        }),
        mergeArgs(default_repo, .{
            .name = "corporate-python",
            .description = "A container for executing python in corporate environments",
            .actions_secrets = &.{ "DOCKER_HUB_ACCESS_TOKEN", "DOCKER_HUB_USERNAME" },
        }),
        mergeArgs(default_repo, .{ .name = "dependabot-dockerfile-test", .description = "A test to see whether dependabot updates dockerfiles with args" }),
        mergeArgs(default_repo, .{ .name = "dotfiles", .description = "My collection of dotfiles used to configure my command line environments" }),
        mergeArgs(default_repo, .{ .name = "effortful-retrieval-questions", .description = "A collection of effortful retrieval questions of a number of articles I've read" }),
        mergeArgs(default_repo, .{ .name = "git-presentation-2018-10", .description = "Git Präsentation für Vorlesung Industrielle Softwareentwicklung" }),
        mergeArgs(default_repo, .{ .name = "homebrew-tap", .description = "A homebrew tap for my own formulas and casks" }),
        mergeArgs(default_repo, .{ .name = "kafka-debugger", .description = "A small jar utility to test kafka connections" }),
        mergeArgs(default_repo, .{ .name = "menubar-scripts", .description = "A collection of scripts that can run in e.g. xbar, swiftbar, etc." }),
        mergeArgs(default_repo, .{
            .name = "music-stuff",
            .description = "Some spotify and beatunes stuff",
            .branch_protections = &.{test_ci_branch_protection},
            .rulesets = &.{test_ci_ruleset},
        }),
        mergeArgs(default_repo, .{ .name = "nope-amine", .description = "A firefox extension that slowly increases the time for things to load on reddit.com" }),
        mergeArgs(default_repo, .{ .name = "open-webui-runner", .description = "A small repo to run open-webui locally and stop it after using it" }),
        mergeArgs(default_repo, .{ .name = "postgres-sync-demo", .description = "A demo on how to use triggers, queues, etc. to sync the app's data somewhere else" }),
        mergeArgs(default_repo, .{ .name = "python-nc", .description = "A test to see if I can implement nc's proxy functionality with python" }),
        mergeArgs(default_repo, .{ .name = "sci-fi-movies", .description = "an app to import sci fi movies from rotten tomatoes into a database in order to run queries on them" }),
        mergeArgs(default_repo, .{
            .name = "terraform-github",
            .description = "A project to manage github settings with terraform",
            .environments = &.{.{ .name = "production", .secrets = &.{"TF_GITHUB_TOKEN"} }},
        }),
        mergeArgs(default_repo, .{
            .name = "tsaf-parser",
            .description = "Binary format exploration",
            .branch_protections = &.{test_ci_branch_protection},
            .rulesets = &.{test_ci_ruleset},
        }),
        mergeArgs(default_repo, .{ .name = "vagrant-ssh-config", .description = "A vagrant plugin that automatically creates ssh configs for vms" }),
    };

    // ── Archived repos ────────────────────────────────────────────────────
    const archived = [_]models.RepositoryArgs{
        .{ .name = "actions", .archived = true },
        .{ .name = "actions-checkout-fetch-depth-demo", .archived = true },
        .{ .name = "airmac", .archived = true },
        .{ .name = "campuswoche-2018-webseiten-steuern", .archived = true },
        .{ .name = "chop-kata", .archived = true },
        .{ .name = "dotnet-http-client-reproduction", .archived = true },
        .{ .name = "gitfx", .archived = true },
        .{ .name = "graalfx", .archived = true },
        .{ .name = "gwt-dragula-test", .archived = true },
        .{ .name = "gwt-log-print-style-demo", .archived = true },
        .{ .name = "gwt-refresh-demo", .archived = true },
        .{ .name = "HalloJSX", .archived = true },
        .{ .name = "HelloCocoaHTTPServer", .archived = true },
        .{ .name = "HelloIntAirActServer", .archived = true },
        .{ .name = "HelloRoutingServer", .archived = true },
        .{ .name = "HelloServer", .archived = true },
        .{ .name = "iebox", .archived = true },
        .{ .name = "ilabwebworkshop", .archived = true },
        .{ .name = "IntAirAct", .archived = true },
        .{ .name = "IntAirAct-Performance", .archived = true },
        .{ .name = "jBrowserDriver", .archived = true },
        .{ .name = "jbrowserdriver-cucumber-integration-tests", .archived = true },
        .{ .name = "jbrowserdriver-test", .archived = true },
        .{ .name = "jdk-newinstance-leak-demo", .archived = true },
        .{ .name = "jdk8u144-classloader-leak-demo-webapp", .archived = true },
        .{ .name = "jhipster-app", .archived = true },
        .{ .name = "json-smart-dependency-resolution-test", .archived = true },
        .{ .name = "m2e-wro4j-bug-demo", .archived = true },
        .{ .name = "m2e-wro4j-bug-demo2", .archived = true },
        .{ .name = "maven-quickstart-j2objc", .archived = true },
        .{ .name = "Mirror", .archived = true },
        .{ .name = "modern-ie-vagrant", .archived = true },
        .{ .name = "MWPhotoBrowser", .archived = true },
        .{ .name = "npmrc-github-action", .archived = true },
        .{ .name = "packer-templates", .archived = true },
        .{ .name = "pico-playground", .archived = true },
        .{ .name = "postgres-query-error-demo", .archived = true },
        .{ .name = "quickstart-buck-bazel-maven", .archived = true },
        .{ .name = "selenium-xp-ie6", .archived = true },
        .{ .name = "self-hosted-gh-actions-runner", .archived = true },
        .{ .name = "spring-cloud-context-classloader-leak-demo", .archived = true },
        .{ .name = "spring-configuration-processor-metadata-bug-demo", .archived = true },
        .{ .name = "spring-security-drupal-password-encoder", .archived = true },
        .{ .name = "testcontainers-colima-github-actions", .archived = true },
        .{ .name = "toado", .archived = true },
        .{ .name = "vagrant-1", .archived = true },
        .{ .name = "vitest-link-reproduction", .archived = true },
        .{ .name = "vitest-mocking-reproduction", .archived = true },
        .{ .name = "workflow-dispatch-input-defaults", .archived = true },
    };

    break :blk &(pages_sites ++ main_ci_repos ++ individual ++ archived);
};
