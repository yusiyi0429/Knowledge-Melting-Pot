package com.knowledgemeltingpot.workbench.application.port;

import java.nio.file.Path;

/**
 * Restricted OCR boundary for scanned PDF input. Implementations must apply
 * explicit resource budgets and must never execute user-supplied commands.
 */
public interface OcrPort {

    MaterialParserPort.MaterialParseResult.Parsed recognizeScannedPdf(Path file,
            PageProgress progress) throws MaterialParseException;

    @FunctionalInterface
    interface PageProgress {
        void completed(int completedPages, int totalPages);
    }
}
