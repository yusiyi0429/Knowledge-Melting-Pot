package com.knowledgemeltingpot.workbench.content;

import com.knowledgemeltingpot.workbench.application.port.MaterialParseException;
import com.knowledgemeltingpot.workbench.application.port.MaterialParserPort;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

class DocxMaterialParser {

    private static final String PARSER_NAME = "Apache POI XWPF";
    private static final String PARSER_VERSION = "5.3.0";

    MaterialParserPort.MaterialParseResult parse(Path file) throws MaterialParseException {
        try (XWPFDocument document = new XWPFDocument(java.nio.file.Files.newInputStream(file))) {
            List<MaterialParserPort.ParsedSegment> segments = new ArrayList<>();
            int paragraphIndex = 0;
            int tableIndex = 0;
            for (var element : document.getBodyElements()) {
                switch (element.getElementType()) {
                    case PARAGRAPH -> {
                        XWPFParagraph paragraph = (XWPFParagraph) element;
                        String text = paragraph.getText().trim();
                        if (!text.isEmpty()) {
                            segments.add(new MaterialParserPort.ParsedSegment(segments.size(),
                                    new MaterialParserPort.SegmentLocator(
                                            MaterialParserPort.LocatorType.DOCX_PARAGRAPH,
                                            null, paragraphIndex, null, null, null, null, null, null, null),
                                    text));
                        }
                        paragraphIndex++;
                    }
                    case TABLE -> {
                        XWPFTable table = (XWPFTable) element;
                        List<XWPFTableRow> rows = table.getRows();
                        for (int row = 0; row < rows.size(); row++) {
                            XWPFTableRow tableRow = rows.get(row);
                            List<XWPFTableCell> cells = tableRow.getTableCells();
                            for (int col = 0; col < cells.size(); col++) {
                                String text = cells.get(col).getText().trim();
                                if (!text.isEmpty()) {
                                    segments.add(new MaterialParserPort.ParsedSegment(segments.size(),
                                            new MaterialParserPort.SegmentLocator(
                                                    MaterialParserPort.LocatorType.DOCX_TABLE_CELL,
                                                    null, null, null, row, row, col, col, null, null),
                                            text));
                                }
                            }
                        }
                        tableIndex++;
                    }
                    default -> {
                        // Ignore other body element types.
                    }
                }
            }
            return new MaterialParserPort.MaterialParseResult.Parsed(PARSER_NAME, PARSER_VERSION, segments);
        } catch (IOException exception) {
            throw new MaterialParseException("DOCX_PARSE_FAILED", exception);
        }
    }
}
