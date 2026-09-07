package io.github.arlol.githubcheck.export;

import java.util.List;

/**
 * A value in an exported Pkl file, one step above text.
 * <p>
 * Exporters build this tree and {@link PklWriter} is the only thing that turns
 * it into Pkl. Keeping syntax in one place is what lets a section exporter be a
 * list of field comparisons rather than a string builder, and it is why
 * indentation, escaping and the listing-versus-mapping distinction are each
 * decided once.
 */
public sealed interface PklNode {

	/** A member of an {@link Obj} or {@link Mapping}: a field or a comment. */
	sealed interface Member {
	}

	record Field(
			String name,
			PklNode value
	) implements Member {
	}

	/**
	 * A {@code //} comment. It carries what the export could not say — an
	 * unreadable group, an entity drifty does not manage, a secret value — so a
	 * reader does not mistake an absent section for an empty one.
	 */
	record Note(
			String text
	) implements Member, PklNode {
	}

	/** A rendered literal: {@code "text"}, {@code 3}, {@code true}. */
	record Scalar(
			String value,
			boolean quoted
	) implements PklNode {

		public static Scalar of(String value) {
			return new Scalar(value, true);
		}

		public static Scalar of(boolean value) {
			return new Scalar(Boolean.toString(value), false);
		}

		public static Scalar of(long value) {
			return new Scalar(Long.toString(value), false);
		}

		/**
		 * An enum constant, as the string the schema spells it with. Every enum
		 * drifty generates from a Pkl union carries the union's own spelling in
		 * {@code toString}.
		 */
		public static Scalar of(Enum<?> value) {
			return of(value.toString());
		}

		/**
		 * A Pkl {@code null} literal.
		 */
		public static Scalar nullValue() {
			return new Scalar("null", false);
		}

	}

	record Obj(
			List<Member> members
	) implements PklNode {

		public Obj {
			members = List.copyOf(members);
		}

	}

	/** Keyed entries: {@code ["key"] { … }}. */
	record Mapping(
			List<Member> entries
	) implements PklNode {

		public Mapping {
			entries = List.copyOf(entries);
		}

	}

	/**
	 * Positional entries: a bare scalar, or {@code new { … }} for an object.
	 * <p>
	 * {@code replace} decides how the enclosing field renders: {@code false}
	 * writes {@code name { … }}, an <em>amendment</em> of whatever the schema
	 * default already holds; {@code true} writes {@code name = new Listing { …
	 * }}, which replaces it outright. Every scalar listing renders as a
	 * replacement — see {@code Fields.strings} — because a scalar element
	 * typechecks against {@code Listing<String>} whether or not the property
	 * carries an explicit type argument. An object listing cannot use the same
	 * rendering without one: {@code new Listing { new { … } }} gives each
	 * element the untyped {@code Dynamic} class, which every typed element
	 * class in the schema then refuses. {@code Fields.objects} keeps amendment,
	 * which is exactly equivalent to a replacement as long as the schema
	 * default is the empty listing every object-typed field in
	 * {@code config/drifty.pkl} declares today — the same fact
	 * {@code Webhook.events} broke for a scalar listing before this distinction
	 * existed.
	 */
	record Listing(
			List<PklNode> elements,
			boolean replace
	) implements PklNode {

		public Listing {
			elements = List.copyOf(elements);
		}

	}

}
