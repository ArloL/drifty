package io.github.arlol.githubcheck.actual;

import org.jspecify.annotations.Nullable;

/**
 * The features GitHub does not offer one repository, each as the reason it does
 * not; {@code null} means offered, or that nothing said otherwise.
 * <p>
 * Each is learned from a different place, which is why none of them is a
 * boolean on the record its setting is read from: the wiki from the owning
 * account's plan, the {@code security_and_analysis} toggles from the
 * repository's visibility, owner and that section's presence, code scanning
 * from what its own endpoint answers.
 *
 * @param wiki                why {@code has_wiki} cannot be turned on
 * @param securityAndAnalysis why none of the eight toggles GitHub carries under
 *                            {@code security_and_analysis} can be turned on
 * @param codeScanning        why code scanning default setup cannot be turned
 *                            on — GitHub's own words
 */
public record UnavailableFeatures(
		@Nullable String wiki,
		@Nullable String securityAndAnalysis,
		@Nullable String codeScanning
) {

	public static final UnavailableFeatures NONE = new UnavailableFeatures(
			null,
			null,
			null
	);

}
