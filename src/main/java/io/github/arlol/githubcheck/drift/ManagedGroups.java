package io.github.arlol.githubcheck.drift;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Which drift groups drifty manages for one repository.
 * <p>
 * Consulted twice per repository, and both are load-bearing: once to decide
 * which groups to build, and once in {@code OrgChecker.fetchState} to decide
 * which requests to send. Skipping only the comparison would still send the
 * request, and a repository in an org someone else administers is exactly where
 * those requests return 403.
 */
public final class ManagedGroups {

	private final Set<Drifty.GroupName> managed;

	private ManagedGroups(Set<Drifty.GroupName> managed) {
		this.managed = managed;
	}

	public static ManagedGroups of(Drifty.Managed managed) {
		Set<Drifty.GroupName> named = managed.groups.isEmpty()
				? EnumSet.noneOf(Drifty.GroupName.class)
				: EnumSet.copyOf(managed.groups);
		return new ManagedGroups(switch (managed.mode) {
		case ONLY -> named;
		case ALL_EXCEPT -> {
			var rest = EnumSet.allOf(Drifty.GroupName.class);
			rest.removeAll(named);
			yield rest;
		}
		});
	}

	/** Every group, which is what a repository that declares nothing gets. */
	public static ManagedGroups all() {
		return new ManagedGroups(EnumSet.allOf(Drifty.GroupName.class));
	}

	public boolean manages(Drifty.GroupName group) {
		return managed.contains(group);
	}

	/** The groups this repository leaves alone, for the report. */
	public List<Drifty.GroupName> unmanaged() {
		var rest = EnumSet.allOf(Drifty.GroupName.class);
		rest.removeAll(managed);
		return List.copyOf(rest);
	}

}
