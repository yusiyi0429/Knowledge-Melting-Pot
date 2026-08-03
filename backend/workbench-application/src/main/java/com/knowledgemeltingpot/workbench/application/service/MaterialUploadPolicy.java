package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.application.error.PayloadTooLargeException;
import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.MaterialFormat;
import java.util.Arrays;
import java.util.Locale;

public final class MaterialUploadPolicy {
    private MaterialUploadPolicy() {
    }

    public static ValidatedUpload validate(String fileName, long sizeBytes, String mediaType, String sha256) {
        String normalizedName = validateFileName(fileName);
        if (sizeBytes > Material.MAX_UPLOAD_BYTES) {
            throw new PayloadTooLargeException("material exceeds the 200MB upload limit");
        }
        if (sizeBytes < 1) {
            throw new IllegalArgumentException("material size must be positive");
        }
        String extension = normalizedName.substring(normalizedName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (extension.equals("doc") || extension.equals("xls")) {
            throw new IllegalArgumentException("legacy .doc and .xls files are not supported");
        }
        MaterialFormat format = Arrays.stream(MaterialFormat.values())
                .filter(candidate -> candidate.extension().equals(extension))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("supported material formats are pdf, docx, xlsx, txt"));
        String normalizedMediaType = requireText(mediaType, "mediaType").toLowerCase(Locale.ROOT);
        if (!format.mediaType().equals(normalizedMediaType)) {
            throw new IllegalArgumentException("mediaType does not match the file extension");
        }
        String normalizedSha256 = requireText(sha256, "sha256").toLowerCase(Locale.ROOT);
        if (!normalizedSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be a 64-character hexadecimal digest");
        }
        return new ValidatedUpload(normalizedName, format, normalizedMediaType, normalizedSha256, sizeBytes);
    }

    private static String validateFileName(String fileName) {
        String normalized = requireText(fileName, "fileName");
        if (normalized.length() > 255 || normalized.indexOf('/') >= 0 || normalized.indexOf('\\') >= 0
                || normalized.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException("fileName is invalid");
        }
        int dot = normalized.lastIndexOf('.');
        if (dot < 1 || dot == normalized.length() - 1) {
            throw new IllegalArgumentException("fileName must include a supported extension");
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public record ValidatedUpload(
            String fileName,
            MaterialFormat format,
            String mediaType,
            String sha256,
            long sizeBytes) {
    }
}
