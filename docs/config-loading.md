# Loading the config

- **The schema is answered from inside the binary, not fetched from GitHub.**
  Every exported config `amends` `BundledSchema.MAIN_SCHEMA_URI`, and Pkl's
  cache under `~/.pkl/cache` holds packages only — a plain `https` module is
  fetched again on every evaluation. Measured on a 477-line config: 0.21s
  evaluated against the URL, 0.07s against a local copy, which was the whole of
  drifty's config-loading phase. `BundledSchema.moduleKeyFactory` claims that
  one URI and Pkl's own `https` factory answers everything else, so a config
  pinned to a tag still resolves over the network. It has to be *prepended* to
  the preconfigured factories, not added: the first factory that claims a URI
  is the one that answers it. Both `PklConfigLoader` and `SchemaDefaults` build
  their evaluator from `BundledSchema.evaluatorBuilder`.
- **The bundled schema is `config/drifty.pkl`, copied by the build.** The
  `pkl-add-resource` execution in `pom.xml` puts it beside `BundledSchema`, and
  its glob is in both `reachability-metadata.json` and
  `ReachabilityMetadata.MAIN_RESOURCE_PREFIXES` or the native image cannot read
  it. Answering with the shipped copy is the point: `Drifty.java` is generated
  from that same file at build time, so a binary resolving main's newer schema
  could be handed a field its own mapper has never heard of.

