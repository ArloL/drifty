package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.actual.ActualBranchProtection;
import io.github.arlol.githubcheck.actual.StatusCheck;

class BranchProtectionExporterTest {

	private static final SchemaDefaults DEFAULTS = SchemaDefaults
			.of(Path.of("config/drifty.pkl").toAbsolutePath().toString());

	/** GitHub's report for a branch with no rules configured at all. */
	private static ActualBranchProtection defaultProtection() {
		return new ActualBranchProtection(
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				Set.of(),
				Optional.empty(),
				Optional.empty()
		);
	}

	private static String render(ActualBranchProtection actual) {
		var field = (PklNode.Field) BranchProtectionExporter
				.entry("main", actual, DEFAULTS.branchProtection());
		return PklWriter.write(field.value());
	}

	@Test
	void everyFieldAtItsDefaultExportsNothing() {
		assertThat(render(defaultProtection())).isEqualTo("");
	}

	@Test
	void keyIsThePattern() {
		var field = (PklNode.Field) BranchProtectionExporter.entry(
				"release/*",
				defaultProtection(),
				DEFAULTS.branchProtection()
		);
		assertThat(field.name()).isEqualTo("release/*");
	}

	@Test
	void aDirectBooleanDifferingFromDefaultIsEmitted() {
		ActualBranchProtection actual = new ActualBranchProtection(
				true,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				Set.of(),
				Optional.empty(),
				Optional.empty()
		);
		assertThat(render(actual)).isEqualTo("""
				enforceAdmins = true
				""");
	}

	@Test
	void requiredStatusChecksAreEmittedSortedAndOmitANullAppId() {
		ActualBranchProtection actual = new ActualBranchProtection(
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				Set.of(
						new StatusCheck("ci/z", null),
						new StatusCheck("ci/a", 7)
				),
				Optional.empty(),
				Optional.empty()
		);
		assertThat(render(actual)).isEqualTo("""
				requiredStatusChecks {
				  new {
				    context = "ci/a"
				    appId = 7
				  }
				  new {
				    context = "ci/z"
				  }
				}
				""");
	}

	/**
	 * {@code pullRequestReviews} absent must not blank out
	 * {@code restrictions}: a broken implementation sharing one guard between
	 * both sections would drop these fields too, and one that calls
	 * {@code Optional.orElseThrow()} instead of substituting the "not
	 * configured" default would throw rather than reach this assertion at all.
	 */
	@Test
	void absentPullRequestReviewsDoesNotSuppressRestrictions() {
		ActualBranchProtection actual = new ActualBranchProtection(
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				Set.of(),
				Optional.empty(),
				Optional.of(
						new ActualBranchProtection.Restrictions(
								Set.of("alice"),
								Set.of(),
								Set.of()
						)
				)
		);
		assertThat(render(actual)).isEqualTo("""
				users {
				  "alice"
				}
				""");
	}

	/**
	 * The mirror of the above: an absent restrictions must not blank reviews.
	 */
	@Test
	void absentRestrictionsDoesNotSuppressPullRequestReviews() {
		ActualBranchProtection actual = new ActualBranchProtection(
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				Set.of(),
				Optional.of(
						new ActualBranchProtection.PullRequestReviews(
								true,
								false,
								null,
								null,
								ActualBranchProtection.Actors.NONE,
								ActualBranchProtection.Actors.NONE
						)
				),
				Optional.empty()
		);
		assertThat(render(actual)).isEqualTo("""
				dismissStaleReviews = true
				""");
	}

	/**
	 * Every sub-field of {@code pullRequestReviews} at once, so a
	 * field/accessor mismatch among the ten cannot hide behind another field
	 * happening to cover for it.
	 */
	@Test
	void everyPullRequestReviewsSubFieldIsWrittenWhenDrifted() {
		ActualBranchProtection actual = new ActualBranchProtection(
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				Set.of(),
				Optional.of(
						new ActualBranchProtection.PullRequestReviews(
								true,
								true,
								2,
								true,
								new ActualBranchProtection.Actors(
										Set.of("dismiss-user"),
										Set.of("dismiss-team"),
										Set.of("dismiss-app")
								),
								new ActualBranchProtection.Actors(
										Set.of("bypass-user"),
										Set.of("bypass-team"),
										Set.of("bypass-app")
								)
						)
				),
				Optional.empty()
		);
		assertThat(render(actual)).isEqualTo("""
				requiredApprovingReviewCount = 2
				dismissStaleReviews = true
				requireCodeOwnerReviews = true
				requireLastPushApproval = true
				dismissalUsers {
				  "dismiss-user"
				}
				dismissalTeams {
				  "dismiss-team"
				}
				dismissalApps {
				  "dismiss-app"
				}
				bypassPullRequestUsers {
				  "bypass-user"
				}
				bypassPullRequestTeams {
				  "bypass-team"
				}
				bypassPullRequestApps {
				  "bypass-app"
				}
				""");
	}

	@Test
	void everyRestrictionsSubFieldIsWrittenWhenDrifted() {
		ActualBranchProtection actual = new ActualBranchProtection(
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				Set.of(),
				Optional.empty(),
				Optional.of(
						new ActualBranchProtection.Restrictions(
								Set.of("alice"),
								Set.of("platform"),
								Set.of("ci-app")
						)
				)
		);
		assertThat(render(actual)).isEqualTo("""
				users {
				  "alice"
				}
				teams {
				  "platform"
				}
				apps {
				  "ci-app"
				}
				""");
	}

	@Test
	void aRestrictionsWithEverySetEmptyExportsNothing() {
		// Distinguishes a present-but-empty Restrictions from an absent one:
		// both must render identically, since the schema has no way to tell
		// them apart.
		ActualBranchProtection actual = new ActualBranchProtection(
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				Set.of(),
				Optional.empty(),
				Optional.of(
						new ActualBranchProtection.Restrictions(
								Set.of(),
								Set.of(),
								Set.of()
						)
				)
		);
		assertThat(render(actual)).isEqualTo("");
	}

}
