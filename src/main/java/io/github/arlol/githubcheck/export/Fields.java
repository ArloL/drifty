package io.github.arlol.githubcheck.export;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Builds the members of an exported object, dropping everything that is already
 * at its schema default.
 * <p>
 * "Differs from the default" is decided here and nowhere else, so a section
 * exporter reads as a list of settings rather than a list of if-statements.
 * <p>
 * {@link #required} is the exception the schema forces: a field Pkl declares
 * without a default — {@code Repository.name}, {@code Webhook.url},
 * {@code CustomProperty.valueType} — has no default to differ from, and a file
 * that omits one does not evaluate.
 */
public final class Fields {

	private Fields() {
	}

	public static Optional<PklNode.Member> field(
			String name,
			String actual,
			String defaultValue
	) {
		return Objects.equals(actual, defaultValue) ? Optional.empty()
				: Optional
						.of(new PklNode.Field(name, PklNode.Scalar.of(actual)));
	}

	public static Optional<PklNode.Member> field(
			String name,
			boolean actual,
			boolean defaultValue
	) {
		return actual == defaultValue ? Optional.empty()
				: Optional
						.of(new PklNode.Field(name, PklNode.Scalar.of(actual)));
	}

	public static Optional<PklNode.Member> field(
			String name,
			long actual,
			long defaultValue
	) {
		return actual == defaultValue ? Optional.empty()
				: Optional
						.of(new PklNode.Field(name, PklNode.Scalar.of(actual)));
	}

	public static Optional<PklNode.Member> field(
			String name,
			Integer actual,
			Integer defaultValue
	) {
		if (Objects.equals(actual, defaultValue)) {
			return Optional.empty();
		}
		return Optional.of(
				new PklNode.Field(
						name,
						actual == null ? PklNode.Scalar.nullValue()
								: PklNode.Scalar.of(actual.longValue())
				)
		);
	}

	/**
	 * An enum against the string the schema defaults it to. Every enum
	 * generated from a Pkl union spells its constants the way the union does,
	 * so comparing the two as text is comparing like with like.
	 */
	public static Optional<PklNode.Member> field(
			String name,
			Enum<?> actual,
			String defaultValue
	) {
		return field(
				name,
				actual == null ? null : actual.toString(),
				defaultValue
		);
	}

	public static Optional<PklNode.Member> required(String name, String value) {
		return Optional.of(new PklNode.Field(name, PklNode.Scalar.of(value)));
	}

	public static Optional<PklNode.Member> required(String name, long value) {
		return Optional.of(new PklNode.Field(name, PklNode.Scalar.of(value)));
	}

	/**
	 * A collection of strings, compared as a set: GitHub's ordering is not
	 * drifty's, and the checker compares these unordered too. Emitted sorted so
	 * two exports of an unchanged account are identical files.
	 */
	public static Optional<PklNode.Member> strings(
			String name,
			Collection<String> actual,
			Collection<String> defaultValue
	) {
		if (new HashSet<>(actual).equals(new HashSet<>(defaultValue))) {
			return Optional.empty();
		}
		List<PklNode> elements = actual.stream()
				.sorted()
				.map(value -> (PklNode) PklNode.Scalar.of(value))
				.toList();
		return Optional
				.of(new PklNode.Field(name, new PklNode.Listing(elements)));
	}

	/** A listing of objects, omitted when empty — empty is its default. */
	public static Optional<PklNode.Member> objects(
			String name,
			List<PklNode> elements
	) {
		return elements.isEmpty() ? Optional.empty()
				: Optional.of(
						new PklNode.Field(name, new PklNode.Listing(elements))
				);
	}

	/** A keyed mapping, omitted when empty. */
	public static Optional<PklNode.Member> mapping(
			String name,
			List<PklNode.Member> entries
	) {
		return entries.isEmpty() ? Optional.empty()
				: Optional.of(
						new PklNode.Field(name, new PklNode.Mapping(entries))
				);
	}

	/** A nested object, omitted when nothing inside it drifted. */
	public static Optional<PklNode.Member> nested(
			String name,
			List<PklNode.Member> members
	) {
		return members.isEmpty() ? Optional.empty()
				: Optional
						.of(new PklNode.Field(name, new PklNode.Obj(members)));
	}

	public static PklNode.Member note(String text) {
		return new PklNode.Note(text);
	}

	@SafeVarargs
	public static List<PklNode.Member> members(
			Optional<PklNode.Member>... candidates
	) {
		return Stream.of(candidates).flatMap(Optional::stream).toList();
	}

	/** The same, for members built in a loop beside fixed ones. */
	public static List<PklNode.Member> concat(
			List<PklNode.Member> first,
			List<PklNode.Member> second
	) {
		var all = new ArrayList<>(first);
		all.addAll(second);
		return List.copyOf(all);
	}

}
