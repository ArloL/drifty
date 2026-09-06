package io.github.arlol.githubcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.arlol.githubcheck.pkl.Drifty;

class PklConfigLoaderTest {

	@Test
	void loadsExampleConfig() throws IOException {
		DriftyConfig config = PklConfigLoader
				.load(Path.of("config/example.pkl").toAbsolutePath());

		assertThat(config.organizations()).containsOnlyKeys("example-org");
		assertThat(config.users()).containsOnlyKeys("example-user");
		assertThat(config.allRepositories()).extracting(r -> r.name)
				.contains("example-service", "personal-site");
	}

	@Test
	void managedDefaultsToEverything() throws IOException {
		DriftyConfig config = PklConfigLoader
				.load(Path.of("config/example.pkl").toAbsolutePath());

		assertThat(config.allRepositories())
				.filteredOn(
						repo -> !"shared-with-another-team".equals(repo.name)
				)
				.allSatisfy(repo -> {
					assertThat(repo.managed.mode)
							.isEqualTo(Drifty.ManageMode.ALL_EXCEPT);
					assertThat(repo.managed.groups).isEmpty();
				});
	}

	@Test
	void load_readsOrganizationsAndUsers(@TempDir Path dir) throws Exception {
		Path config = dir.resolve("drifty.pkl");
		Files.writeString(
				config,
				"""
						amends "%s"

						organizations {
						  ["my-org"] {
						    repositories {
						      new { name = "repo-a" }
						    }
						  }
						}

						users {
						  ["ArloL"] {
						    repositories {
						      new { name = "drifty" }
						    }
						  }
						}
						""".formatted(
						Path.of("config/drifty.pkl").toAbsolutePath().toUri()
				)
		);

		DriftyConfig loaded = PklConfigLoader.load(config);

		assertThat(loaded.organizations()).containsOnlyKeys("my-org");
		assertThat(loaded.organizations().get("my-org").repositories)
				.extracting(r -> r.name)
				.containsExactly("repo-a");
		assertThat(loaded.users()).containsOnlyKeys("ArloL");
		assertThat(loaded.users().get("ArloL").repositories)
				.extracting(r -> r.name)
				.containsExactly("drifty");
	}

	/**
	 * Accounts and their repositories come back in declaration order. The maps
	 * used to be {@code Map.copyOf}, whose order is salted per JVM run, so the
	 * progress lines and the missing-secrets list came out differently on every
	 * run of one config. Four accounts each, in non-alphabetical order, so a
	 * salted or sorted map would not pass by luck.
	 */
	@Test
	void load_keepsAccountsInDeclarationOrder(@TempDir Path dir)
			throws Exception {
		Path config = dir.resolve("drifty.pkl");
		Files.writeString(
				config,
				"""
						amends "%s"

						organizations {
						  ["zeta"] { repositories { new { name = "z1" } } }
						  ["alpha"] { repositories { new { name = "a1" } } }
						  ["mike"] { repositories { new { name = "m1" } } }
						  ["bravo"] { repositories { new { name = "b1" } } }
						}

						users {
						  ["yankee"] { repositories { new { name = "y1" } } }
						  ["charlie"] { repositories { new { name = "c1" } } }
						  ["xray"] { repositories { new { name = "x1" } } }
						  ["delta"] { repositories { new { name = "d1" } } }
						}
						""".formatted(
						Path.of("config/drifty.pkl").toAbsolutePath().toUri()
				)
		);

		DriftyConfig loaded = PklConfigLoader.load(config);

		assertThat(loaded.organizations().keySet())
				.containsExactly("zeta", "alpha", "mike", "bravo");
		assertThat(loaded.users().keySet())
				.containsExactly("yankee", "charlie", "xray", "delta");
		// Organizations first, then users, each in declaration order.
		assertThat(loaded.allRepositories()).extracting(
				r -> r.name
		).containsExactly("z1", "a1", "m1", "b1", "y1", "c1", "x1", "d1");
	}

	@Test
	void unknownGroupName_failsToEvaluate(@TempDir Path tempDir)
			throws IOException {
		// A file: URI, not a plain path: on Windows an absolute path is
		// "D:\\a\\drifty\\config\\drifty.pkl", and Pkl rejects "\\a" as an
		// invalid escape before it ever reaches the group name under test.
		String schema = Path.of("config/drifty.pkl")
				.toAbsolutePath()
				.toUri()
				.toString();
		Path config = tempDir.resolve("drifty.pkl");
		Files.writeString(config, """
				amends "%s"

				users {
				  ["owner"] {
				    repositories {
				      new {
				        name = "repo"
				        managed { mode = "only"; groups { "not_a_group" } }
				      }
				    }
				  }
				}
				""".formatted(schema));

		assertThatThrownBy(() -> PklConfigLoader.load(config))
				.hasMessageContaining("not_a_group");
	}

	/**
	 * A push ruleset has no refs to condition on, so the schema refuses ref
	 * patterns on one; a repository target is an organization ruleset's, so a
	 * repository's mapping refuses it. Both fail at config eval, before any
	 * request GitHub would 422.
	 */
	@Test
	void pushRulesetWithRefPatterns_failsToEvaluate(@TempDir Path tempDir)
			throws IOException {
		Path config = write(
				tempDir,
				"""
						users {
						  ["owner"] {
						    repositories {
						      new {
						        name = "repo"
						        rulesets {
						          ["no-binaries"] { target = "push"; includePatterns { "~ALL" } }
						        }
						      }
						    }
						  }
						}
						"""
		);

		assertThatThrownBy(() -> PklConfigLoader.load(config))
				.hasMessageContaining("includePatterns");
	}

	@Test
	void repositoryTargetOnARepositoryRuleset_failsToEvaluate(
			@TempDir Path tempDir
	) throws IOException {
		Path config = write(tempDir, """
				users {
				  ["owner"] {
				    repositories {
				      new {
				        name = "repo"
				        rulesets { ["settings"] { target = "repository" } }
				      }
				    }
				  }
				}
				""");

		assertThatThrownBy(() -> PklConfigLoader.load(config))
				.hasMessageContaining("rulesets");
	}

	@Test
	void pushAndRepositoryTargets_load(@TempDir Path tempDir)
			throws IOException {
		Path config = write(
				tempDir,
				"""
						users {
						  ["owner"] {
						    repositories {
						      new {
						        name = "repo"
						        rulesets {
						          ["no-binaries"] { target = "push"; fileExtensionRestrictions { "*.exe" } }
						        }
						      }
						    }
						  }
						}
						organizations {
						  ["my-org"] {
						    rulesets {
						      ["no-binaries"] { target = "push"; maxFileSize = 5 }
						      ["settings"] { target = "repository"; repositoryNameInclude { "~ALL" } }
						    }
						  }
						}
						"""
		);

		DriftyConfig loaded = PklConfigLoader.load(config);

		assertThat(loaded.allRepositories().getFirst().rulesets)
				.extractingByKey("no-binaries")
				.extracting(r -> r.target)
				.isEqualTo(Drifty.RulesetTarget.PUSH);
		assertThat(loaded.organizations().get("my-org").rulesets)
				.extractingByKey("settings")
				.extracting(r -> r.target)
				.isEqualTo(Drifty.RulesetTarget.REPOSITORY);
	}

	private static Path write(Path tempDir, String body) throws IOException {
		String schema = Path.of("config/drifty.pkl")
				.toAbsolutePath()
				.toUri()
				.toString();
		Path config = tempDir.resolve("drifty.pkl");
		Files.writeString(
				config,
				"amends \"%s\"\n\n%s".formatted(schema, body)
		);
		return config;
	}

}
