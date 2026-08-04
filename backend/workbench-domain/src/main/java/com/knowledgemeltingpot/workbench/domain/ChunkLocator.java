package com.knowledgemeltingpot.workbench.domain;

/**
 * Structured, database-derived origin for one chunk of a verified material.
 * Every field is set by the parser of the matching format; no client input may
 * ever populate these coordinates.
 */
public record ChunkLocator(
        LocatorType type,
        Integer page,
        Integer paragraph,
        Integer table,
        String sheet,
        Integer rowStart,
        Integer rowEnd,
        Integer colStart,
        Integer colEnd,
        Integer lineStart,
        Integer lineEnd) {

    public ChunkLocator {
        type = DomainChecks.required(type, "type");
        if (page != null && page < 1) {
            throw new IllegalArgumentException("page must be positive");
        }
        if (paragraph != null && paragraph < 0) {
            throw new IllegalArgumentException("paragraph must not be negative");
        }
        if (table != null && table < 0) {
            throw new IllegalArgumentException("table must not be negative");
        }
        if (rowStart != null && rowStart < 0) {
            throw new IllegalArgumentException("rowStart must not be negative");
        }
        if (rowEnd != null && rowEnd < 0) {
            throw new IllegalArgumentException("rowEnd must not be negative");
        }
        if (colStart != null && colStart < 0) {
            throw new IllegalArgumentException("colStart must not be negative");
        }
        if (colEnd != null && colEnd < 0) {
            throw new IllegalArgumentException("colEnd must not be negative");
        }
        if (lineStart != null && lineStart < 0) {
            throw new IllegalArgumentException("lineStart must not be negative");
        }
        if (lineEnd != null && lineEnd < 0) {
            throw new IllegalArgumentException("lineEnd must not be negative");
        }
        if (rowStart != null && rowEnd != null && rowEnd < rowStart) {
            throw new IllegalArgumentException("rowEnd must not be before rowStart");
        }
        if (colStart != null && colEnd != null && colEnd < colStart) {
            throw new IllegalArgumentException("colEnd must not be before colStart");
        }
        if (lineStart != null && lineEnd != null && lineEnd < lineStart) {
            throw new IllegalArgumentException("lineEnd must not be before lineStart");
        }
    }

    public enum LocatorType {
        PDF_PAGE_PARAGRAPH,
        DOCX_PARAGRAPH,
        DOCX_TABLE_CELL,
        XLSX_RANGE,
        TXT_LINES
    }
}
