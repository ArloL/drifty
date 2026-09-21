# Releasing

- **A release asset's name is the installer's only input.** mise scores assets
  by the os and arch tokens in the name, prefers an archive over a bare file,
  and then runs whatever the archive holds under the name it already has. So
  `main.yaml`'s release job builds one archive per os and arch holding a single
  executable called `drifty`. Naming an asset for the os alone is what made
  `mise use github:ArloL/drifty` unpack a zip of jars with no `drifty` in it,
  and would hand an arm64 machine an x64 build.

