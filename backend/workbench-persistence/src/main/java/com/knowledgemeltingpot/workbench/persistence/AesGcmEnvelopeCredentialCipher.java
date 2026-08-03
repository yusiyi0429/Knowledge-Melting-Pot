package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.port.CredentialCipher;
import com.knowledgemeltingpot.workbench.domain.CredentialEnvelope;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AesGcmEnvelopeCredentialCipher implements CredentialCipher {
    private static final String FORMAT_VERSION = "kmp1";
    private static final int AES_256_KEY_BYTES = 32;
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final byte[] KEY_AAD_PREFIX = "kmp:model-credential:key:kmp1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DATA_AAD_PREFIX = "kmp:model-credential:data:kmp1".getBytes(StandardCharsets.UTF_8);
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec masterKey;
    private final SecureRandom secureRandom;

    public AesGcmEnvelopeCredentialCipher(byte[] masterKey) {
        this(masterKey, new SecureRandom());
    }

    AesGcmEnvelopeCredentialCipher(byte[] masterKey, SecureRandom secureRandom) {
        if (masterKey == null || masterKey.length != AES_256_KEY_BYTES) {
            throw new IllegalArgumentException("model credential master key must contain exactly 32 bytes");
        }
        this.masterKey = new SecretKeySpec(masterKey.clone(), "AES");
        this.secureRandom = secureRandom;
    }

    @Override
    public CredentialEnvelope seal(UUID modelConnectionId, char[] credential) {
        if (modelConnectionId == null) {
            throw new IllegalArgumentException("modelConnectionId is required");
        }
        if (credential == null || credential.length == 0) {
            throw new IllegalArgumentException("credential must not be empty");
        }
        byte[] plaintext = encodeUtf8(credential);
        byte[] dataKey = randomBytes(AES_256_KEY_BYTES);
        byte[] keyNonce = randomBytes(GCM_NONCE_BYTES);
        byte[] dataNonce = randomBytes(GCM_NONCE_BYTES);
        byte[] keyAad = contextualAad(KEY_AAD_PREFIX, modelConnectionId);
        byte[] dataAad = contextualAad(DATA_AAD_PREFIX, modelConnectionId);
        try {
            byte[] wrappedKey = crypt(Cipher.ENCRYPT_MODE, masterKey, keyNonce, dataKey, keyAad);
            byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                    dataNonce, plaintext, dataAad);
            try {
                return new CredentialEnvelope(String.join(".", FORMAT_VERSION, encode(keyNonce), encode(wrappedKey),
                        encode(dataNonce), encode(ciphertext)));
            } finally {
                Arrays.fill(wrappedKey, (byte) 0);
                Arrays.fill(ciphertext, (byte) 0);
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("credential encryption failed", exception);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(dataKey, (byte) 0);
            Arrays.fill(keyNonce, (byte) 0);
            Arrays.fill(dataNonce, (byte) 0);
            Arrays.fill(keyAad, (byte) 0);
            Arrays.fill(dataAad, (byte) 0);
        }
    }

    @Override
    public char[] unseal(UUID modelConnectionId, CredentialEnvelope envelope) {
        if (modelConnectionId == null || envelope == null) {
            throw new IllegalArgumentException("credential envelope is required");
        }
        byte[] keyNonce = null;
        byte[] wrappedKey = null;
        byte[] dataNonce = null;
        byte[] ciphertext = null;
        byte[] dataKey = null;
        byte[] plaintext = null;
        byte[] keyAad = contextualAad(KEY_AAD_PREFIX, modelConnectionId);
        byte[] dataAad = contextualAad(DATA_AAD_PREFIX, modelConnectionId);
        try {
            String[] parts = envelope.encoded().split("\\.", -1);
            if (parts.length != 5 || !FORMAT_VERSION.equals(parts[0])) {
                throw new IllegalArgumentException("unsupported credential envelope format");
            }
            keyNonce = decode(parts[1]);
            wrappedKey = decode(parts[2]);
            dataNonce = decode(parts[3]);
            ciphertext = decode(parts[4]);
            if (keyNonce.length != GCM_NONCE_BYTES || dataNonce.length != GCM_NONCE_BYTES) {
                throw new IllegalArgumentException("invalid credential envelope nonce");
            }
            dataKey = crypt(Cipher.DECRYPT_MODE, masterKey, keyNonce, wrappedKey, keyAad);
            if (dataKey.length != AES_256_KEY_BYTES) {
                throw new IllegalArgumentException("invalid wrapped credential key");
            }
            plaintext = crypt(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                    dataNonce, ciphertext, dataAad);
            return decodeUtf8(plaintext);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("credential envelope could not be decrypted", exception);
        } finally {
            wipe(keyNonce);
            wipe(wrappedKey);
            wipe(dataNonce);
            wipe(ciphertext);
            wipe(dataKey);
            wipe(plaintext);
            wipe(keyAad);
            wipe(dataAad);
        }
    }

    @Override
    public String toString() {
        return "AesGcmEnvelopeCredentialCipher[masterKey=REDACTED]";
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        secureRandom.nextBytes(bytes);
        return bytes;
    }

    private static byte[] crypt(int mode, SecretKeySpec key, byte[] nonce, byte[] input, byte[] aad)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(input);
    }

    private static byte[] encodeUtf8(char[] value) {
        ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(value));
        byte[] encoded = new byte[buffer.remaining()];
        buffer.get(encoded);
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), (byte) 0);
        }
        return encoded;
    }

    private static char[] decodeUtf8(byte[] value) {
        CharBuffer buffer = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(value));
        char[] decoded = new char[buffer.remaining()];
        buffer.get(decoded);
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), '\0');
        }
        return decoded;
    }

    private static String encode(byte[] value) {
        return ENCODER.encodeToString(value);
    }

    private static byte[] decode(String value) {
        return DECODER.decode(value);
    }

    private static byte[] contextualAad(byte[] prefix, UUID modelConnectionId) {
        return ByteBuffer.allocate(prefix.length + 16)
                .put(prefix)
                .putLong(modelConnectionId.getMostSignificantBits())
                .putLong(modelConnectionId.getLeastSignificantBits())
                .array();
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}
