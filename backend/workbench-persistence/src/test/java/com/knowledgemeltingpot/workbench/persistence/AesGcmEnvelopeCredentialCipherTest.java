package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgemeltingpot.workbench.domain.CredentialEnvelope;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AesGcmEnvelopeCredentialCipherTest {

    @Test
    void roundTripsCredentialWithRandomizedEnvelopeAndRedactedToString() {
        byte[] masterKey = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        AesGcmEnvelopeCredentialCipher cipher = new AesGcmEnvelopeCredentialCipher(masterKey);
        char[] credential = "sk-sensitive-value".toCharArray();
        UUID connectionId = UUID.randomUUID();

        CredentialEnvelope first = cipher.seal(connectionId, credential);
        CredentialEnvelope second = cipher.seal(connectionId, credential);
        char[] decrypted = cipher.unseal(connectionId, first);

        try {
            assertThat(decrypted).containsExactly(credential);
            assertThat(first).isNotEqualTo(second);
            assertThat(first.encoded()).startsWith("kmp1.").doesNotContain("sk-sensitive-value");
            assertThat(cipher.toString()).isEqualTo("AesGcmEnvelopeCredentialCipher[masterKey=REDACTED]");
        } finally {
            Arrays.fill(credential, '\0');
            Arrays.fill(decrypted, '\0');
            Arrays.fill(masterKey, (byte) 0);
        }
    }

    @Test
    void wrongMasterKeyCannotDecryptEnvelope() {
        byte[] firstKey = new byte[32];
        byte[] secondKey = new byte[32];
        UUID connectionId = UUID.randomUUID();
        Arrays.fill(firstKey, (byte) 1);
        Arrays.fill(secondKey, (byte) 2);
        CredentialEnvelope envelope = new AesGcmEnvelopeCredentialCipher(firstKey)
                .seal(connectionId, "secret".toCharArray());

        assertThatThrownBy(() -> new AesGcmEnvelopeCredentialCipher(secondKey).unseal(connectionId, envelope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("credential envelope could not be decrypted");
    }

    @Test
    void envelopeCannotBeMovedToAnotherConnection() {
        byte[] key = new byte[32];
        AesGcmEnvelopeCredentialCipher cipher = new AesGcmEnvelopeCredentialCipher(key);
        CredentialEnvelope envelope = cipher.seal(UUID.randomUUID(), "secret".toCharArray());

        assertThatThrownBy(() -> cipher.unseal(UUID.randomUUID(), envelope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("credential envelope could not be decrypted");
    }
}
