package io.github.arlol.githubcheck.drift;

import java.util.List;

import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * A single-toggle group whose feature GitHub does not offer this repository,
 * standing in for the group that would compare it.
 * <p>
 * Comparing the toggle as usual reported {@code want=true got=false} on every
 * private repository of a Free account that kept the schema's defaults, and
 * offered a {@code --fix} that GitHub then refused or ignored (issue #202). A
 * config that wants the feature off already matches; one that wants it on is
 * reported with the reason and never written, since no request can turn it on.
 */
public final class UnavailableDriftGroup extends DriftGroup<Drifty.GroupName> {

	private final Drifty.GroupName name;
	private final boolean desired;
	private final String reason;

	public UnavailableDriftGroup(
			Drifty.GroupName name,
			boolean desired,
			String reason
	) {
		this.name = name;
		this.desired = desired;
		this.reason = reason;
	}

	@Override
	public Drifty.GroupName name() {
		return name;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		if (!desired) {
			return List.of();
		}
		return List.of(
				DriftFix.reported(
						new DriftItem.Unavailable("enabled", true, reason),
						reason
				)
		);
	}

}
