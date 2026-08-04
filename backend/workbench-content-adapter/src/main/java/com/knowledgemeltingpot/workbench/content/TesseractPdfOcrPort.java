package com.knowledgemeltingpot.workbench.content;

import com.knowledgemeltingpot.workbench.application.port.MaterialParseException;
import com.knowledgemeltingpot.workbench.application.port.MaterialParserPort;
import com.knowledgemeltingpot.workbench.application.port.OcrPort;
import com.knowledgemeltingpot.workbench.domain.ChunkLocator;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Page-bounded PDF OCR using a fixed Tesseract command line. The input file
 * path is passed directly to {@link ProcessBuilder}; no shell is involved.
 */
public final class TesseractPdfOcrPort implements OcrPort {
    private static final String PARSER_NAME = "Tesseract OCR";
    private static final Pattern SAFE_VERSION = Pattern.compile("[A-Za-z0-9 ._+-]{1,80}");

    private final OcrProperties properties;

    public TesseractPdfOcrPort(OcrProperties properties) {
        this.properties = properties;
    }

    @Override
    public MaterialParserPort.MaterialParseResult.Parsed recognizeScannedPdf(Path file,
            PageProgress progress) throws MaterialParseException {
        Path workspace = null;
        try (PDDocument document = PDDocument.load(file.toFile())) {
            int pageCount = document.getNumberOfPages();
            if (pageCount < 1) {
                throw new MaterialParseException("OCR_EMPTY_PDF");
            }
            if (pageCount > properties.maxPages()) {
                throw new MaterialParseException("OCR_PAGE_BUDGET_EXCEEDED");
            }
            workspace = Files.createTempDirectory("kmp-ocr-");
            String version = readVersion(workspace);
            PDFRenderer renderer = new PDFRenderer(document);
            List<MaterialParserPort.ParsedSegment> segments = new ArrayList<>();
            long totalPixels = 0;
            int totalCharacters = 0;
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                Path imageFile = workspace.resolve("page-%04d.png".formatted(pageIndex + 1));
                Path outputFile = workspace.resolve("page-%04d.txt".formatted(pageIndex + 1));
                Path errorFile = workspace.resolve("page-%04d.err".formatted(pageIndex + 1));
                long estimatedPixels = estimatedPixels(document, pageIndex);
                if (Math.addExact(totalPixels, estimatedPixels) > properties.maxTotalPixels()) {
                    throw new MaterialParseException("OCR_PIXEL_BUDGET_EXCEEDED");
                }
                BufferedImage image = renderer.renderImageWithDPI(pageIndex, properties.dpi(), ImageType.GRAY);
                try {
                    long actualPixels = Math.multiplyExact((long) image.getWidth(), image.getHeight());
                    if (Math.addExact(totalPixels, actualPixels) > properties.maxTotalPixels()) {
                        throw new MaterialParseException("OCR_PIXEL_BUDGET_EXCEEDED");
                    }
                    totalPixels = Math.addExact(totalPixels, actualPixels);
                    if (!ImageIO.write(image, "png", imageFile.toFile())) {
                        throw new MaterialParseException("OCR_IMAGE_ENCODING_FAILED");
                    }
                } finally {
                    image.flush();
                }
                runPage(imageFile, outputFile, errorFile);
                long outputBytes = Files.size(outputFile);
                long remainingChars = properties.maxOutputChars() - (long) totalCharacters;
                if (remainingChars <= 0 || outputBytes > Math.multiplyExact(remainingChars, 4L)) {
                    throw new MaterialParseException("OCR_OUTPUT_BUDGET_EXCEEDED");
                }
                String text = Files.readString(outputFile, StandardCharsets.UTF_8);
                totalCharacters = Math.addExact(totalCharacters, text.length());
                if (totalCharacters > properties.maxOutputChars()) {
                    throw new MaterialParseException("OCR_OUTPUT_BUDGET_EXCEEDED");
                }
                appendSegments(segments, pageIndex + 1, text);
                deletePageFiles(imageFile, outputFile, errorFile);
                progress.completed(pageIndex + 1, pageCount);
            }
            if (segments.isEmpty()) {
                throw new MaterialParseException("OCR_NO_TEXT_DETECTED");
            }
            return new MaterialParserPort.MaterialParseResult.Parsed(PARSER_NAME, version, List.copyOf(segments));
        } catch (MaterialParseException exception) {
            throw exception;
        } catch (ArithmeticException exception) {
            throw new MaterialParseException("OCR_RESOURCE_BUDGET_OVERFLOW", exception);
        } catch (IOException exception) {
            throw new MaterialParseException("OCR_PDF_PROCESSING_FAILED", exception);
        } finally {
            deleteWorkspace(workspace);
        }
    }

    private String readVersion(Path workspace) throws MaterialParseException {
        Path output = workspace.resolve("version.txt");
        Path error = workspace.resolve("version.err");
        Process process;
        try {
            process = new ProcessBuilder(properties.executable(), "--version")
                    .redirectOutput(output.toFile())
                    .redirectError(error.toFile())
                    .start();
            if (!process.waitFor(Math.min(properties.pageTimeout().toMillis(), 10_000), TimeUnit.MILLISECONDS)) {
                terminate(process);
                throw new MaterialParseException("OCR_ENGINE_TIMEOUT");
            }
            if (process.exitValue() != 0 || Files.size(output) > 4_096) {
                throw new MaterialParseException("OCR_ENGINE_UNAVAILABLE");
            }
            String firstLine = Files.readAllLines(output, StandardCharsets.UTF_8).stream()
                    .findFirst().orElse("").strip();
            if (!SAFE_VERSION.matcher(firstLine).matches()) {
                throw new MaterialParseException("OCR_ENGINE_VERSION_INVALID");
            }
            return firstLine;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MaterialParseException("OCR_INTERRUPTED", exception);
        } catch (IOException exception) {
            throw new MaterialParseException("OCR_ENGINE_UNAVAILABLE", exception);
        } finally {
            deletePageFiles(output, error);
        }
    }

    private void runPage(Path image, Path output, Path error) throws MaterialParseException {
        Process process;
        try {
            process = new ProcessBuilder(properties.executable(), image.toString(), "stdout",
                    "-l", properties.languages(), "--dpi", Integer.toString(properties.dpi()), "--psm", "6")
                    .redirectOutput(output.toFile())
                    .redirectError(error.toFile())
                    .start();
            if (!process.waitFor(properties.pageTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process);
                throw new MaterialParseException("OCR_PAGE_TIMEOUT");
            }
            if (process.exitValue() != 0) {
                throw new MaterialParseException("OCR_ENGINE_FAILED");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MaterialParseException("OCR_INTERRUPTED", exception);
        } catch (IOException exception) {
            throw new MaterialParseException("OCR_ENGINE_UNAVAILABLE", exception);
        }
    }

    private long estimatedPixels(PDDocument document, int pageIndex) throws MaterialParseException {
        var box = document.getPage(pageIndex).getCropBox();
        double width = box.getWidth();
        double height = box.getHeight();
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0) {
            throw new MaterialParseException("OCR_PAGE_GEOMETRY_INVALID");
        }
        double scale = properties.dpi() / 72.0;
        double pixelWidth = Math.ceil(width * scale);
        double pixelHeight = Math.ceil(height * scale);
        if (!Double.isFinite(pixelWidth) || !Double.isFinite(pixelHeight)
                || pixelWidth > Integer.MAX_VALUE || pixelHeight > Integer.MAX_VALUE) {
            throw new MaterialParseException("OCR_PIXEL_BUDGET_EXCEEDED");
        }
        try {
            return Math.multiplyExact((long) pixelWidth, (long) pixelHeight);
        } catch (ArithmeticException exception) {
            throw new MaterialParseException("OCR_PIXEL_BUDGET_EXCEEDED", exception);
        }
    }

    private static void terminate(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static void appendSegments(List<MaterialParserPort.ParsedSegment> segments, int page, String text) {
        int lineNumber = 0;
        for (String line : text.split("\\R")) {
            String normalized = line.replaceAll("\\s+", " ").strip();
            if (!normalized.isEmpty()) {
                segments.add(new MaterialParserPort.ParsedSegment(segments.size(),
                        new ChunkLocator(ChunkLocator.LocatorType.PDF_PAGE_PARAGRAPH,
                                page, lineNumber, null, null, null, null, null, null, null, null),
                        normalized));
                lineNumber++;
            }
        }
    }

    private static void deletePageFiles(Path... files) {
        for (Path file : files) {
            if (file == null) continue;
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // Best-effort cleanup of isolated temporary files.
            }
        }
    }

    private static void deleteWorkspace(Path workspace) {
        if (workspace == null) return;
        try (var files = Files.list(workspace)) {
            files.forEach(path -> deletePageFiles(path));
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
        try {
            Files.deleteIfExists(workspace);
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }
}
