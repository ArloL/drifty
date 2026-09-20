package io.github.arlol.githubcheck.client;

import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.jspecify.annotations.Nullable;

/**
 * GraphQL's answers rewritten as the REST shape they replace.
 * <p>
 * One query per repository answers what six requests answered — a ruleset
 * listing and each ruleset's rules, a branch listing and each branch's
 * protection, the collaborators and the vulnerability-alerts flag — and the
 * cheapest way to keep that from becoming a second translator is to rewrite the
 * JSON rather than the records. Everything downstream still sees
 * {@code RulesetDetailsResponse} and {@code BranchProtectionResponse}, so
 * {@code ActualTypes} and every test over it are unchanged.
 * <p>
 * Verified against all 45 active repositories of one account on 2026-09-19: the
 * {@code ActualRuleset} and {@code ActualBranchProtection} the two routes
 * produce were identical for every one of them.
 * <p>
 * Three of the differences are not spelling. {@code lockAllowsFetchAndMerge} is
 * REST's {@code allow_fork_syncing}; a ruleset's status checks name their app
 * as {@code integrationId} where a branch protection rule's name it as
 * {@code app { databaseId }}; and a bypass actor is a union plus three booleans
 * where REST has an {@code actor_type} string. Everything else is camelCase
 * against snake_case and {@code SCREAMING_CASE} against the wire spelling.
 */
final class GraphQlShape {

	private static final JsonNodeFactory F = JsonNodeFactory.instance;

	private GraphQlShape() {
	}

	/**
	 * One {@code collaborators} edge as an entry of
	 * {@code GET .../collaborators}.
	 */
	static ObjectNode collaborator(JsonNode edge) {
		ObjectNode out = F.objectNode();
		out.put("login", edge.path("node").path("login").asText());
		ObjectNode permissions = out.putObject("permissions");
		// The levels imply the ones below them, and Permissions.level() reads
		// the highest that is on — so only the one GraphQL names is set.
		switch (edge.path("permission").asText()) {
		case "ADMIN" -> permissions.put("admin", true);
		case "MAINTAIN" -> permissions.put("maintain", true);
		case "WRITE" -> permissions.put("push", true);
		case "TRIAGE" -> permissions.put("triage", true);
		default -> permissions.put("pull", true);
		}
		return out;
	}

	/**
	 * One element of {@code branchProtectionRules.nodes} as {@code GET
	 * .../branches/{branch}/protection}. GitHub omits a section rather than
	 * returning it disabled, and ActualTypes reads that omission as false, so
	 * every wrapper here is written only when GraphQL says the setting is on.
	 */
	static ObjectNode branchProtection(JsonNode node) {
		ObjectNode out = F.objectNode();
		enabled(out, "enforce_admins", node.path("isAdminEnforced"));
		enabled(
				out,
				"required_linear_history",
				node.path("requiresLinearHistory")
		);
		enabled(out, "allow_force_pushes", node.path("allowsForcePushes"));
		enabled(out, "allow_deletions", node.path("allowsDeletions"));
		enabled(out, "block_creations", node.path("blocksCreations"));
		enabled(
				out,
				"required_conversation_resolution",
				node.path("requiresConversationResolution")
		);
		enabled(
				out,
				"required_signatures",
				node.path("requiresCommitSignatures")
		);
		enabled(out, "lock_branch", node.path("lockBranch"));
		enabled(
				out,
				"allow_fork_syncing",
				node.path("lockAllowsFetchAndMerge")
		);
		if (node.path("requiresStatusChecks").asBoolean()) {
			ObjectNode checks = out.putObject("required_status_checks");
			checks.put(
					"strict",
					node.path("requiresStrictStatusChecks").asBoolean()
			);
			ArrayNode list = checks.putArray("checks");
			for (JsonNode check : node.path("requiredStatusChecks")) {
				ObjectNode c = list.addObject();
				c.put("context", check.path("context").asText());
				JsonNode app = check.path("app").path("databaseId");
				if (app.isNumber()) {
					c.put("app_id", app.asInt());
				}
			}
		}
		if (node.path("requiresApprovingReviews").asBoolean()) {
			ObjectNode reviews = out.putObject("required_pull_request_reviews");
			reviews.put(
					"dismiss_stale_reviews",
					node.path("dismissesStaleReviews").asBoolean()
			);
			reviews.put(
					"require_code_owner_reviews",
					node.path("requiresCodeOwnerReviews").asBoolean()
			);
			if (node.hasNonNull("requiredApprovingReviewCount")) {
				reviews.put(
						"required_approving_review_count",
						node.get("requiredApprovingReviewCount").asInt()
				);
			}
			reviews.put(
					"require_last_push_approval",
					node.path("requireLastPushApproval").asBoolean()
			);
			if (node.path("restrictsReviewDismissals").asBoolean()) {
				reviews.set(
						"dismissal_restrictions",
						actors(node.path("reviewDismissalAllowances"))
				);
			}
			if (!node.path("bypassPullRequestAllowances")
					.path("nodes")
					.isEmpty()) {
				reviews.set(
						"bypass_pull_request_allowances",
						actors(node.path("bypassPullRequestAllowances"))
				);
			}
		}
		if (node.path("restrictsPushes").asBoolean()) {
			out.set("restrictions", actors(node.path("pushAllowances")));
		}
		return out;
	}

	/** An allowance list as REST's {@code {users, teams, apps}} triple. */
	private static ObjectNode actors(JsonNode allowances) {
		ObjectNode out = F.objectNode();
		ArrayNode users = out.putArray("users");
		ArrayNode teams = out.putArray("teams");
		ArrayNode apps = out.putArray("apps");
		for (JsonNode allowance : allowances.path("nodes")) {
			JsonNode actor = allowance.path("actor");
			switch (actor.path("__typename").asText()) {
			case "User" ->
				users.addObject().put("login", actor.path("login").asText());
			case "Team" ->
				teams.addObject().put("slug", actor.path("slug").asText());
			case "App" ->
				apps.addObject().put("slug", actor.path("slug").asText());
			default -> {
				// An actor type drifty does not compare.
			}
			}
		}
		return out;
	}

	private static void enabled(ObjectNode out, String section, JsonNode on) {
		if (on.asBoolean()) {
			out.putObject(section).put("enabled", true);
		}
	}

	/**
	 * One element of {@code rulesets.nodes} as {@code GET .../rulesets/{id}}.
	 */
	static ObjectNode ruleset(JsonNode node) {
		ObjectNode out = F.objectNode();
		out.put("id", node.path("databaseId").asLong());
		out.put("name", node.path("name").asText());
		out.put("target", wire(node.path("target").asText(null)));
		out.put("enforcement", wire(node.path("enforcement").asText(null)));
		// includeParents: false already asks for the repository's own rulesets
		// only, but source_type is what RepositoryChecker filters an
		// organization's out by — so it is read rather than assumed.
		out.put(
				"source_type",
				node.path("source").path("__typename").asText("Repository")
		);
		JsonNode conditions = node.path("conditions");
		if (conditions.isObject() && conditions.has("refName")) {
			ObjectNode c = F.objectNode();
			c.set("ref_name", conditions.get("refName"));
			out.set("conditions", c);
		}
		ArrayNode rules = out.putArray("rules");
		for (JsonNode rule : node.path("rules").path("nodes")) {
			ObjectNode r = F.objectNode();
			r.put("type", wire(rule.path("type").asText(null)));
			JsonNode parameters = rule.path("parameters");
			if (parameters.isObject()) {
				r.set("parameters", snake(parameters));
			}
			rules.add(r);
		}
		ArrayNode bypass = out.putArray("bypass_actors");
		for (JsonNode actor : node.path("bypassActors").path("nodes")) {
			bypass.add(bypassActor(actor));
		}
		return out;
	}

	private static ObjectNode bypassActor(JsonNode actor) {
		ObjectNode out = F.objectNode();
		out.put("bypass_mode", wire(actor.path("bypassMode").asText(null)));
		if (actor.path("organizationAdmin").asBoolean()) {
			out.put("actor_type", "OrganizationAdmin");
			return out;
		}
		if (actor.path("deployKey").asBoolean()) {
			out.put("actor_type", "DeployKey");
			return out;
		}
		if (actor.hasNonNull("repositoryRoleDatabaseId")) {
			out.put("actor_type", "RepositoryRole");
			out.put("actor_id", actor.get("repositoryRoleDatabaseId").asLong());
			return out;
		}
		JsonNode inner = actor.path("actor");
		out.put(
				"actor_type",
				"App".equals(inner.path("__typename").asText()) ? "Integration"
						: inner.path("__typename").asText()
		);
		if (inner.hasNonNull("databaseId")) {
			out.put("actor_id", inner.get("databaseId").asLong());
		}
		return out;
	}

	/** Field names to snake_case and enum values to their wire spelling. */
	private static JsonNode snake(JsonNode node) {
		if (node.isArray()) {
			ArrayNode out = F.arrayNode();
			node.forEach(e -> out.add(snake(e)));
			return out;
		}
		if (!node.isObject()) {
			return node.isTextual() && isEnumValue(node.asText())
					? F.textNode(wire(node.asText()))
					: node;
		}
		ObjectNode out = F.objectNode();
		node.properties().forEach(e -> {
			if (!"__typename".equals(e.getKey())) {
				out.set(snakeCase(e.getKey()), snake(e.getValue()));
			}
		});
		return out;
	}

	/**
	 * A GraphQL enum value is SCREAMING_SNAKE_CASE and nothing drifty compares
	 * as a plain string is, so the spelling is what tells them apart.
	 */
	private static boolean isEnumValue(String value) {
		return !value.isEmpty() && value.chars()
				.allMatch(
						c -> c == '_' || Character.isUpperCase(c)
								|| Character.isDigit(c)
				);
	}

	private static @Nullable String wire(@Nullable String value) {
		return value == null ? null : value.toLowerCase(Locale.ROOT);
	}

	private static String snakeCase(String name) {
		StringBuilder out = new StringBuilder(name.length() + 4);
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (Character.isUpperCase(c)) {
				out.append('_').append(Character.toLowerCase(c));
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

}
