package com.knowledgemeltingpot.workbench.objectstorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class S3ObjectKeyValidationTest {

    @Test
    void acceptsNormalRelativeKey() {
        assertThat(S3ObjectStorageAdapter.safeObjectKey("quarantine/abc/file.pdf"))
                .isEqualTo("quarantine/abc/file.pdf");
    }

    @ParameterizedTest
    @MethodSource("unsafeKeys")
    void rejectsUnsafeKeys(String key) {
        assertThatThrownBy(() -> S3ObjectStorageAdapter.safeObjectKey(key))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<String> unsafeKeys() {
        return Stream.of("", "  ", "/leading", "quarantine//double", "quarantine/../evil", "quarantine\\win",
                "quarantine/\u0000null", "a".repeat(1025));
    }
}
