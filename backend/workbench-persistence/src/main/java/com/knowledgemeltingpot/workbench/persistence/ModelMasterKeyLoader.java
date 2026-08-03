package com.knowledgemeltingpot.workbench.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;

public final class ModelMasterKeyLoader {
    private static final int AES_256_KEY_BYTES = 32;

    private ModelMasterKeyLoader() {
    }

    public static byte[] load(Path secretFile, String environmentBase64) {
        if (secretFile != null && Files.isRegularFile(secretFile)) {
            return loadFile(secretFile);
        }
        if (environmentBase64 != null && !environmentBase64.isBlank()) {
            return decodeBase64(environmentBase64.trim());
        }
        throw new IllegalStateException("model credential master key secret is not configured");
    }

    private static byte[] loadFile(Path secretFile) {
        byte[] fileBytes;
        try {
            fileBytes = Files.readAllBytes(secretFile);
        } catch (IOException exception) {
            throw new IllegalStateException("model credential master key secret could not be read", exception);
        }
        try {
            byte[] trimmed = trimAsciiWhitespace(fileBytes);
            try {
                byte[] decoded = Base64.getDecoder().decode(trimmed);
                if (decoded.length != AES_256_KEY_BYTES) {
                    Arrays.fill(decoded, (byte) 0);
                    throw new IllegalStateException(
                            "model credential master key secret must contain exactly 32 bytes");
                }
                return decoded;
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("model credential master key secret is not valid Base64", exception);
            } finally {
                Arrays.fill(trimmed, (byte) 0);
            }
        } finally {
            Arrays.fill(fileBytes, (byte) 0);
        }
    }

    private static byte[] decodeBase64(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length != AES_256_KEY_BYTES) {
                Arrays.fill(decoded, (byte) 0);
                throw new IllegalStateException("model credential master key secret must contain exactly 32 bytes");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("model credential master key secret is not valid Base64", exception);
        }
    }

    private static byte[] trimAsciiWhitespace(byte[] value) {
        int start = 0;
        int end = value.length;
        while (start < end && isAsciiWhitespace(value[start])) {
            start++;
        }
        while (end > start && isAsciiWhitespace(value[end - 1])) {
            end--;
        }
        return Arrays.copyOfRange(value, start, end);
    }

    private static boolean isAsciiWhitespace(byte value) {
        return value == ' ' || value == '\t' || value == '\r' || value == '\n';
    }
}
