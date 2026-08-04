package com.knowledgemeltingpot.workbench.content;

import com.knowledgemeltingpot.workbench.application.port.MaterialParseException;
import com.knowledgemeltingpot.workbench.application.port.MaterialParserPort;
import com.knowledgemeltingpot.workbench.domain.ChunkLocator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

class XlsxMaterialParser {

    private static final String PARSER_NAME = "Apache POI XSSF-SAX";
    private static final String PARSER_VERSION = "5.3.0";

    MaterialParserPort.MaterialParseResult parse(Path file) throws MaterialParseException {
        try (OPCPackage pkg = OPCPackage.open(file.toFile())) {
            XSSFReader reader = new XSSFReader(pkg);
            StylesTable styles = reader.getStylesTable();
            ReadOnlySharedStringsTable sharedStrings = new ReadOnlySharedStringsTable(pkg);
            DataFormatter formatter = new DataFormatter();
            List<MaterialParserPort.ParsedSegment> segments = new ArrayList<>();

            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (sheets.hasNext()) {
                try (InputStream stream = sheets.next()) {
                    String sheetName = sheets.getSheetName();
                    SheetData handler = new SheetData();
                    XSSFSheetXMLHandler xmlHandler = new XSSFSheetXMLHandler(styles, sharedStrings, handler,
                            formatter, false);
                    XMLReader xmlReader = org.apache.poi.util.XMLHelper.newXMLReader();
                    xmlReader.setContentHandler(xmlHandler);
                    xmlReader.parse(new InputSource(stream));
                    MaterialParserPort.ParsedSegment segment = handler.toSegment(segments.size(), sheetName);
                    if (segment != null) {
                        segments.add(segment);
                    }
                }
            }
            return new MaterialParserPort.MaterialParseResult.Parsed(PARSER_NAME, PARSER_VERSION, segments);
        } catch (IOException | SAXException | OpenXML4JException | ParserConfigurationException exception) {
            throw new MaterialParseException("XLSX_PARSE_FAILED", exception);
        }
    }

    private static class SheetData implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final Map<Integer, Map<Integer, String>> rows = new TreeMap<>();
        private int currentRow = -1;
        private int firstRow = Integer.MAX_VALUE;
        private int lastRow = Integer.MIN_VALUE;
        private int firstCol = Integer.MAX_VALUE;
        private int lastCol = Integer.MIN_VALUE;

        @Override
        public void startRow(int rowNum) {
            currentRow = rowNum;
        }

        @Override
        public void endRow(int rowNum) {
            // No-op: data is already collected.
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            if (formattedValue == null || formattedValue.isBlank()) {
                return;
            }
            CellReference ref = new CellReference(cellReference);
            int row = ref.getRow();
            int col = ref.getCol();
            rows.computeIfAbsent(row, ignored -> new TreeMap<>()).put(col, formattedValue.trim());
            firstRow = Math.min(firstRow, row);
            lastRow = Math.max(lastRow, row);
            firstCol = Math.min(firstCol, col);
            lastCol = Math.max(lastCol, col);
        }

        @Override
        public void headerFooter(String text, boolean isHeader, String tagName) {
            // Ignored.
        }

        MaterialParserPort.ParsedSegment toSegment(int ordinal, String sheetName) {
            if (rows.isEmpty()) {
                return null;
            }
            StringBuilder text = new StringBuilder();
            for (Map.Entry<Integer, Map<Integer, String>> rowEntry : rows.entrySet()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                List<String> cells = new ArrayList<>(rowEntry.getValue().values());
                text.append(String.join("\t", cells));
            }
            return new MaterialParserPort.ParsedSegment(ordinal,
                    new ChunkLocator(
                            ChunkLocator.LocatorType.XLSX_RANGE,
                            null, null, null, sheetName, firstRow, lastRow, firstCol, lastCol, null, null),
                    text.toString());
        }
    }
}
