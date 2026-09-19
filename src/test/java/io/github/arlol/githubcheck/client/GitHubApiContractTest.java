package io.github.arlol.githubcheck.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.DisabledInNativeImage;

import io.github.arlol.githubcheck.client.ApiContract.SchemaNode;
import io.github.arlol.githubcheck.client.ContractComparison.Finding;
import io.github.arlol.githubcheck.client.ContractComparison.Options;
import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.AnnotationParameterValueList;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * Every wire record against the endpoint it names in GitHub's own spec.
 * <p>
 * The client mapper sets {@code FAIL_ON_UNKNOWN_PROPERTIES} to false, so a
 * component whose name does not match the wire deserializes to null: no
 * exception, no failed request, a wrong answer. The WireMock fixtures cannot
 * catch that — a stub written to match the record agrees with the misspelling —
 * which is what this test is for.
 * <p>
 * Refresh the contract with {@code python3 download-schemas.py --contract}.
 * <p>
 * Not run in the native test image. ClassGraph cannot scan there — it reaches
 * for {@code Module.getLayer} through a proxy the image has no metadata for —
 * and there would be nothing for it to prove: this compares source-level
 * records against a committed file, where the native test image exists to
 * verify that the shipped binary's reflective paths work. Registering
 * ClassGraph's machinery to run a check about source would cost image size and
 * show nothing the JVM run has not already shown.
 */
@DisabledInNativeImage
class GitHubApiContractTest {

	private static final String PACKAGE = "io.github.arlol.githubcheck.client";

	private static final ApiContract CONTRACT = ApiContract.bundled();

	/**
	 * The two {@code *Response} types that answer to no OpenAPI endpoint.
	 * {@code CachedHttpResponse} wraps a kept body and is not wire JSON at all;
	 * {@code GraphQlRepositoryResponse} answers to GitHub's GraphQL schema.
	 * <p>
	 * The GraphQL rewrite is still pinned, indirectly: {@code GraphQlShape}
	 * writes into {@code RulesetDetailsResponse},
	 * {@code BranchProtectionResponse} and {@code CollaboratorResponse}, each
	 * of which is checked against its REST endpoint below.
	 */
	private static final Set<String> NOT_OPENAPI = Set
			.of("CachedHttpResponse", "GraphQlRepositoryResponse");

	/**
	 * Names that need no {@code unmanaged} declaration when they sit at the
	 * root of a response object: navigation, identity and counters, never
	 * settings.
	 * <p>
	 * <b>Root-level only.</b> A webhook's {@code config.url} is the payload URL
	 * — a managed setting at depth 1 — so a blanket {@code *_url} rule would
	 * hide the one URL that matters.
	 */
	private static final Set<String> ROOT_METADATA = Set.of(
			"id",
			"node_id",
			"url",
			"created_at",
			"updated_at",
			"pushed_at",
			"archived_at",
			"starred_at",
			"gravatar_id",
			"site_admin",
			"user_view_type",
			"score",
			"type",
			"plan",
			"is_verified",
			"disk_usage",
			"collaborators",
			"followers",
			"following",
			"public_repos",
			"public_gists",
			"private_gists",
			"owned_private_repos",
			"total_private_repos",
			"forks",
			"forks_count",
			"watchers",
			"watchers_count",
			"stargazers_count",
			"subscribers_count",
			"network_count",
			"open_issues",
			"open_issues_count",
			"size",
			"language",
			"clone_url",
			"git_url",
			"ssh_url",
			"svn_url",
			"mirror_url",
			"master_branch",
			"temp_clone_token",
			"organization_id",
			"enterprise_id",
			"members_count",
			"repos_count",
			"ldap_dn"
	);

	/**
	 * Names that are navigation wherever they appear, not only at the root.
	 * <p>
	 * A wire record reached as {@code owner}, {@code parent} or
	 * {@code repository} is a reference to another resource, and every
	 * {@code *_url} hanging off it is a link GitHub supplies for traversal. A
	 * <em>bare</em> {@code url} is deliberately not here: a webhook's
	 * {@code config.url} is the payload URL, a managed setting at depth 1, and
	 * it is the reason this distinction exists at all.
	 */
	private static boolean isNavigation(String name) {
		return name.endsWith("_url") || "node_id".equals(name)
				|| "gravatar_id".equals(name) || "starred_at".equals(name)
				|| "user_view_type".equals(name) || "site_admin".equals(name);
	}

	private static final Predicate<String> IS_ROOT_METADATA = name -> ROOT_METADATA
			.contains(name);

	/** One record, one endpoint, one direction. */
	private record Binding(
			Class<?> type,
			String endpoint,
			String direction,
			List<String> unmanaged,
			List<String> undocumented
	) {

		String name() {
			return type.getSimpleName() + " → " + endpoint + " (" + direction
					+ ")";
		}

	}

	private static List<Binding> bindings() {
		List<Binding> bindings = new ArrayList<>();
		try (ScanResult scan = new ClassGraph().enableClassInfo()
				.enableAnnotationInfo()
				.acceptPackages(PACKAGE)
				.scan()) {
			for (ClassInfo info : scan
					.getClassesWithAnnotation(GitHubEndpoint.class.getName())) {
				AnnotationInfo annotation = info
						.getAnnotationInfo(GitHubEndpoint.class.getName());
				AnnotationParameterValueList values = annotation
						.getParameterValues(true);
				List<String> unmanaged = strings(values, "unmanaged");
				List<String> undocumented = strings(values, "undocumented");
				for (String direction : List.of("request", "response")) {
					for (String endpoint : strings(values, direction)) {
						bindings.add(
								new Binding(
										info.loadClass(),
										endpoint,
										direction,
										unmanaged,
										undocumented
								)
						);
					}
				}
			}
		}
		bindings.sort((a, b) -> a.name().compareTo(b.name()));
		return bindings;
	}

	private static List<String> strings(
			AnnotationParameterValueList values,
			String name
	) {
		Object value = values.getValue(name);
		if (value == null) {
			return List.of();
		}
		return Stream.of((Object[]) value).map(String::valueOf).toList();
	}

	// ─── Guards
	// ──────────────────────────────────────────────────────────────

	@Test
	void everyWireRecordNamesItsEndpoint() {
		Set<String> bound = new LinkedHashSet<>();
		bindings()
				.forEach(binding -> bound.add(binding.type().getSimpleName()));

		List<String> unbound = new ArrayList<>();
		try (ScanResult scan = new ClassGraph().enableClassInfo()
				.acceptPackages(PACKAGE)
				.scan()) {
			for (ClassInfo info : scan.getAllRecords()) {
				String name = info.getSimpleName();
				boolean wire = name.endsWith("Request")
						|| name.endsWith("Response");
				if (wire && !bound.contains(name) && !NOT_OPENAPI.contains(name)
						&& !info.isInnerClass()) {
					unbound.add(name);
				}
			}
		}

		assertThat(unbound)
				.as(
						"""
								Every client record ending in Request or Response must name the \
								endpoint it belongs to with @GitHubEndpoint, or be listed in \
								NOT_OPENAPI with a reason. Without the annotation nothing \
								compares it against GitHub's spec, and a miscased component \
								deserializes to null in silence."""
				)
				.isEmpty();
	}

	@Test
	void everyNamedEndpointIsInTheContractFile() {
		List<String> missing = bindings().stream()
				.map(Binding::endpoint)
				.distinct()
				.filter(endpoint -> !CONTRACT.has(endpoint))
				.sorted()
				.toList();

		assertThat(missing).as("""
				An endpoint an annotation names is absent from the contract \
				file. Either the path is not spelled as OpenAPI spells it, or \
				the file needs refreshing:
				  python3 download-schemas.py --contract""").isEmpty();
	}

	@Test
	void theContractFileSaysWhatItWasCutFrom() {
		assertThat(CONTRACT.apiVersion()).isNotEmpty();
		assertThat(CONTRACT.specEtag()).isNotEmpty().isNotEqualTo("unknown");
		assertThat(CONTRACT.endpoints()).hasSizeGreaterThan(50);
	}

	// ─── The gate
	// ────────────────────────────────────────────────────────────

	@TestFactory
	Stream<DynamicTest> everyRecordMatchesTheEndpointItNames() {
		return bindings().stream()
				.map(
						binding -> DynamicTest.dynamicTest(
								binding.name(),
								() -> check(binding)
						)
				);
	}

	private static ContractComparison check(Binding binding) {
		SchemaNode schema = CONTRACT
				.schema(binding.endpoint(), binding.direction());
		assertThat(schema)
				.as(
						"%s has no %s schema in the contract file",
						binding.endpoint(),
						binding.direction()
				)
				.isNotNull();

		ContractComparison comparison = new ContractComparison(
				binding.endpoint(),
				binding.direction(),
				new Options(
						true,
						Set.copyOf(binding.unmanaged()),
						Set.copyOf(binding.undocumented()),
						IS_ROOT_METADATA,
						GitHubApiContractTest::isNavigation
				)
		);
		List<Finding> findings = comparison.compare(schema, binding.type());

		assertThat(findings)
				.as(
						"""
								%s disagrees with GitHub's spec. Each finding is one of three \
								things: a bug in the record (fix the record), a field GitHub's \
								spec lags on (@GitHubEndpoint undocumented, with the reason), \
								or a field drifty does not manage (@GitHubEndpoint unmanaged, \
								with the reason). Never widen ROOT_METADATA to silence one.

								%s""",
						binding.type().getSimpleName(),
						findings.stream()
								.map(Finding::toString)
								.reduce("", (a, b) -> a + "\n  " + b)
				)
				.isEmpty();
		return comparison;
	}

	/**
	 * An exclusion no binding of its record had a use for.
	 * <p>
	 * Checked here rather than inside a comparison because one record serves up
	 * to four endpoints off one pair of lists: {@code rules[merge_queue]}
	 * answers a finding on the organization ruleset endpoints and has nothing
	 * to answer on the repository ones, and that is not staleness. Only when
	 * every binding had no use for an entry is it dead, and a dead exclusion is
	 * how a check quietly stops covering what it claims to.
	 */
	@Test
	void noExclusionIsDead() {
		Map<Class<?>, Set<String>> declared = new LinkedHashMap<>();
		Map<Class<?>, Set<String>> used = new LinkedHashMap<>();

		for (Binding binding : bindings()) {
			SchemaNode schema = CONTRACT
					.schema(binding.endpoint(), binding.direction());
			if (schema == null) {
				continue;
			}
			ContractComparison comparison = new ContractComparison(
					binding.endpoint(),
					binding.direction(),
					new Options(
							true,
							Set.copyOf(binding.unmanaged()),
							Set.copyOf(binding.undocumented()),
							IS_ROOT_METADATA,
							GitHubApiContractTest::isNavigation
					)
			);
			comparison.compare(schema, binding.type());
			declared.computeIfAbsent(
					binding.type(),
					key -> new LinkedHashSet<>()
			).addAll(comparison.unusedExclusions());
			used.computeIfAbsent(binding.type(), key -> new LinkedHashSet<>())
					.addAll(comparison.consumedExclusions());
		}

		Map<String, Set<String>> dead = new LinkedHashMap<>();
		declared.forEach((type, unused) -> {
			Set<String> remaining = new LinkedHashSet<>(unused);
			remaining.removeAll(used.getOrDefault(type, Set.of()));
			if (!remaining.isEmpty()) {
				dead.put(type.getSimpleName(), remaining);
			}
		});

		assertThat(dead).as(
				"These @GitHubEndpoint exclusions answer no finding on any"
						+ " endpoint their record names. Delete them, or"
						+ " correct the path."
		).isEmpty();
	}

}
