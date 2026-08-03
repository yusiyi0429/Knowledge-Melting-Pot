package com.knowledgemeltingpot.workbench.content;

import com.knowledgemeltingpot.workbench.application.port.MaterialParseException;
import com.knowledgemeltingpot.workbench.application.port.MaterialParserPort;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

class PdfMaterialParser {

    private static final String PARSER_NAME = "Apache PDFBox";
    private static final String PARSER_VERSION = "2.0.31";

    MaterialParserPort.MaterialParseResult parse(Path file) throws MaterialParseException {
        try (PDDocument document = PDDocument.load(file.toFile())) {
            int pages = document.getNumberOfPages();
            if (pages == 0) {
                return new MaterialParserPort.MaterialParseResult.OcrRequired(PARSER_NAME, PARSER_VERSION);
            }
            List<MaterialParserPort.ParsedSegment> segments = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            long totalText = 0;
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document);
                String[] lines = pageText.split("\\r?\\n");
                int lineIndex = 0;
                for (String line : lines) {
                    String trimmed = line.replaceAll("\\s+", " ").trim();
                    if (!trimmed.isEmpty()) {
                        totalText += trimmed.length();
                        segments.add(new MaterialParserPort.ParsedSegment(segments.size(),
                                new MaterialParserPort.SegmentLocator(
                                        MaterialParserPort.LocatorType.PDF_PAGE_PARAGRAPH,
                                        page, lineIndex, null, null, null, null, null, null, null),
                                trimmed));
                        lineIndex++;
                    }
                }
            }
            if (totalText == 0) {
                return new MaterialParserPort.MaterialParseResult.OcrRequired(PARSER_NAME, PARSER_VERSION);
            }
            return new MaterialParserPort.MaterialParseResult.Parsed(PARSER_NAME, PARSER_VERSION, segments);
        } catch (IOException exception) {
            throw new MaterialParseException("PDF_PARSE_FAILED", exception);
        }
    }
}
