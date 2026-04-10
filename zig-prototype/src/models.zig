//! models.zig — data structures mirroring the Java records/enums
//!
//! Translation notes:
//!   Java record Foo(T a, T b)   → const Foo = struct { a: T, b: T };
//!   Optional<T>                  → ?T  (zero overhead, no boxing)
//!   Map<String, V>               → std.StringHashMap(V)  (must deinit())
//!   List<String>                 → [][]const u8
//!   sealed interface + subclasses → union(enum) with exhaustive switch
//!   @JsonProperty("private")     → @"private"  (escapes reserved keyword)

const std = @import("std");
const Allocator = std.mem.Allocator;

// ── GitHub API response types ─────────────────────────────────────────────
// Field names are snake_case to match the GitHub JSON wire format directly.
// No naming-strategy configuration is needed (unlike Jackson's SNAKE_CASE).

pub const RepositoryVisibility = enum { public, private, internal };

pub const SecurityAndAnalysisStatus = struct {
    status: []const u8 = "disabled", // "enabled" | "disabled"
};

pub const SecurityAndAnalysis = struct {
    secret_scanning: ?SecurityAndAnalysisStatus = null,
    secret_scanning_push_protection: ?SecurityAndAnalysisStatus = null,
    advanced_security: ?SecurityAndAnalysisStatus = null,
    dependabot_security_updates: ?SecurityAndAnalysisStatus = null,
};

pub const RepositoryMinimal = struct {
    id: i64,
    name: []const u8,
    full_name: []const u8,
    @"private": bool = false,
    archived: bool = false,
    // "public" | "private" | "internal" — kept as string to avoid failure on
    // future values; callers compare with std.mem.eql.
    visibility: ?[]const u8 = null,
    default_branch: ?[]const u8 = null,
    description: ?[]const u8 = null,
    homepage: ?[]const u8 = null,
    html_url: []const u8 = "",
    security_and_analysis: ?SecurityAndAnalysis = null,
};

pub const RepositoryFull = struct {
    id: i64,
    name: []const u8,
    full_name: []const u8,
    @"private": bool = false,
    archived: bool = false,
    disabled: bool = false,
    visibility: ?[]const u8 = null,
    default_branch: []const u8 = "main",
    description: ?[]const u8 = null,
    homepage: ?[]const u8 = null,
    has_issues: bool = true,
    has_projects: bool = true,
    has_wiki: bool = true,
    allow_merge_commit: bool = true,
    allow_squash_merge: bool = true,
    allow_rebase_merge: bool = true,
    allow_auto_merge: bool = false,
    allow_update_branch: bool = false,
    delete_branch_on_merge: bool = false,
    topics: ?[][]const u8 = null,
    security_and_analysis: ?SecurityAndAnalysis = null,
};

pub const WorkflowPermissions = struct {
    // "read" | "write"
    default_workflow_permissions: []const u8 = "write",
    can_approve_pull_request_reviews: bool = true,
};

// ── Branch protection ─────────────────────────────────────────────────────

pub const BranchProtectionStatusCheck = struct {
    context: []const u8,
    app_id: ?i64 = null,
};

pub const BranchProtectionRequiredStatusChecks = struct {
    strict: bool = false,
    // GitHub returns both "checks" (newer) and "contexts" (legacy).
    checks: ?[]BranchProtectionStatusCheck = null,
    contexts: ?[][]const u8 = null,
};

pub const BranchProtectionPRReviews = struct {
    dismiss_stale_reviews: bool = false,
    require_code_owner_reviews: bool = false,
    required_approving_review_count: ?i32 = null,
    require_last_push_approval: ?bool = null,
};

pub const BranchProtectionSimpleFlag = struct {
    enabled: bool = false,
};

pub const BranchProtectionResponse = struct {
    url: ?[]const u8 = null,
    enforce_admins: ?BranchProtectionSimpleFlag = null,
    required_linear_history: ?BranchProtectionSimpleFlag = null,
    allow_force_pushes: ?BranchProtectionSimpleFlag = null,
    allow_deletions: ?BranchProtectionSimpleFlag = null,
    required_status_checks: ?BranchProtectionRequiredStatusChecks = null,
    required_pull_request_reviews: ?BranchProtectionPRReviews = null,
};

// ── Pages ─────────────────────────────────────────────────────────────────

pub const PagesSource = struct {
    branch: []const u8 = "",
    path: []const u8 = "/",
};

pub const PagesResponse = struct {
    url: ?[]const u8 = null,
    status: ?[]const u8 = null,
    // "workflow" | "legacy"
    build_type: ?[]const u8 = null,
    source: ?PagesSource = null,
    @"public": bool = true,
    https_enforced: bool = false,
};

// ── Environments ──────────────────────────────────────────────────────────

pub const EnvironmentDeploymentBranchPolicy = struct {
    protected_branches: bool = false,
    custom_branch_policies: bool = false,
};

pub const EnvironmentDetailsResponse = struct {
    name: []const u8,
    // protection_rules is heterogeneous (wait_timer / required_reviewers /
    // branch_policy); omit for prototype, check via raw JSON if needed.
    deployment_branch_policy: ?EnvironmentDeploymentBranchPolicy = null,
};

// ── Rules (the polymorphic part) ──────────────────────────────────────────
//
// Java uses Jackson @JsonTypeInfo discriminating on "type" field +
// @JsonSubTypes listing 15 subtypes of a sealed interface.
//
// Zig equivalent: tagged union(enum). Each variant holds only the fields
// specific to that rule type. The "unknown" variant carries the raw type
// string so callers can log and skip gracefully.
//
// Deserialization is two-phase:
//   1. Parse into RuleWire = struct { type: []u8, parameters: ?std.json.Value }
//   2. Call ruleFromWire() which switches on .type and extracts .parameters

pub const RuleStatusCheck = struct {
    context: []const u8,
    // GitHub Actions = 15368, GitHub Advanced Security = 65
    integration_id: ?i64 = null,
};

pub const RuleCodeScanningTool = struct {
    tool: []const u8,
    // "none" | "errors" | "errors_and_warnings" | "all"
    alerts_threshold: ?[]const u8 = null,
    // "none" | "critical" | "high_or_higher" | "medium_or_higher" | "all"
    security_alerts_threshold: ?[]const u8 = null,
};

pub const RulePatternParameters = struct {
    name: ?[]const u8 = null,
    negate: ?bool = null,
    // "starts_with" | "ends_with" | "contains" | "regex"
    operator: []const u8 = "contains",
    pattern: []const u8 = "",
};

pub const Rule = union(enum) {
    required_linear_history: void,
    non_fast_forward: void,
    creation: void,
    deletion: void,
    required_signatures: void,
    update: UpdateParams,
    required_status_checks: RequiredStatusChecksParams,
    pull_request: PullRequestParams,
    code_scanning: CodeScanningParams,
    commit_message_pattern: RulePatternParameters,
    commit_author_email_pattern: RulePatternParameters,
    committer_email_pattern: RulePatternParameters,
    branch_name_pattern: RulePatternParameters,
    tag_name_pattern: RulePatternParameters,
    required_deployments: RequiredDeploymentsParams,
    // Catch-all for future rule types; .unknown = "the_type_string"
    unknown: []const u8,

    pub const UpdateParams = struct {
        update_allows_fetch_and_merge: ?bool = null,
    };

    pub const RequiredStatusChecksParams = struct {
        required_status_checks: ?[]RuleStatusCheck = null,
        strict_required_status_checks_policy: ?bool = null,
    };

    pub const PullRequestParams = struct {
        required_approving_review_count: ?i32 = null,
        dismiss_stale_reviews_on_push: ?bool = null,
        require_code_owner_review: ?bool = null,
        require_last_push_approval: ?bool = null,
    };

    pub const CodeScanningParams = struct {
        code_scanning_tools: ?[]RuleCodeScanningTool = null,
    };

    pub const RequiredDeploymentsParams = struct {
        required_deployment_environments: ?[][]const u8 = null,
    };
};

// Intermediate wire type: every rule arrives as { "type": "...", "parameters": {...} }
// We must parse via Value first because std.json can't auto-discriminate a union.
pub const RuleWire = struct {
    type: []const u8,
    parameters: ?std.json.Value = null,
};

/// Convert a parsed RuleWire into a strongly-typed Rule.
/// `params_alloc` is the allocator that owns the std.json.Value tree.
pub fn ruleFromWire(wire: RuleWire, params_alloc: Allocator) !Rule {
    const t = wire.type;

    if (std.mem.eql(u8, t, "required_linear_history")) return .required_linear_history;
    if (std.mem.eql(u8, t, "non_fast_forward")) return .non_fast_forward;
    if (std.mem.eql(u8, t, "creation")) return .creation;
    if (std.mem.eql(u8, t, "deletion")) return .deletion;
    if (std.mem.eql(u8, t, "required_signatures")) return .required_signatures;

    if (std.mem.eql(u8, t, "update")) {
        var p = Rule.UpdateParams{};
        if (wire.parameters) |params| {
            if (params.object.get("update_allows_fetch_and_merge")) |v|
                p.update_allows_fetch_and_merge = v.bool;
        }
        return .{ .update = p };
    }

    if (std.mem.eql(u8, t, "required_status_checks")) {
        var p = Rule.RequiredStatusChecksParams{};
        if (wire.parameters) |params| {
            if (params.object.get("strict_required_status_checks_policy")) |v|
                p.strict_required_status_checks_policy = v.bool;
            if (params.object.get("required_status_checks")) |checks_val| {
                const arr = checks_val.array.items;
                const checks = try params_alloc.alloc(RuleStatusCheck, arr.len);
                for (arr, 0..) |item, i| {
                    checks[i] = .{
                        .context = item.object.get("context").?.string,
                        .integration_id = if (item.object.get("integration_id")) |v|
                            v.integer
                        else
                            null,
                    };
                }
                p.required_status_checks = checks;
            }
        }
        return .{ .required_status_checks = p };
    }

    if (std.mem.eql(u8, t, "pull_request")) {
        var p = Rule.PullRequestParams{};
        if (wire.parameters) |params| {
            if (params.object.get("required_approving_review_count")) |v|
                p.required_approving_review_count = @intCast(v.integer);
            if (params.object.get("dismiss_stale_reviews_on_push")) |v|
                p.dismiss_stale_reviews_on_push = v.bool;
            if (params.object.get("require_code_owner_review")) |v|
                p.require_code_owner_review = v.bool;
            if (params.object.get("require_last_push_approval")) |v|
                p.require_last_push_approval = v.bool;
        }
        return .{ .pull_request = p };
    }

    if (std.mem.eql(u8, t, "code_scanning")) {
        var p = Rule.CodeScanningParams{};
        if (wire.parameters) |params| {
            if (params.object.get("code_scanning_tools")) |tools_val| {
                const arr = tools_val.array.items;
                const tools = try params_alloc.alloc(RuleCodeScanningTool, arr.len);
                for (arr, 0..) |item, i| {
                    tools[i] = .{
                        .tool = item.object.get("tool").?.string,
                        .alerts_threshold = if (item.object.get("alerts_threshold")) |v|
                            v.string
                        else
                            null,
                        .security_alerts_threshold = if (item.object.get("security_alerts_threshold")) |v|
                            v.string
                        else
                            null,
                    };
                }
                p.code_scanning_tools = tools;
            }
        }
        return .{ .code_scanning = p };
    }

    // Pattern rules all share the same parameters shape.
    inline for (.{
        .{ "commit_message_pattern", Rule.commit_message_pattern },
        .{ "commit_author_email_pattern", Rule.commit_author_email_pattern },
        .{ "committer_email_pattern", Rule.committer_email_pattern },
        .{ "branch_name_pattern", Rule.branch_name_pattern },
        .{ "tag_name_pattern", Rule.tag_name_pattern },
    }) |entry| {
        if (std.mem.eql(u8, t, entry[0])) {
            var p = RulePatternParameters{};
            if (wire.parameters) |params| {
                if (params.object.get("pattern")) |v| p.pattern = v.string;
                if (params.object.get("operator")) |v| p.operator = v.string;
                if (params.object.get("negate")) |v| p.negate = v.bool;
                if (params.object.get("name")) |v| p.name = v.string;
            }
            return @unionInit(Rule, entry[0], p);
        }
    }

    if (std.mem.eql(u8, t, "required_deployments")) {
        var p = Rule.RequiredDeploymentsParams{};
        if (wire.parameters) |params| {
            if (params.object.get("required_deployment_environments")) |envs_val| {
                const arr = envs_val.array.items;
                const envs = try params_alloc.alloc([]const u8, arr.len);
                for (arr, 0..) |item, i| envs[i] = item.string;
                p.required_deployment_environments = envs;
            }
        }
        return .{ .required_deployments = p };
    }

    return .{ .unknown = t };
}

// ── Ruleset response ──────────────────────────────────────────────────────

pub const RulesetBypassActor = struct {
    actor_id: ?i64 = null,
    // "Integration" | "OrganizationAdmin" | "RepositoryRole" | "Team"
    actor_type: []const u8 = "",
    // "always" | "pull_request"
    bypass_mode: []const u8 = "always",
};

pub const RulesetRefCondition = struct {
    include: ?[][]const u8 = null,
    exclude: ?[][]const u8 = null,
};

pub const RulesetConditions = struct {
    ref_name: ?RulesetRefCondition = null,
};

// Rules are kept as []Rule after deserialization via ruleFromWire().
pub const RulesetDetailsResponse = struct {
    id: i64,
    name: []const u8,
    // "branch" | "tag" | "push"
    target: ?[]const u8 = null,
    // "active" | "disabled" | "evaluate"
    enforcement: []const u8 = "active",
    bypass_actors: ?[]RulesetBypassActor = null,
    conditions: ?RulesetConditions = null,
    rules: []Rule,
};

// ── RepositoryState — aggregate of all fetched API data ──────────────────
//
// Mirrors Java's RepositoryState record. Each field is either a slice (owned
// by the per-repo arena), a map (must deinit), or a scalar/optional.
// The caller (OrgChecker.fetchState) builds this; the arena is freed after
// computeDiffs() returns, so no deep cleanup is needed.

pub const RepositoryState = struct {
    name: []const u8,
    summary: RepositoryMinimal,
    details: RepositoryFull,
    vulnerability_alerts: bool,
    automated_security_fixes: bool,
    // branch name → protection (arena-owned)
    branch_protections: std.StringHashMap(BranchProtectionResponse),
    action_secret_names: [][]const u8,
    // environment name → secret names (arena-owned)
    environment_secret_names: std.StringHashMap([][]const u8),
    workflow_permissions: WorkflowPermissions,
    rulesets: []RulesetDetailsResponse,
    pages: ?PagesResponse,
    // environment name → details (arena-owned)
    environment_details: std.StringHashMap(EnvironmentDetailsResponse),
    immutable_releases: bool,
    private_vulnerability_reporting: bool,
    code_scanning_default_setup: bool,
};

// ── CheckResult ───────────────────────────────────────────────────────────
//
// Mirrors Java's CheckResult record and its nested RepoCheckResult.

pub const CheckStatus = enum { ok, drift, @"error", unknown, missing };

pub const RepoCheckResult = struct {
    name: []const u8,
    status: CheckStatus,
    // Slice of human-readable diff descriptions, e.g. "allow_auto_merge: want=true got=false"
    diffs: []const []const u8,
    // Non-null only when status == .@"error"
    err: ?[]const u8,
};

pub const CheckResult = struct {
    repos: []RepoCheckResult,

    pub fn hasDrift(self: CheckResult) bool {
        for (self.repos) |r| {
            if (r.status == .drift or r.status == .@"error") return true;
        }
        return false;
    }

    pub fn countByStatus(self: CheckResult, s: CheckStatus) usize {
        var n: usize = 0;
        for (self.repos) |r| if (r.status == s) { n += 1; };
        return n;
    }
};

// ── RepositoryArgs — desired configuration ────────────────────────────────
//
// Java uses a fluent builder class (RepositoryArgs.create("name")
//   .allowMergeCommit(false).build()). In Zig we use a plain struct with
// default values. Callers use struct-literal syntax with named fields:
//
//   const repo = RepositoryArgs{
//       .name = "drifty",
//       .allow_auto_merge = true,
//       .delete_branch_on_merge = true,
//   };
//
// To "extend" a base config, use mergeArgs() in main.zig (comptime helper).

pub const StatusCheckArgs = struct {
    context: []const u8,
    // GitHub Actions = 15368, GitHub Advanced Security = 65
    app_id: ?i64 = null,
};

pub const CodeScanningToolArgs = struct {
    tool: []const u8,
    alerts_threshold: []const u8 = "errors",
    security_alerts_threshold: []const u8 = "high_or_higher",
};

pub const BranchProtectionArgs = struct {
    branch: []const u8 = "main",
    required_status_checks: []const StatusCheckArgs = &.{},
    enforce_admins: bool = false,
    required_linear_history: bool = false,
    allow_force_pushes: bool = false,
    allow_deletions: bool = false,
};

pub const RulesetArgs = struct {
    name: []const u8,
    // ref patterns to include, e.g. "refs/heads/main"
    include_patterns: []const []const u8 = &.{},
    exclude_patterns: []const []const u8 = &.{},
    enforcement: []const u8 = "active",
    required_linear_history: bool = false,
    no_force_pushes: bool = false,
    required_status_checks: []const StatusCheckArgs = &.{},
    required_code_scanning: []const CodeScanningToolArgs = &.{},
    bypass_actors: []const RulesetBypassActor = &.{},
};

pub const EnvironmentArgs = struct {
    name: []const u8,
    secrets: []const []const u8 = &.{},
    // wait timer in minutes, null = not configured
    wait_timer: ?i32 = null,
};

pub const RepositoryArgs = struct {
    name: []const u8,
    archived: bool = false,
    description: []const u8 = "",
    homepage_url: []const u8 = "",
    // "public" | "private" | "internal"
    visibility: []const u8 = "public",
    topics: []const []const u8 = &.{},
    actions_secrets: []const []const u8 = &.{},
    has_issues: bool = true,
    has_projects: bool = true,
    has_wiki: bool = true,
    allow_merge_commit: bool = true,
    allow_squash_merge: bool = true,
    allow_rebase_merge: bool = true,
    allow_auto_merge: bool = false,
    allow_update_branch: bool = false,
    delete_branch_on_merge: bool = false,
    default_branch: []const u8 = "main",
    vulnerability_alerts: bool = true,
    automated_security_fixes: bool = false,
    secret_scanning: bool = true,
    secret_scanning_push_protection: bool = true,
    private_vulnerability_reporting: bool = false,
    code_scanning_default_setup: bool = false,
    immutable_releases: bool = false,
    // "read" | "write"
    default_workflow_permissions: []const u8 = "write",
    can_approve_pull_request_reviews: bool = true,
    pages: bool = false,
    branch_protections: []const BranchProtectionArgs = &.{},
    rulesets: []const RulesetArgs = &.{},
    environments: []const EnvironmentArgs = &.{},
};
