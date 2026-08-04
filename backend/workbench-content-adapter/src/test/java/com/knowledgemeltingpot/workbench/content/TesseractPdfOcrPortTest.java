package com.knowledgemeltingpot.workbench.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgemeltingpot.workbench.application.port.MaterialParseException;
import com.knowledgemeltingpot.workbench.domain.ChunkLocator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TesseractPdfOcrPortTest {

    @TempDir
    Path tempDir;

    @Test
    void recognizesPagesWithFixedCommandAndSourceLocators() throws Exception {
        Path executable = fakeTesseract("""
                #!/bin/sh
                if [ "$1" = "--version" ]; then
                  printf 'tesseract 5.5.1\n'
                  exit 0
                fi
                printf '逾期超过三十天\n需要重点复核\n'
                """);
        Path pdf = scannedPdf(2);
        TesseractPdfOcrPort port = new TesseractPdfOcrPort(properties(executable, 10, 50_000_000));
        List<String> progress = new ArrayList<>();

        var parsed = port.recognizeScannedPdf(pdf,
                (completed, total) -> progress.add(completed + "/" + total));

        assertThat(parsed.parserName()).isEqualTo("Tesseract OCR");
        assertThat(parsed.parserVersion()).isEqualTo("tesseract 5.5.1");
        assertThat(parsed.segments()).hasSize(4);
        assertThat(parsed.segments().get(0).locator().type())
                .isEqualTo(ChunkLocator.LocatorType.PDF_PAGE_PARAGRAPH);
        assertThat(parsed.segments().get(0).locator().page()).isEqualTo(1);
        assertThat(parsed.segments().get(2).locator().page()).isEqualTo(2);
        assertThat(parsed.segments()).extracting(segment -> segment.text())
                .contains("逾期超过三十天", "需要重点复核");
        assertThat(progress).containsExactly("1/2", "2/2");
    }

    @Test
    void rejectsPdfBeyondTheConfiguredPageBudgetBeforeStartingOcr() throws Exception {
        Path executable = fakeTesseract("""
                #!/bin/sh
                printf 'tesseract 5.5.1\n'
                """);
        TesseractPdfOcrPort port = new TesseractPdfOcrPort(properties(executable, 1, 50_000_000));

        assertThatThrownBy(() -> port.recognizeScannedPdf(scannedPdf(2), (completed, total) -> { }))
                .isInstanceOf(MaterialParseException.class)
                .hasMessage("OCR_PAGE_BUDGET_EXCEEDED");
    }

    @Test
    void rejectsOversizedPageBeforeAllocatingTheRaster() throws Exception {
        Path executable = fakeTesseract("""
                #!/bin/sh
                if [ "$1" = "--version" ]; then
                  printf 'tesseract 5.5.1\n'
                  exit 0
                fi
                exit 99
                """);
        Path pdf = tempDir.resolve("oversized.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(100_000, 100_000)));
            document.save(pdf.toFile());
        }
        TesseractPdfOcrPort port = new TesseractPdfOcrPort(properties(executable, 1, 1_000_000));

        assertThatThrownBy(() -> port.recognizeScannedPdf(pdf, (completed, total) -> { }))
                .isInstanceOf(MaterialParseException.class)
                .hasMessage("OCR_PIXEL_BUDGET_EXCEEDED");
    }

    @Test
    void validatesLanguagesAndRenderingBudgets() {
        assertThatThrownBy(() -> new OcrProperties(true, "tesseract", "chi_sim;sh", 200, 100,
                200_000_000, Duration.ofSeconds(45), 5_000_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("languages");
    }

    private OcrProperties properties(Path executable, int maxPages, long maxPixels) {
        return new OcrProperties(true, executable.toString(), "chi_sim+eng", 100, maxPages,
                maxPixels, Duration.ofSeconds(5), 100_000);
    }

    private Path fakeTesseract(String source) throws Exception {
        Path executable = tempDir.resolve("fake-tesseract-" + System.nanoTime());
        Files.writeString(executable, source, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        return executable;
    }

    private Path scannedPdf(int pages) throws Exception {
        Path pdf = tempDir.resolve("scan-" + pages + "-" + System.nanoTime() + ".pdf");
        try (PDDocument document = new PDDocument()) {
            for (int page = 0; page < pages; page++) {
                document.addPage(new PDPage(new PDRectangle(300, 200)));
            }
            document.save(pdf.toFile());
        }
        return pdf;
    }
}
