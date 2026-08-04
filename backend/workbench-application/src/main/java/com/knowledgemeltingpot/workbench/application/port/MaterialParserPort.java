package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.ChunkLocator;
import com.knowledgemeltingpot.workbench.domain.MaterialFormat;
import java.nio.file.Path;
import java.util.List;

/**
 * Port for Magic-MIME detection and deterministic material parsing.
 * The parser is never invoked before malware scanning completes.
 */
public interface MaterialParserPort {

    String detectMediaType(Path file) throws MaterialParseException;

    MaterialParseResult parse(Path file, MaterialFormat format) throws MaterialParseException;

    sealed interface MaterialParseResult permits MaterialParseResult.Parsed, MaterialParseResult.OcrRequired {

        record Parsed(String parserName, String parserVersion, List<ParsedSegment> segments) implements MaterialParseResult {
            public Parsed {
                if (parserName == null || parserName.isBlank()) {
                    throw new IllegalArgumentException("parserName is required");
                }
                if (parserVersion == null || parserVersion.isBlank()) {
                    throw new IllegalArgumentException("parserVersion is required");
                }
                if (segments == null) {
                    throw new IllegalArgumentException("segments is required");
                }
            }
        }

        record OcrRequired(String parserName, String parserVersion) implements MaterialParseResult {
            public OcrRequired {
                if (parserName == null || parserName.isBlank()) {
                    throw new IllegalArgumentException("parserName is required");
                }
                if (parserVersion == null || parserVersion.isBlank()) {
                    throw new IllegalArgumentException("parserVersion is required");
                }
            }
        }
    }

    record ParsedSegment(int ordinal, ChunkLocator locator, String text) {
        public ParsedSegment {
            if (ordinal < 0) {
                throw new IllegalArgumentException("ordinal must not be negative");
            }
            if (locator == null) {
                throw new IllegalArgumentException("locator is required");
            }
            if (text == null) {
                throw new IllegalArgumentException("text is required");
            }
        }
    }
}
