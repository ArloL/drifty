//! github_client.zig — wraps std.http.Client for GitHub API calls
//!
//! Translation notes vs. Java GitHubClient:
//!   HttpClient (Java 11)        → std.http.Client
//!   Jackson ObjectMapper        → std.json.parseFromSlice (explicit, no annotations)
//!   String.format("/repos/...")  → std.fmt.bufPrint (stack buffer, no alloc)
//!   resp.statusCode()           → result.status (std.http.Status enum)
//!   Link header pagination      → extractNextLink() parses raw header bytes
//!
//! API version: 2022-11-28  (GitHub's stable v3 JSON API)

const std = @import("std");
const Allocator = std.mem.Allocator;
const models = @import("models.zig");

pub const GitHubApiError = error{
    /// Non-2xx response that is not specifically handled below.
    UnexpectedStatus,
    /// 429 rate-limit, even after retry sleep.
    RateLimited,
    /// 404 Not Found.
    NotFound,
    /// 403 Forbidden.
    Forbidden,
};

// ── Low-level response ────────────────────────────────────────────────────

/// Owns the response body bytes and a copy of the server header block.
/// Caller must call deinit() when done.
pub const RawResponse = struct {
    status: std.http.Status,
    body: []u8,
    // Raw HTTP/1.1 header block copied from the server_header_buffer.
    // Used to extract Link and X-RateLimit-* headers after fetch() returns,
    // since std.http.Client.fetch() does not expose header iterators directly.
    header_bytes: []u8,
    alloc: Allocator,

    pub fn deinit(self: RawResponse) void {
        self.alloc.free(self.body);
        self.alloc.free(self.header_bytes);
    }

    /// Find a header value by name (case-insensitive) in the raw header block.
    /// Returns a slice into header_bytes, or null if not present.
    pub fn header(self: RawResponse, name: []const u8) ?[]const u8 {
        return findHeader(self.header_bytes, name);
    }
};

fn findHeader(buf: []const u8, name: []const u8) ?[]const u8 {
    var lines = std.mem.splitScalar(u8, buf, '\n');
    while (lines.next()) |line| {
        const colon = std.mem.indexOfScalar(u8, line, ':') orelse continue;
        const key = std.mem.trim(u8, line[0..colon], " \r");
        if (!std.ascii.eqlIgnoreCase(key, name)) continue;
        return std.mem.trim(u8, line[colon + 1 ..], " \r\n");
    }
    return null;
}

/// Extract the "next" URL from a Link header value.
///   Link: <https://api.github.com/repos?page=2>; rel="next", ...
pub fn extractNextLink(link_header: []const u8) ?[]const u8 {
    var parts = std.mem.splitScalar(u8, link_header, ',');
    while (parts.next()) |part| {
        const trimmed = std.mem.trim(u8, part, " ");
        if (std.mem.indexOf(u8, trimmed, "rel=\"next\"") == null) continue;
        const start = std.mem.indexOfScalar(u8, trimmed, '<') orelse continue;
        const end = std.mem.indexOfScalar(u8, trimmed, '>') orelse continue;
        if (end <= start + 1) continue;
        return trimmed[start + 1 .. end];
    }
    return null;
}

// ── GitHubClient ──────────────────────────────────────────────────────────

pub const GitHubClient = struct {
    alloc: Allocator,
    token: []const u8,
    base_url: []const u8,
    http: std.http.Client,

    pub fn init(alloc: Allocator, token: []const u8) GitHubClient {
        return .{
            .alloc = alloc,
            .token = token,
            .base_url = "https://api.github.com",
            .http = .{ .allocator = alloc },
        };
    }

    pub fn initWithBaseUrl(
        alloc: Allocator,
        token: []const u8,
        base_url: []const u8,
    ) GitHubClient {
        return .{
            .alloc = alloc,
            .token = token,
            .base_url = base_url,
            .http = .{ .allocator = alloc },
        };
    }

    pub fn deinit(self: *GitHubClient) void {
        self.http.deinit();
    }

    // ── Core HTTP ─────────────────────────────────────────────────────────

    fn rawFetch(
        self: *GitHubClient,
        method: std.http.Method,
        url: []const u8,
        payload: ?[]const u8,
    ) !RawResponse {
        // Build the Authorization header value on the stack.
        // Max token length ~100 chars; 512 is safe.
        var auth_buf: [512]u8 = undefined;
        const auth_val = blk: {
            const prefix = "Bearer ";
            if (prefix.len + self.token.len > auth_buf.len)
                return error.TokenTooLong;
            @memcpy(auth_buf[0..prefix.len], prefix);
            @memcpy(auth_buf[prefix.len .. prefix.len + self.token.len], self.token);
            break :blk auth_buf[0 .. prefix.len + self.token.len];
        };

        const content_type: []const u8 = if (payload != null)
            "application/json"
        else
            "application/vnd.github+json";

        // Extra headers passed as a slice; stack-allocated, so they must
        // outlive the open() call (they do — they live until rawFetch returns).
        const extra_headers = [_]std.http.Header{
            .{ .name = "Authorization", .value = auth_val },
            .{ .name = "Accept", .value = "application/vnd.github+json" },
            .{ .name = "X-GitHub-Api-Version", .value = "2022-11-28" },
            .{ .name = "Content-Type", .value = content_type },
        };

        // 16 KiB covers all GitHub response headers comfortably.
        // Must outlive the request (deinit() happens via defer below).
        var server_header_buf: [16 * 1024]u8 = undefined;

        // Lower-level open/send/wait gives us unambiguous access to the
        // response status code and headers, which the high-level fetch()
        // convenience wrapper does not expose directly.
        //
        // std.http.Client.open() in Zig 0.14.0:
        //   client.open(method, uri, options) → !Request
        //   request.send()   — sends headers (and optionally a body)
        //   request.finish() — signals end of request body (even if empty)
        //   request.wait()   — reads response headers; after this,
        //                      request.response.status is valid
        //   request.reader() — returns a Reader for the response body
        const uri = try std.Uri.parse(url);

        var req = try self.http.open(method, uri, .{
            .server_header_buffer = &server_header_buf,
            .extra_headers = &extra_headers,
        });
        defer req.deinit();

        // Set Content-Length for requests with a body.
        if (payload) |p| {
            req.transfer_encoding = .{ .content_length = p.len };
        }

        try req.send();
        if (payload) |p| try req.writeAll(p);
        try req.finish();
        try req.wait(); // response headers are now available

        const status = req.response.status;

        // Rate-limit: log and surface as an error. A production implementation
        // would parse X-RateLimit-Reset (Unix timestamp in the response headers)
        // and sleep until that time, then retry by calling rawFetch recursively
        // or via a loop at the call site. The open/send/wait pattern makes retry
        // straightforward: deinit the request, sleep, then open a new one.
        if (status == .too_many_requests) {
            std.debug.print("Rate limited (HTTP 429) — retry after sleep\n", .{});
            return GitHubApiError.RateLimited;
        }

        // Read the response body (limit 32 MiB — generous for GitHub API).
        var body_list = std.ArrayList(u8).init(self.alloc);
        errdefer body_list.deinit();
        try req.reader().readAllArrayList(&body_list, 32 * 1024 * 1024);

        // Copy the raw header block so the RawResponse can outlive the stack
        // buffer. server_header_buf holds the raw HTTP/1.1 header bytes
        // (null-terminated string).
        const header_end = std.mem.indexOfScalar(u8, &server_header_buf, 0) orelse
            server_header_buf.len;
        const header_copy = try self.alloc.dupe(u8, server_header_buf[0..header_end]);
        errdefer self.alloc.free(header_copy);

        return .{
            .status = status,
            .body = try body_list.toOwnedSlice(),
            .header_bytes = header_copy,
            .alloc = self.alloc,
        };
    }

    pub fn get(self: *GitHubClient, url: []const u8) !RawResponse {
        return self.rawFetch(.GET, url, null);
    }

    pub fn post(self: *GitHubClient, url: []const u8, body: []const u8) !RawResponse {
        return self.rawFetch(.POST, url, body);
    }

    pub fn put(self: *GitHubClient, url: []const u8, body: []const u8) !RawResponse {
        return self.rawFetch(.PUT, url, body);
    }

    pub fn patch(self: *GitHubClient, url: []const u8, body: []const u8) !RawResponse {
        return self.rawFetch(.PATCH, url, body);
    }

    pub fn delete(self: *GitHubClient, url: []const u8) !RawResponse {
        return self.rawFetch(.DELETE, url, null);
    }

    // ── Pagination ────────────────────────────────────────────────────────
    //
    // Java: collectPaginatedArrayItems() uses resp.headers().firstValue("Link")
    //       and walks next-page URLs accumulating Jackson JsonNode items.
    //
    // Zig: same logic; items are std.json.Value deep-copied into `out_alloc`
    //      because each page is parsed in a temporary arena that resets.

    /// Fetch all pages of a JSON array endpoint, appending parsed items to
    /// `out`. Items are deep-copied into `out_alloc` (outlives temp arenas).
    /// `array_key`: if non-null the response body is an object and the array
    /// lives under this key (e.g. "secrets", "environments"). If null, the
    /// response body IS the array.
    pub fn fetchAllPages(
        self: *GitHubClient,
        first_url: []const u8,
        array_key: ?[]const u8,
        out_alloc: Allocator,
        out: *std.ArrayList(std.json.Value),
    ) !void {
        var page_arena = std.heap.ArenaAllocator.init(self.alloc);
        defer page_arena.deinit();
        const pa = page_arena.allocator();

        // Work with a mutable copy of the current URL.
        var url_buf: [2048]u8 = undefined;
        if (first_url.len >= url_buf.len) return error.UrlTooLong;
        @memcpy(url_buf[0..first_url.len], first_url);
        var current_len = first_url.len;

        while (true) {
            const resp = try self.get(url_buf[0..current_len]);
            defer resp.deinit();

            if (@intFromEnum(resp.status) < 200 or @intFromEnum(resp.status) >= 300)
                return GitHubApiError.UnexpectedStatus;

            const parsed = try std.json.parseFromSlice(
                std.json.Value,
                pa,
                resp.body,
                .{ .ignore_unknown_fields = true },
            );
            // No need to call parsed.deinit() — page_arena owns it and resets.

            const array_val: std.json.Value = if (array_key) |key|
                parsed.value.object.get(key) orelse std.json.Value{ .array = .init(pa) }
            else
                parsed.value;

            for (array_val.array.items) |item| {
                try out.append(try deepCopy(item, out_alloc));
            }

            // Follow the next-page link if present.
            const link = resp.header("link") orelse break;
            const next = extractNextLink(link) orelse break;

            if (next.len >= url_buf.len) return error.UrlTooLong;
            @memcpy(url_buf[0..next.len], next);
            current_len = next.len;

            _ = page_arena.reset(.retain_capacity);
        }
    }

    // ── Public API methods ────────────────────────────────────────────────
    //
    // Each method allocates results into `out_alloc` (typically the per-repo
    // ArenaAllocator) so callers need no explicit cleanup beyond the arena.

    pub fn listOrgRepos(
        self: *GitHubClient,
        org: []const u8,
        out_alloc: Allocator,
    ) ![]models.RepositoryMinimal {
        var url_buf: [512]u8 = undefined;
        var url = try std.fmt.bufPrint(
            &url_buf,
            "{s}/orgs/{s}/repos?per_page=100&type=all",
            .{ self.base_url, org },
        );

        // GitHub returns 404 for personal accounts; fall back to /user/repos.
        const probe = try self.get(url);
        defer probe.deinit();

        var fallback_buf: [512]u8 = undefined;
        if (probe.status == .not_found) {
            url = try std.fmt.bufPrint(
                &fallback_buf,
                "{s}/user/repos?per_page=100&type=owner",
                .{self.base_url},
            );
        } else if (@intFromEnum(probe.status) < 200 or @intFromEnum(probe.status) >= 300) {
            std.debug.print(
                "HTTP {} listing repos for {s}: {s}\n",
                .{ @intFromEnum(probe.status), org, probe.body },
            );
            return GitHubApiError.UnexpectedStatus;
        }

        var items = std.ArrayList(std.json.Value).init(self.alloc);
        defer items.deinit();
        try self.fetchAllPages(url, null, self.alloc, &items);

        // Deserialize each item into the typed struct.
        // We re-stringify each Value to reuse parseFromSlice (avoids a custom
        // walker). For a production version a direct Value→struct mapper would
        // be more efficient, but this is clear for a prototype.
        const result = try out_alloc.alloc(models.RepositoryMinimal, items.items.len);
        for (items.items, 0..) |item, i| {
            const json_str = try std.json.stringifyAlloc(self.alloc, item, .{});
            defer self.alloc.free(json_str);
            const parsed = try std.json.parseFromSlice(
                models.RepositoryMinimal,
                out_alloc,
                json_str,
                .{ .ignore_unknown_fields = true },
            );
            result[i] = parsed.value;
        }
        return result;
    }

    pub fn getRepo(
        self: *GitHubClient,
        org: []const u8,
        repo: []const u8,
        out_alloc: Allocator,
    ) !models.RepositoryFull {
        var url_buf: [512]u8 = undefined;
        const url = try std.fmt.bufPrint(
            &url_buf,
            "{s}/repos/{s}/{s}",
            .{ self.base_url, org, repo },
        );
        const resp = try self.get(url);
        defer resp.deinit();
        if (resp.status != .ok) return GitHubApiError.UnexpectedStatus;

        const parsed = try std.json.parseFromSlice(
            models.RepositoryFull,
            out_alloc,
            resp.body,
            .{ .ignore_unknown_fields = true },
        );
        return parsed.value;
    }

    /// Returns true if vulnerability alerts are enabled (HTTP 204), false if
    /// disabled (HTTP 404).
    pub fn getVulnerabilityAlerts(
        self: *GitHubClient,
        org: []const u8,
        repo: []const u8,
    ) !bool {
        var url_buf: [512]u8 = undefined;
        const url = try std.fmt.bufPrint(
            &url_buf,
            "{s}/repos/{s}/{s}/vulnerability-alerts",
            .{ self.base_url, org, repo },
        );
        const resp = try self.get(url);
        defer resp.deinit();
        return switch (resp.status) {
            .no_content => true,
            .not_found => false,
            else => GitHubApiError.UnexpectedStatus,
        };
    }

    /// Same pattern as vulnerability alerts.
    pub fn getAutomatedSecurityFixes(
        self: *GitHubClient,
        org: []const u8,
        repo: []const u8,
    ) !bool {
        var url_buf: [512]u8 = undefined;
        const url = try std.fmt.bufPrint(
            &url_buf,
            "{s}/repos/{s}/{s}/automated-security-fixes",
            .{ self.base_url, org, repo },
        );
        const resp = try self.get(url);
        defer resp.deinit();
        return switch (resp.status) {
            .no_content => true,
            .not_found => false,
            else => GitHubApiError.UnexpectedStatus,
        };
    }

    pub fn getPrivateVulnerabilityReporting(
        self: *GitHubClient,
        org: []const u8,
        repo: []const u8,
    ) !bool {
        var url_buf: [512]u8 = undefined;
        const url = try std.fmt.bufPrint(
            &url_buf,
            "{s}/repos/{s}/{s}/private-vulnerability-reporting",
            .{ self.base_url, org, repo },
        );
        const resp = try self.get(url);
        defer resp.deinit();
        // Response: { "enabled": true }
        if (resp.status == .not_found) return false;
        if (resp.status != .ok) return false;

        const Payload = struct { enabled: bool };
        const parsed = try std.json.parseFromSlice(
            Payload,
            self.alloc,
            resp.body,
            .{ .ignore_unknown_fields = true },
        );
        defer parsed.deinit();
        return parsed.value.enabled;
    }

    pub fn getCodeScanningDefaultSetup(
        self: *GitHubClient,
        org: []const u8,
        repo: []const u8,
    ) !bool {
        var url_buf: [512]u8 = undefined;
        const url = try std.fmt.bufPrint(
            &url_buf,
            "{s}/repos/{s}/{s}/code-scanning/default-setup",
            .{ self.base_url, org, repo },
        );
        const resp = try self.get(url);
        defer resp.deinit();
        if (resp.status == .not_found or resp.status == .forbidden) return false;
        if (resp.status != .ok) return false;

        // Response: { "state": "configured" | "not-configured", ... }
        const Payload = struct { state: []const u8 };
        const parsed = try std.json.parseFromSlice(
            Payload,
            self.alloc,
            resp.body,
            .{ .ignore_unknown_fields = true },
        );
        defer parsed.deinit();
        return std.mem.eql(u8, parsed.value.state, "configured");
    }

    pub fn getImmutableReleases(
        self: *GitHubClient,
        org: []const u8,
        repo: []const u8,
    ) !?bool {
        var url_buf: [512]u8 = undefined;
        const url = try std.fmt.bufPrint(
            &url_buf,
            "{s}/repos/{s}/{s}/properties/values",
            .{ self.base_url, org, repo },
        );
        const resp = try self.get(url);
        defer resp.deinit();
        if (resp.status == .not_found or resp.status == .forbidden) return null;
        if (resp.status != .ok) return null;

        // Response: array of { "property_name": "...", "value": "..." }
        const Entry = struct {
            property_name: []const u8,
            value: ?[]const u8 = null,
        };
        const parsed = try std.json.parseFromSlice(
            []Entry,
            self.alloc,
            resp.body,
            .{ .ignore_unknown_fields = true },
        );
        defer parsed.deinit();

        for (parsed.value) |entry| {
            if (std.mem.eql(u8, entry.property_name, "immutable-releases")) {
                if (entry.value) |v| return std.mem.eql(u8, v, "true");
            }
        }
        return null;
    }

    pub fn getWorkflowPermissions(
        self: *GitHubClient,
        org: []const u8,
        repo: []const u8,
        out_alloc: Allocator,
    ) !models.WorkflowPermissions {
        var url_buf: [512]u8 = undefined;
        const url = try std.fmt.bufPrint(
            &url_buf,
            "{s}/repos/{s}/{s}/actions/permissions/workflow",
            .{ self.base_url, org, repo },
        );
        const resp = try self.get(url);
        defer resp.deinit();
        if (resp.status == .forbidden) return GitHubApiError.Forbidden;
        if (resp.status != .ok) return GitHubApiError.UnexpectedStatus;

        const parsed = try std.json.parseFromSlice(
            models.WorkflowPermissions,
            out_alloc,
            resp.body,
            .{ .ignore_unknown_fields = true },
        );
        return parsed.value;
    }

    pub fn getPages(
        self: *GitHubClient,
        org: []const u8,
        repo: []const u8,
        out_alloc: Allocator,
    ) !?models.PagesResponse {
        var url_buf: [512]u8 = undefined;
        const url = try std.fmt.bufPrint(
            &url_buf,
            "{s}/repos/{s}/{s}/pages",
            .{ self.base_url, org, repo },
        );
        const resp = try self.get(url);
        defer resp.deinit();
        return switch (resp.status) {
            .ok => blk: {
                const parsed = try std.json.parseFromSlice(
                    models.PagesResponse,
                    out_alloc,
                    resp.body,
                    .{ .ignore_unknown_fields = true },
                );
                break :blk parsed.value;
            },
            .not_found => null,
            .forbidden => GitHubApiError.Forbidden,
            else => GitHubApiError.UnexpectedStatus,
        };
    }

    pub fn listRulesets(
        self: *GitHubClient,
        org: []const u8,
        repo: []const u8,
        out_alloc: Allocator,
    ) ![]models.RulesetDetailsResponse {
        // Phase 1: list ruleset summaries to get IDs.
        var url_buf: [512]u8 = undefined;
        const list_url = try std.fmt.bufPrint(
            &url_buf,
            "{s}/repos/{s}/{s}/rulesets?per_page=100",
            .{ self.base_url, org, repo },
        );

        var summaries = std.ArrayList(std.json.Value).init(self.alloc);
        defer summaries.deinit();
        try self.fetchAllPages(list_url, null, self.alloc, &summaries);

        if (summaries.items.len == 0) return out_alloc.alloc(models.RulesetDetailsResponse, 0);

        // Phase 2: fetch each ruleset's full details (includes rules).
        const result = try out_alloc.alloc(models.RulesetDetailsResponse, summaries.items.len);

        for (summaries.items, 0..) |summary, i| {
            const id = summary.object.get("id").?.integer;
            var detail_url_buf: [512]u8 = undefined;
            const detail_url = try std.fmt.bufPrint(
                &detail_url_buf,
                "{s}/repos/{s}/{s}/rulesets/{d}",
                .{ self.base_url, org, repo, id },
            );
            const resp = try self.get(detail_url);
            defer resp.deinit();
            if (resp.status != .ok) return GitHubApiError.UnexpectedStatus;

            result[i] = try parseRulesetDetails(resp.body, out_alloc);
        }
        return result;
    }

    pub fn getActionSecretNames(
        self: *GitHubClient,
        org: []const u8,
        repo: []const u8,
        out_alloc: Allocator,
    ) ![][]const u8 {
        var url_buf: [512]u8 = undefined;
        const url = try std.fmt.bufPrint(
            &url_buf,
            "{s}/repos/{s}/{s}/actions/secrets?per_page=100",
            .{ self.base_url, org, repo },
        );

        var items = std.ArrayList(std.json.Value).init(self.alloc);
        defer items.deinit();
        try self.fetchAllPages(url, "secrets", self.alloc, &items);

        const names = try out_alloc.alloc([]const u8, items.items.len);
        for (items.items, 0..) |item, i| {
            names[i] = try out_alloc.dupe(u8, item.object.get("name").?.string);
        }
        return names;
    }

    pub fn getEnvironments(
        self: *GitHubClient,
        org: []const u8,
        repo: []const u8,
        out_alloc: Allocator,
    ) ![]models.EnvironmentDetailsResponse {
        var url_buf: [512]u8 = undefined;
        const url = try std.fmt.bufPrint(
            &url_buf,
            "{s}/repos/{s}/{s}/environments?per_page=100",
            .{ self.base_url, org, repo },
        );
        const resp = try self.get(url);
        defer resp.deinit();
        if (resp.status == .not_found) return out_alloc.alloc(models.EnvironmentDetailsResponse, 0);
        if (resp.status != .ok) return GitHubApiError.UnexpectedStatus;

        const Payload = struct {
            environments: []models.EnvironmentDetailsResponse,
        };
        const parsed = try std.json.parseFromSlice(
            Payload,
            out_alloc,
            resp.body,
            .{ .ignore_unknown_fields = true },
        );
        return parsed.value.environments;
    }

    pub fn getEnvironmentSecretNames(
        self: *GitHubClient,
        org: []const u8,
        repo: []const u8,
        env_name: []const u8,
        out_alloc: Allocator,
    ) ![][]const u8 {
        var url_buf: [512]u8 = undefined;
        const url = try std.fmt.bufPrint(
            &url_buf,
            "{s}/repos/{s}/{s}/environments/{s}/secrets?per_page=100",
            .{ self.base_url, org, repo, env_name },
        );

        var items = std.ArrayList(std.json.Value).init(self.alloc);
        defer items.deinit();
        try self.fetchAllPages(url, "secrets", self.alloc, &items);

        const names = try out_alloc.alloc([]const u8, items.items.len);
        for (items.items, 0..) |item, i| {
            names[i] = try out_alloc.dupe(u8, item.object.get("name").?.string);
        }
        return names;
    }

    // Fetching branch protections — only for public non-archived repos.
    pub fn getProtectedBranches(
        self: *GitHubClient,
        org: []const u8,
        repo: []const u8,
        out_alloc: Allocator,
    ) ![][]const u8 {
        var url_buf: [512]u8 = undefined;
        const url = try std.fmt.bufPrint(
            &url_buf,
            "{s}/repos/{s}/{s}/branches?protected=true&per_page=100",
            .{ self.base_url, org, repo },
        );

        var items = std.ArrayList(std.json.Value).init(self.alloc);
        defer items.deinit();
        try self.fetchAllPages(url, null, self.alloc, &items);

        const names = try out_alloc.alloc([]const u8, items.items.len);
        for (items.items, 0..) |item, i| {
            names[i] = try out_alloc.dupe(u8, item.object.get("name").?.string);
        }
        return names;
    }

    pub fn getBranchProtection(
        self: *GitHubClient,
        org: []const u8,
        repo: []const u8,
        branch: []const u8,
        out_alloc: Allocator,
    ) !?models.BranchProtectionResponse {
        var url_buf: [512]u8 = undefined;
        const url = try std.fmt.bufPrint(
            &url_buf,
            "{s}/repos/{s}/{s}/branches/{s}/protection",
            .{ self.base_url, org, repo, branch },
        );
        const resp = try self.get(url);
        defer resp.deinit();
        return switch (resp.status) {
            .ok => blk: {
                const parsed = try std.json.parseFromSlice(
                    models.BranchProtectionResponse,
                    out_alloc,
                    resp.body,
                    .{ .ignore_unknown_fields = true },
                );
                break :blk parsed.value;
            },
            .not_found => null,
            .forbidden => null,
            else => GitHubApiError.UnexpectedStatus,
        };
    }
};

// ── Ruleset deserialization helper ────────────────────────────────────────
//
// Java: Jackson auto-dispatches via @JsonTypeInfo. The Rule sealed interface
//       delegates to the correct subtype based on the "type" field.
// Zig:  We parse the whole ruleset into a std.json.Value, extract the "rules"
//       array manually, and call ruleFromWire() per element.

fn parseRulesetDetails(
    json_body: []const u8,
    out_alloc: Allocator,
) !models.RulesetDetailsResponse {
    // Use a temporary arena for the intermediate Value; we'll copy what we
    // need into out_alloc.
    var tmp_arena = std.heap.ArenaAllocator.init(out_alloc);
    defer tmp_arena.deinit();
    const ta = tmp_arena.allocator();

    const parsed = try std.json.parseFromSlice(
        std.json.Value,
        ta,
        json_body,
        .{ .ignore_unknown_fields = true },
    );
    const obj = parsed.value.object;

    // Scalar fields.
    const id = obj.get("id").?.integer;
    const name = try out_alloc.dupe(u8, obj.get("name").?.string);
    const enforcement = if (obj.get("enforcement")) |v|
        try out_alloc.dupe(u8, v.string)
    else
        "active";
    const target = if (obj.get("target")) |v| try out_alloc.dupe(u8, v.string) else null;

    // Bypass actors.
    var bypass_actors: ?[]models.RulesetBypassActor = null;
    if (obj.get("bypass_actors")) |ba_val| {
        const arr = ba_val.array.items;
        const actors = try out_alloc.alloc(models.RulesetBypassActor, arr.len);
        for (arr, 0..) |item, i| {
            const ao = item.object;
            actors[i] = .{
                .actor_id = if (ao.get("actor_id")) |v| v.integer else null,
                .actor_type = if (ao.get("actor_type")) |v| try out_alloc.dupe(u8, v.string) else "",
                .bypass_mode = if (ao.get("bypass_mode")) |v| try out_alloc.dupe(u8, v.string) else "always",
            };
        }
        bypass_actors = actors;
    }

    // Conditions.
    var conditions: ?models.RulesetConditions = null;
    if (obj.get("conditions")) |cond_val| {
        var c = models.RulesetConditions{};
        if (cond_val.object.get("ref_name")) |rn| {
            var ref = models.RulesetRefCondition{};
            if (rn.object.get("include")) |inc| {
                const include = try out_alloc.alloc([]const u8, inc.array.items.len);
                for (inc.array.items, 0..) |v, i| include[i] = try out_alloc.dupe(u8, v.string);
                ref.include = include;
            }
            if (rn.object.get("exclude")) |exc| {
                const exclude = try out_alloc.alloc([]const u8, exc.array.items.len);
                for (exc.array.items, 0..) |v, i| exclude[i] = try out_alloc.dupe(u8, v.string);
                ref.exclude = exclude;
            }
            c.ref_name = ref;
        }
        conditions = c;
    }

    // Rules — the polymorphic part.
    var rules: []models.Rule = &.{};
    if (obj.get("rules")) |rules_val| {
        const arr = rules_val.array.items;
        const rule_slice = try out_alloc.alloc(models.Rule, arr.len);
        for (arr, 0..) |item, i| {
            const wire = models.RuleWire{
                .type = item.object.get("type").?.string,
                .parameters = item.object.get("parameters"),
            };
            rule_slice[i] = try models.ruleFromWire(wire, out_alloc);
        }
        rules = rule_slice;
    }

    return models.RulesetDetailsResponse{
        .id = id,
        .name = name,
        .target = target,
        .enforcement = enforcement,
        .bypass_actors = bypass_actors,
        .conditions = conditions,
        .rules = rules,
    };
}

// ── Deep-copy a std.json.Value into a new allocator ───────────────────────
//
// Needed in fetchAllPages: page arenas reset between pages, so items must be
// copied into the longer-lived out_alloc before the reset.

fn deepCopy(value: std.json.Value, alloc: Allocator) !std.json.Value {
    return switch (value) {
        .null => .null,
        .bool => |b| .{ .bool = b },
        .integer => |n| .{ .integer = n },
        .float => |f| .{ .float = f },
        .number_string => |s| .{ .number_string = try alloc.dupe(u8, s) },
        .string => |s| .{ .string = try alloc.dupe(u8, s) },
        .array => |arr| blk: {
            var new = std.json.Array.init(alloc);
            for (arr.items) |item| try new.append(try deepCopy(item, alloc));
            break :blk .{ .array = new };
        },
        .object => |obj| blk: {
            var new = std.json.ObjectMap.init(alloc);
            var it = obj.iterator();
            while (it.next()) |entry| {
                try new.put(
                    try alloc.dupe(u8, entry.key_ptr.*),
                    try deepCopy(entry.value_ptr.*, alloc),
                );
            }
            break :blk .{ .object = new };
        },
    };
}
