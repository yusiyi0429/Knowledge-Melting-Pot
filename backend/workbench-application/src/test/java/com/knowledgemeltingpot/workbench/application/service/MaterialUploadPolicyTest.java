package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgemeltingpot.workbench.application.error.PayloadTooLargeException;
import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.MaterialFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MaterialUploadPolicyTest {
    @ParameterizedTest
    @MethodSource("supportedFormats")
    void acceptsOnlySupportedExtensionAndMediaTypePairs(String fileName, String mediaType, MaterialFormat format) {
        var upload = MaterialUploadPolicy.validate(fileName, Material.MAX_UPLOAD_BYTES, mediaType, "A".repeat(64));

        assertThat(upload.format()).isEqualTo(format);
        assertThat(upload.sha256()).isEqualTo("a".repeat(64));
    }

    @ParameterizedTest
    @MethodSource("legacyFormats")
    void explicitlyRejectsLegacyOfficeFormats(String fileName, String mediaType) {
        assertThatThrownBy(() -> MaterialUploadPolicy.validate(fileName, 10, mediaType, "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".doc and .xls");
    }

    @Test
    void rejectsExtensionAndMediaTypeMismatch() {
        assertThatThrownBy(() -> MaterialUploadPolicy.validate(
                "spoofed.pdf", 10, "text/plain", "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void enforcesHardTwoHundredMegabyteLimit() {
        assertThatThrownBy(() -> MaterialUploadPolicy.validate(
                "large.pdf", Material.MAX_UPLOAD_BYTES + 1, "application/pdf", "a".repeat(64)))
                .isInstanceOf(PayloadTooLargeException.class)
                .hasMessageContaining("200MB");
    }

    @Test
    void rejectsPathLikeFileNames() {
        assertThatThrownBy(() -> MaterialUploadPolicy.validate(
                "../policy.pdf", 10, "application/pdf", "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fileName");
    }

    private static Stream<Arguments> supportedFormats() {
        return Stream.of(
                Arguments.of("policy.PDF", "application/pdf", MaterialFormat.PDF),
                Arguments.of("rules.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        MaterialFormat.DOCX),
                Arguments.of("cases.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        MaterialFormat.XLSX),
                Arguments.of("notes.txt", "text/plain", MaterialFormat.TXT));
    }

    private static Stream<Arguments> legacyFormats() {
        return Stream.of(
                Arguments.of("legacy.doc", "application/msword"),
                Arguments.of("legacy.xls", "application/vnd.ms-excel"));
    }
}
