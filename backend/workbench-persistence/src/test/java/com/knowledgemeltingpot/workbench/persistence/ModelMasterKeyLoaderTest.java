package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelMasterKeyLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void mountedSecretTakesPrecedenceAndAcceptsTrailingNewline() throws Exception {
        byte[] expected = new byte[32];
        Arrays.fill(expected, (byte) 7);
        Path secretFile = temporaryDirectory.resolve("model-master-key");
        Files.writeString(secretFile, Base64.getEncoder().encodeToString(expected) + "\n");

        byte[] loaded = ModelMasterKeyLoader.load(secretFile,
                Base64.getEncoder().encodeToString(new byte[32]));

        assertThat(loaded).containsExactly(expected);
        Arrays.fill(expected, (byte) 0);
        Arrays.fill(loaded, (byte) 0);
    }

    @Test
    void rejectsMissingOrWrongLengthKeysWithoutEchoingTheirValue() {
        String invalid = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> ModelMasterKeyLoader.load(temporaryDirectory.resolve("missing"), invalid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes")
                .hasMessageNotContaining(invalid);
    }
}
