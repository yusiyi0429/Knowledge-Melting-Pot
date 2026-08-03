package com.knowledgemeltingpot.workbench.content;

import com.knowledgemeltingpot.workbench.application.port.MaterialParseException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Pre-checks OOXML containers for Zip Slip, entry count, total uncompressed size,
 * per-entry size and per-entry compression ratio before handing the file to POI.
 */
public final class OoxmlBudgetChecker {

    private static final int MAX_ENTRIES = 10000;
    private static final long MAX_TOTAL_UNCOMPRESSED = 500L * 1024 * 1024;
    private static final long MAX_ENTRY_UNCOMPRESSED = 200L * 1024 * 1024;
    private static final double MAX_COMPRESSION_RATIO = 100.0;

    private OoxmlBudgetChecker() {
    }

    public static void check(Path file) throws MaterialParseException {
        long totalUncompressed = 0;
        int entryCount = 0;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            if (zip.getEntry("[Content_Types].xml") == null) {
                throw new MaterialParseException("OOXML_CONTENT_TYPES_MISSING");
            }
            for (ZipEntry entry : zip.stream().toList()) {
                entryCount++;
                if (entryCount > MAX_ENTRIES) {
                    throw new MaterialParseException("OOXML_TOO_MANY_ENTRIES");
                }
                String name = entry.getName();
                if (name.isEmpty() || name.startsWith("/") || name.startsWith("\\") || name.contains("..")) {
                    throw new MaterialParseException("OOXML_ZIP_SLIP_DETECTED");
                }
                long uncompressed = entry.getSize();
                if (uncompressed < 0) {
                    uncompressed = 0;
                }
                if (uncompressed > MAX_ENTRY_UNCOMPRESSED) {
                    throw new MaterialParseException("OOXML_ENTRY_TOO_LARGE");
                }
                totalUncompressed += uncompressed;
                if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED) {
                    throw new MaterialParseException("OOXML_TOTAL_UNCOMPRESSED_TOO_LARGE");
                }
                long compressed = entry.getCompressedSize();
                if (compressed > 0) {
                    double ratio = (double) uncompressed / (double) compressed;
                    if (ratio > MAX_COMPRESSION_RATIO && uncompressed > 1024) {
                        throw new MaterialParseException("OOXML_COMPRESSION_BOMB");
                    }
                } else if (uncompressed > 0) {
                    throw new MaterialParseException("OOXML_COMPRESSION_BOMB");
                }
            }
        } catch (IOException exception) {
            throw new MaterialParseException("OOXML_OPEN_FAILED", exception);
        }
    }
}
