package io.github.arlol.githubcheck.drift;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Which drift groups drifty manages for one repository or organization.
 * <p>
 * Consulted three times per entity, and all three are required: once to decide
 * which groups to build, once in the checker's {@code fetchState} to decide
 * which requests to send, and once in {@code GitHubCheck.collectMissingSecrets}
 * to decide which secrets {@code --fix} needs a value for. Skipping only the
 * comparison would still send the request, and an org — or a repository in one
 * — that someone else administers is exactly where those requests return 403;
 * skipping only those two still aborted {@code --fix} over the secret values
 * that repository's config declares but drifty never pushes.
 * <p>
 * Generic over the group-name enum, with the {@code Class<N>} token carried
 * alongside it purely because Java erases {@code N} at runtime — {@code
 * EnumSet.allOf} needs the class to enumerate an enum's constants.
 */
public final class ManagedGroups<N extends Enum<N>> {

	private final Class<N> type;
	private final Set<N> managed;

	private ManagedGroups(Class<N> type, Set<N> managed) {
		this.type = type;
		this.managed = managed;
	}

	public static ManagedGroups<Drifty.GroupName> of(Drifty.Managed managed) {
		return of(Drifty.GroupName.class, managed.mode, managed.groups);
	}

	public static ManagedGroups<Drifty.OrgGroupName> of(
			Drifty.OrgManaged managed
	) {
		return of(Drifty.OrgGroupName.class, managed.mode, managed.groups);
	}

	/**
	 * Every group of one scope, which is what an entity that declares nothing
	 * gets. The class token is the only way to enumerate an enum's constants
	 * generically — {@code EnumSet.allOf} needs it.
	 */
	public static <N extends Enum<N>> ManagedGroups<N> all(Class<N> type) {
		return new ManagedGroups<>(type, EnumSet.allOf(type));
	}

	private static <N extends Enum<N>> ManagedGroups<N> of(
			Class<N> type,
			Drifty.ManageMode mode,
			List<N> groups
	) {
		Set<N> named = groups.isEmpty() ? EnumSet.noneOf(type)
				: EnumSet.copyOf(groups);
		return new ManagedGroups<>(type, switch (mode) {
		case ONLY -> named;
		case ALL_EXCEPT -> {
			var rest = EnumSet.allOf(type);
			rest.removeAll(named);
			yield rest;
		}
		});
	}

	/**
	 * The groups both this and {@code groups} manage.
	 * <p>
	 * What narrows the set a checker's {@code fetchState} reads without
	 * touching the one the report and the drift groups read. A repository the
	 * config wants archived is compared on {@code archived} alone, so every
	 * other group's requests were sent and thrown away; the entry it produces
	 * still has to name the groups the <em>config</em> leaves unmanaged, which
	 * is a different set and comes from the untouched instance.
	 */
	public ManagedGroups<N> and(Set<N> groups) {
		var both = EnumSet.noneOf(type);
		both.addAll(managed);
		both.retainAll(groups);
		return new ManagedGroups<>(type, both);
	}

	public boolean manages(N group) {
		return managed.contains(group);
	}

	/**
	 * Whether any of these groups is managed, for a read that serves several.
	 */
	public boolean managesAny(Collection<N> groups) {
		return groups.stream().anyMatch(this::manages);
	}

	/** The groups this entity leaves alone, for the report. */
	public List<N> unmanaged() {
		var rest = EnumSet.allOf(type);
		rest.removeAll(managed);
		return List.copyOf(rest);
	}

}
