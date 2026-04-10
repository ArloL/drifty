//! org_checker.zig — the main checker: fetch → diff → report
//!
//! Translation notes vs. Java OrgChecker:
//!   ExecutorService.newVirtualThreadPerTaskExecutor() → sequential loop
//!   (I/O-bound + GitHub rate limit; parallelism adds complexity without benefit)
//!   List<String> diffs            → std.ArrayList([]const u8)
//!   Optional<PagesResponse>       → ?PagesResponse
//!   Objects.equals / String.equals → std.mem.eql(u8, a, b)
//!   switch on sealed interface     → switch on tagged union (compiler-checked)

const std = @import("std");
const Allocator = std.mem.Allocator;
const models = @import("models.zig");
const GitHubClient = @import("github_client.zig").GitHubClient;

pub const OrgChecker = struct {
    gpa: Allocator,
    client: *GitHubClient,
    org: []const u8,
    fix: bool,

    pub fn init(
        gpa: Allocator,
        client: *GitHubClient,
        org: []const u8,
        fix: bool,
    ) OrgChecker {
        return .{ .gpa = gpa, .client = client, .org = org, .fix = fix };
    }

    // ── Entry point ───────────────────────────────────────────────────────

    pub fn check(
        self: *OrgChecker,
        repositories: []const models.RepositoryArgs,
    ) !models.CheckResult {
        std.debug.print("Fetching repo list for org: {s}\n", .{self.org});

        // Per-run arena: one deinit() cleans up everything allocated during
        // listing + intermediate data. Results that must outlive this (the
        // final RepoCheckResult slices) are duped into self.gpa.
        var run_arena = std.heap.ArenaAllocator.init(self.gpa);
        defer run_arena.deinit();
        const ra = run_arena.allocator();

        const summaries = try self.client.listOrgRepos(self.org, ra);
        std.debug.print(
            "Found {d} repos. Checking...\n",
            .{summaries.len},
        );

        // Build a name→desired map for O(1) lookup.
        var desired_map = std.StringHashMap(models.RepositoryArgs).init(ra);
        for (repositories) |repo| try desired_map.put(repo.name, repo);

        // Sequential check loop — appropriate for I/O-bound, rate-limited work.
        // Each repo gets its own arena that is freed before the next iteration,
        // keeping peak memory to one repo's worth of API data at a time.
        var results = std.ArrayList(models.RepoCheckResult).init(ra);

        for (summaries) |summary| {
            var repo_arena = std.heap.ArenaAllocator.init(self.gpa);
            defer repo_arena.deinit();

            const result = self.checkOne(
                repo_arena.allocator(),
                summary,
                desired_map.get(summary.name),
            ) catch |err| models.RepoCheckResult{
                .name = summary.name,
                .status = .@"error",
                .diffs = &.{},
                .err = @errorName(err),
            };

            // Copy the result into the run arena so it outlives the repo arena.
            try results.append(try copyResult(result, ra));
        }

        // Repos declared in config but not found in the org → MISSING.
        var found = std.StringHashMap(void).init(ra);
        for (summaries) |s| try found.put(s.name, {});
        for (repositories) |desired| {
            if (!found.contains(desired.name)) {
                try results.append(.{
                    .name = desired.name,
                    .status = .missing,
                    .diffs = &.{},
                    .err = null,
                });
            }
        }

        // Move final results into the GPA so they survive the run arena teardown.
        const owned = try self.gpa.dupe(models.RepoCheckResult, results.items);
        return models.CheckResult{ .repos = owned };
    }

    // ── Per-repo check ────────────────────────────────────────────────────

    fn checkOne(
        self: *OrgChecker,
        alloc: Allocator,
        summary: models.RepositoryMinimal,
        desired_opt: ?models.RepositoryArgs,
    ) !models.RepoCheckResult {
        const desired = desired_opt orelse return models.RepoCheckResult{
            .name = summary.name,
            .status = .unknown,
            .diffs = &.{},
            .err = null,
        };

        const state = try self.fetchState(alloc, summary);

        var diffs = std.ArrayList([]const u8).init(alloc);
        try self.computeDiffs(&diffs, &state, desired);

        if (diffs.items.len == 0) {
            return .{ .name = summary.name, .status = .ok, .diffs = &.{}, .err = null };
        }
        return .{
            .name = summary.name,
            .status = .drift,
            .diffs = try diffs.toOwnedSlice(),
            .err = null,
        };
    }

    // ── State fetching ────────────────────────────────────────────────────
    //
    // Mirrors Java OrgChecker.fetchState(). Each API call allocates into
    // `alloc` (the per-repo arena); the whole state is freed when the arena
    // is torn down after computeDiffs() returns.

    fn fetchState(
        self: *OrgChecker,
        alloc: Allocator,
        summary: models.RepositoryMinimal,
    ) !models.RepositoryState {
        const name = summary.name;
        const archived = summary.archived;
        const is_public = if (summary.visibility) |v|
            std.mem.eql(u8, v, "public")
        else
            !summary.@"private";

        const details = try self.client.getRepo(self.org, name, alloc);

        var vuln_alerts = false;
        var auto_sec_fixes = false;
        var immutable_releases = false;
        var pvr = false;
        var code_scan = false;

        if (!archived) {
            vuln_alerts = try self.client.getVulnerabilityAlerts(self.org, name);
            auto_sec_fixes = try self.client.getAutomatedSecurityFixes(self.org, name);
            pvr = try self.client.getPrivateVulnerabilityReporting(self.org, name);
            code_scan = try self.client.getCodeScanningDefaultSetup(self.org, name);
            if (try self.client.getImmutableReleases(self.org, name)) |v|
                immutable_releases = v;
        }

        // Branch protections: only for public, non-archived repos.
        var branch_protections = std.StringHashMap(models.BranchProtectionResponse).init(alloc);
        if (!archived and is_public) {
            const protected_branches = try self.client.getProtectedBranches(self.org, name, alloc);
            for (protected_branches) |branch| {
                if (try self.client.getBranchProtection(self.org, name, branch, alloc)) |bp| {
                    try branch_protections.put(branch, bp);
                }
            }
        }

        const secret_names = try self.client.getActionSecretNames(self.org, name, alloc);

        const environments = try self.client.getEnvironments(self.org, name, alloc);
        var env_secrets = std.StringHashMap([][]const u8).init(alloc);
        var env_details = std.StringHashMap(models.EnvironmentDetailsResponse).init(alloc);
        for (environments) |env| {
            try env_details.put(env.name, env);
            const names = try self.client.getEnvironmentSecretNames(
                self.org, name, env.name, alloc,
            );
            try env_secrets.put(env.name, names);
        }

        const wf_perms = try self.client.getWorkflowPermissions(self.org, name, alloc);
        const pages = if (archived) null else try self.client.getPages(self.org, name, alloc);
        const rulesets = if (archived)
            try alloc.alloc(models.RulesetDetailsResponse, 0)
        else
            try self.client.listRulesets(self.org, name, alloc);

        return models.RepositoryState{
            .name = name,
            .summary = summary,
            .details = details,
            .vulnerability_alerts = vuln_alerts,
            .automated_security_fixes = auto_sec_fixes,
            .branch_protections = branch_protections,
            .action_secret_names = secret_names,
            .environment_secret_names = env_secrets,
            .workflow_permissions = wf_perms,
            .rulesets = rulesets,
            .pages = pages,
            .environment_details = env_details,
            .immutable_releases = immutable_releases,
            .private_vulnerability_reporting = pvr,
            .code_scanning_default_setup = code_scan,
        };
    }

    // ── Diff computation ──────────────────────────────────────────────────
    //
    // Mirrors Java OrgChecker.computeDiffs(). Each check appends a human-
    // readable string to `diffs` when actual ≠ desired.

    fn computeDiffs(
        self: *OrgChecker,
        diffs: *std.ArrayList([]const u8),
        actual: *const models.RepositoryState,
        desired: models.RepositoryArgs,
    ) !void {
        _ = self;

        // Archived repos: only check the archived flag itself.
        if (desired.archived) {
            if (!actual.summary.archived)
                try diffs.append("archived: want=true got=false");
            return;
        }

        const d = actual.details;

        // ── Repository settings ───────────────────────────────────────────
        try checkStr(diffs, "description", desired.description, d.description orelse "");
        try checkStr(diffs, "homepage_url", desired.homepage_url, d.homepage orelse "");
        try checkBool(diffs, "has_issues", desired.has_issues, d.has_issues);
        try checkBool(diffs, "has_projects", desired.has_projects, d.has_projects);
        try checkBool(diffs, "has_wiki", desired.has_wiki, d.has_wiki);
        try checkBool(diffs, "allow_merge_commit", desired.allow_merge_commit, d.allow_merge_commit);
        try checkBool(diffs, "allow_squash_merge", desired.allow_squash_merge, d.allow_squash_merge);
        try checkBool(diffs, "allow_rebase_merge", desired.allow_rebase_merge, d.allow_rebase_merge);
        try checkBool(diffs, "allow_auto_merge", desired.allow_auto_merge, d.allow_auto_merge);
        try checkBool(diffs, "allow_update_branch", desired.allow_update_branch, d.allow_update_branch);
        try checkBool(diffs, "delete_branch_on_merge", desired.delete_branch_on_merge, d.delete_branch_on_merge);

        // ── Security settings ─────────────────────────────────────────────
        try checkBool(diffs, "vulnerability_alerts", desired.vulnerability_alerts, actual.vulnerability_alerts);
        try checkBool(diffs, "automated_security_fixes", desired.automated_security_fixes, actual.automated_security_fixes);
        try checkBool(diffs, "immutable_releases", desired.immutable_releases, actual.immutable_releases);
        try checkBool(diffs, "private_vulnerability_reporting", desired.private_vulnerability_reporting, actual.private_vulnerability_reporting);
        try checkBool(diffs, "code_scanning_default_setup", desired.code_scanning_default_setup, actual.code_scanning_default_setup);

        // Security-and-analysis fields from the repo details payload.
        if (actual.details.security_and_analysis) |sa| {
            const want_scanning = if (desired.secret_scanning) @as([]const u8, "enabled") else "disabled";
            const got_scanning = if (sa.secret_scanning) |s| s.status else "disabled";
            try checkStr(diffs, "secret_scanning", want_scanning, got_scanning);

            const want_push = if (desired.secret_scanning_push_protection) @as([]const u8, "enabled") else "disabled";
            const got_push = if (sa.secret_scanning_push_protection) |s| s.status else "disabled";
            try checkStr(diffs, "secret_scanning_push_protection", want_push, got_push);
        }

        // ── Workflow permissions ──────────────────────────────────────────
        const wfp = actual.workflow_permissions;
        try checkStr(diffs, "default_workflow_permissions", desired.default_workflow_permissions, wfp.default_workflow_permissions);
        try checkBool(diffs, "can_approve_pull_request_reviews", desired.can_approve_pull_request_reviews, wfp.can_approve_pull_request_reviews);

        // ── Pages ─────────────────────────────────────────────────────────
        const has_pages = actual.pages != null;
        if (desired.pages and !has_pages)
            try diffs.append("pages: want=enabled got=disabled");
        if (!desired.pages and has_pages)
            try diffs.append("pages: want=disabled got=enabled");

        // ── Action secrets ────────────────────────────────────────────────
        // Check that every desired secret exists; ignore extra secrets.
        for (desired.actions_secrets) |want| {
            if (!containsStr(actual.action_secret_names, want)) {
                const msg = try std.fmt.allocPrint(
                    diffs.allocator,
                    "action_secret missing: {s}",
                    .{want},
                );
                try diffs.append(msg);
            }
        }

        // ── Environment secrets ───────────────────────────────────────────
        for (desired.environments) |env_args| {
            const actual_secrets = actual.environment_secret_names.get(env_args.name) orelse &.{};
            for (env_args.secrets) |want_secret| {
                if (!containsStr(actual_secrets, want_secret)) {
                    const msg = try std.fmt.allocPrint(
                        diffs.allocator,
                        "environment {s}: secret missing: {s}",
                        .{ env_args.name, want_secret },
                    );
                    try diffs.append(msg);
                }
            }
        }

        // ── Branch protections ────────────────────────────────────────────
        for (desired.branch_protections) |bp_args| {
            const actual_bp = actual.branch_protections.get(bp_args.branch);
            if (actual_bp == null) {
                const msg = try std.fmt.allocPrint(
                    diffs.allocator,
                    "branch_protection/{s}: missing",
                    .{bp_args.branch},
                );
                try diffs.append(msg);
                continue;
            }
            try checkBranchProtection(diffs, bp_args, actual_bp.?);
        }

        // ── Rulesets ──────────────────────────────────────────────────────
        for (desired.rulesets) |rs_args| {
            // Find the actual ruleset by name.
            const actual_rs = findRuleset(actual.rulesets, rs_args.name);
            if (actual_rs == null) {
                const msg = try std.fmt.allocPrint(
                    diffs.allocator,
                    "ruleset/{s}: missing",
                    .{rs_args.name},
                );
                try diffs.append(msg);
                continue;
            }
            try checkRuleset(diffs, rs_args, actual_rs.?);
        }

        // Extra rulesets not in desired config.
        for (actual.rulesets) |rs| {
            if (!findRulesetArgs(desired.rulesets, rs.name)) {
                const msg = try std.fmt.allocPrint(
                    diffs.allocator,
                    "ruleset/{s}: unexpected",
                    .{rs.name},
                );
                try diffs.append(msg);
            }
        }
    }

    // ── Branch protection diff ────────────────────────────────────────────

    fn checkBranchProtection(
        diffs: *std.ArrayList([]const u8),
        desired: models.BranchProtectionArgs,
        actual: models.BranchProtectionResponse,
    ) !void {
        const prefix = desired.branch;

        const actual_admins = if (actual.enforce_admins) |f| f.enabled else false;
        if (desired.enforce_admins != actual_admins) {
            const msg = try std.fmt.allocPrint(
                diffs.allocator,
                "branch_protection/{s}/enforce_admins: want={} got={}",
                .{ prefix, desired.enforce_admins, actual_admins },
            );
            try diffs.append(msg);
        }

        const actual_linear = if (actual.required_linear_history) |f| f.enabled else false;
        if (desired.required_linear_history != actual_linear) {
            const msg = try std.fmt.allocPrint(
                diffs.allocator,
                "branch_protection/{s}/required_linear_history: want={} got={}",
                .{ prefix, desired.required_linear_history, actual_linear },
            );
            try diffs.append(msg);
        }

        // Status checks: desired must be a subset of actual.
        if (actual.required_status_checks) |rsc| {
            outer: for (desired.required_status_checks) |want_check| {
                if (rsc.checks) |checks| {
                    for (checks) |got| {
                        if (std.mem.eql(u8, want_check.context, got.context)) continue :outer;
                    }
                }
                const msg = try std.fmt.allocPrint(
                    diffs.allocator,
                    "branch_protection/{s}/required_status_checks: missing context={s}",
                    .{ prefix, want_check.context },
                );
                try diffs.append(msg);
            }
        } else if (desired.required_status_checks.len > 0) {
            const msg = try std.fmt.allocPrint(
                diffs.allocator,
                "branch_protection/{s}/required_status_checks: missing",
                .{prefix},
            );
            try diffs.append(msg);
        }
    }

    // ── Ruleset diff ──────────────────────────────────────────────────────

    fn checkRuleset(
        diffs: *std.ArrayList([]const u8),
        desired: models.RulesetArgs,
        actual: models.RulesetDetailsResponse,
    ) !void {
        const prefix = desired.name;

        // Enforcement.
        if (!std.mem.eql(u8, desired.enforcement, actual.enforcement)) {
            const msg = try std.fmt.allocPrint(
                diffs.allocator,
                "ruleset/{s}/enforcement: want={s} got={s}",
                .{ prefix, desired.enforcement, actual.enforcement },
            );
            try diffs.append(msg);
        }

        // Include patterns via conditions.ref_name.
        if (actual.conditions) |cond| {
            if (cond.ref_name) |ref| {
                const actual_includes = ref.include orelse &.{};
                for (desired.include_patterns) |want| {
                    if (!containsStr(actual_includes, want)) {
                        const msg = try std.fmt.allocPrint(
                            diffs.allocator,
                            "ruleset/{s}/conditions/ref_name/include: missing {s}",
                            .{ prefix, want },
                        );
                        try diffs.append(msg);
                    }
                }
            }
        } else if (desired.include_patterns.len > 0) {
            const msg = try std.fmt.allocPrint(
                diffs.allocator,
                "ruleset/{s}/conditions: missing",
                .{prefix},
            );
            try diffs.append(msg);
        }

        // Rules — check that each desired rule type is present with matching params.
        // Uses switch on the Rule union; the compiler enforces exhaustiveness.
        for (actual.rules) |rule| {
            switch (rule) {
                .required_linear_history => {
                    if (!desired.required_linear_history) {
                        const msg = try std.fmt.allocPrint(
                            diffs.allocator,
                            "ruleset/{s}/rules: unexpected required_linear_history",
                            .{prefix},
                        );
                        try diffs.append(msg);
                    }
                },
                .non_fast_forward => {
                    if (!desired.no_force_pushes) {
                        const msg = try std.fmt.allocPrint(
                            diffs.allocator,
                            "ruleset/{s}/rules: unexpected non_fast_forward",
                            .{prefix},
                        );
                        try diffs.append(msg);
                    }
                },
                .required_status_checks => |p| {
                    // Verify every desired check is present.
                    const actual_checks = p.required_status_checks orelse &.{};
                    for (desired.required_status_checks) |want| {
                        var found = false;
                        for (actual_checks) |got| {
                            if (std.mem.eql(u8, want.context, got.context)) {
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            const msg = try std.fmt.allocPrint(
                                diffs.allocator,
                                "ruleset/{s}/required_status_checks: missing context={s}",
                                .{ prefix, want.context },
                            );
                            try diffs.append(msg);
                        }
                    }
                },
                .code_scanning => |p| {
                    const actual_tools = p.code_scanning_tools orelse &.{};
                    for (desired.required_code_scanning) |want| {
                        var found = false;
                        for (actual_tools) |got| {
                            if (std.mem.eql(u8, want.tool, got.tool)) {
                                found = true;
                                // Check thresholds.
                                if (got.alerts_threshold) |at| {
                                    if (!std.mem.eql(u8, want.alerts_threshold, at)) {
                                        const msg = try std.fmt.allocPrint(
                                            diffs.allocator,
                                            "ruleset/{s}/code_scanning/{s}/alerts_threshold: want={s} got={s}",
                                            .{ prefix, want.tool, want.alerts_threshold, at },
                                        );
                                        try diffs.append(msg);
                                    }
                                }
                                if (got.security_alerts_threshold) |sat| {
                                    if (!std.mem.eql(u8, want.security_alerts_threshold, sat)) {
                                        const msg = try std.fmt.allocPrint(
                                            diffs.allocator,
                                            "ruleset/{s}/code_scanning/{s}/security_alerts_threshold: want={s} got={s}",
                                            .{ prefix, want.tool, want.security_alerts_threshold, sat },
                                        );
                                        try diffs.append(msg);
                                    }
                                }
                                break;
                            }
                        }
                        if (!found) {
                            const msg = try std.fmt.allocPrint(
                                diffs.allocator,
                                "ruleset/{s}/code_scanning: missing tool={s}",
                                .{ prefix, want.tool },
                            );
                            try diffs.append(msg);
                        }
                    }
                },
                // Variants not currently in RepositoryArgs config — log if unexpected.
                .creation, .deletion, .required_signatures, .update,
                .pull_request, .commit_message_pattern, .commit_author_email_pattern,
                .committer_email_pattern, .branch_name_pattern, .tag_name_pattern,
                .required_deployments => {},
                .unknown => |type_str| {
                    std.debug.print(
                        "  ruleset/{s}: unknown rule type={s} (ignored)\n",
                        .{ prefix, type_str },
                    );
                },
            }
        }

        // Check that desired rules are present.
        if (desired.required_linear_history and !hasRuleType(actual.rules, .required_linear_history)) {
            const msg = try std.fmt.allocPrint(
                diffs.allocator,
                "ruleset/{s}/rules: missing required_linear_history",
                .{prefix},
            );
            try diffs.append(msg);
        }
        if (desired.no_force_pushes and !hasRuleType(actual.rules, .non_fast_forward)) {
            const msg = try std.fmt.allocPrint(
                diffs.allocator,
                "ruleset/{s}/rules: missing non_fast_forward",
                .{prefix},
            );
            try diffs.append(msg);
        }
    }

    // ── Report ────────────────────────────────────────────────────────────

    pub fn printReport(result: models.CheckResult) void {
        const stdout = std.io.getStdOut().writer();

        stdout.print("\n=== Drift Report ===\n", .{}) catch {};
        for (result.repos) |repo| {
            switch (repo.status) {
                .ok => stdout.print("  OK      {s}\n", .{repo.name}) catch {},
                .drift => {
                    stdout.print("  DRIFT   {s}\n", .{repo.name}) catch {};
                    for (repo.diffs) |d|
                        stdout.print("            - {s}\n", .{d}) catch {};
                },
                .@"error" => stdout.print(
                    "  ERROR   {s}: {s}\n",
                    .{ repo.name, repo.err orelse "unknown" },
                ) catch {},
                .unknown => stdout.print("  UNKNOWN {s}\n", .{repo.name}) catch {},
                .missing => stdout.print("  MISSING {s}\n", .{repo.name}) catch {},
            }
        }

        const n_ok = result.countByStatus(.ok);
        const n_drift = result.countByStatus(.drift);
        const n_err = result.countByStatus(.@"error");
        const n_unknown = result.countByStatus(.unknown);
        const n_missing = result.countByStatus(.missing);

        stdout.print(
            "\nSummary: {d} ok, {d} drift, {d} error, {d} unknown, {d} missing\n",
            .{ n_ok, n_drift, n_err, n_unknown, n_missing },
        ) catch {};
    }
};

// ── Diff helper functions ─────────────────────────────────────────────────
//
// Java's check(List<String>, String, Object, Object) helper maps to these.
// Zig cannot compare arbitrary types with ==; string comparison requires
// std.mem.eql, boolean comparison uses ==.

fn checkStr(
    diffs: *std.ArrayList([]const u8),
    field: []const u8,
    want: []const u8,
    got: []const u8,
) !void {
    if (!std.mem.eql(u8, want, got)) {
        const msg = try std.fmt.allocPrint(
            diffs.allocator,
            "{s}: want={s} got={s}",
            .{ field, want, got },
        );
        try diffs.append(msg);
    }
}

fn checkBool(
    diffs: *std.ArrayList([]const u8),
    field: []const u8,
    want: bool,
    got: bool,
) !void {
    if (want != got) {
        const msg = try std.fmt.allocPrint(
            diffs.allocator,
            "{s}: want={} got={}",
            .{ field, want, got },
        );
        try diffs.append(msg);
    }
}

fn containsStr(haystack: []const []const u8, needle: []const u8) bool {
    for (haystack) |s| if (std.mem.eql(u8, s, needle)) return true;
    return false;
}

fn findRuleset(
    rulesets: []const models.RulesetDetailsResponse,
    name: []const u8,
) ?models.RulesetDetailsResponse {
    for (rulesets) |rs| if (std.mem.eql(u8, rs.name, name)) return rs;
    return null;
}

fn findRulesetArgs(args: []const models.RulesetArgs, name: []const u8) bool {
    for (args) |a| if (std.mem.eql(u8, a.name, name)) return true;
    return false;
}

fn hasRuleType(rules: []const models.Rule, tag: std.meta.Tag(models.Rule)) bool {
    for (rules) |r| if (r == tag) return true;
    return false;
}

// Deep-copy a RepoCheckResult into a new allocator.
// Needed when the result was built in a short-lived repo arena and must
// survive into the longer-lived run arena.
fn copyResult(r: models.RepoCheckResult, alloc: Allocator) !models.RepoCheckResult {
    const name = try alloc.dupe(u8, r.name);
    const diffs = try alloc.alloc([]const u8, r.diffs.len);
    for (r.diffs, 0..) |d, i| diffs[i] = try alloc.dupe(u8, d);
    return .{
        .name = name,
        .status = r.status,
        .diffs = diffs,
        .err = if (r.err) |e| try alloc.dupe(u8, e) else null,
    };
}
