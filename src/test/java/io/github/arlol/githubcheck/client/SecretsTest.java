package io.github.arlol.githubcheck.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretsTest {

	// 32-byte all-zeros public key, base64-encoded (used in OrgCheckerFixTest)
	private static final String TEST_PUBLIC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

	@Test
	void encryptSecret_returnsNonEmptyBase64() {
		String encrypted = Secrets
				.encryptSecret(TEST_PUBLIC_KEY, "ghp_test_value");
		assertThat(encrypted).isNotBlank();
	}

}
