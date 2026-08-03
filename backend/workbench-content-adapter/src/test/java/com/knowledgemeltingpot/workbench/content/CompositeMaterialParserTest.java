package com.knowledgemeltingpot.workbench.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgemeltingpot.workbench.application.port.MaterialParseException;
import com.knowledgemeltingpot.workbench.application.port.MaterialParserPort;
import com.knowledgemeltingpot.workbench.domain.MaterialFormat;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompositeMaterialParserTest {

    @TempDir
    Path tempDir;

    private final CompositeMaterialParser parser = new CompositeMaterialParser();

    @Test
    void parsesTextPdfWithPageParagraphLocators() throws Exception {
        Path file = tempDir.resolve("sample.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page);
            content.setFont(PDType1Font.HELVETICA, 12);
            content.beginText();
            content.newLineAtOffset(50, 750);
            content.showText("First paragraph on page one.");
            content.endText();
            content.beginText();
            content.newLineAtOffset(50, 700);
            content.showText("Second paragraph.");
            content.endText();
            content.close();
            doc.save(file.toFile());
        }

        var result = parser.parse(file, MaterialFormat.PDF);

        assertThat(result).isInstanceOf(MaterialParserPort.MaterialParseResult.Parsed.class);
        var parsed = (MaterialParserPort.MaterialParseResult.Parsed) result;
        assertThat(parsed.segments()).hasSize(2);
        assertThat(parsed.segments().get(0).locator().type())
                .isEqualTo(MaterialParserPort.LocatorType.PDF_PAGE_PARAGRAPH);
        assertThat(parsed.segments().get(0).locator().page()).isEqualTo(1);
        assertThat(parsed.segments().get(0).text()).contains("First paragraph");
    }

    @Test
    void emptyPdfReturnsOcrRequired() throws Exception {
        Path file = tempDir.resolve("scan.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            doc.save(file.toFile());
        }

        var result = parser.parse(file, MaterialFormat.PDF);

        assertThat(result).isInstanceOf(MaterialParserPort.MaterialParseResult.OcrRequired.class);
    }

    @Test
    void parsesDocxParagraphAndTableLocators() throws Exception {
        Path file = tempDir.resolve("sample.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph paragraph = doc.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText("Policy statement.");
            XWPFTable table = doc.createTable(1, 2);
            table.getRow(0).getCell(0).setText("Cell A");
            table.getRow(0).getCell(1).setText("Cell B");
            doc.write(Files.newOutputStream(file));
        }

        var result = parser.parse(file, MaterialFormat.DOCX);

        var parsed = (MaterialParserPort.MaterialParseResult.Parsed) result;
        assertThat(parsed.segments().stream()
                .map(MaterialParserPort.ParsedSegment::locator)
                .map(MaterialParserPort.SegmentLocator::type))
                .contains(MaterialParserPort.LocatorType.DOCX_PARAGRAPH,
                        MaterialParserPort.LocatorType.DOCX_TABLE_CELL);
    }

    @Test
    void parsesXlsxWithSheetRangeLocator() throws Exception {
        Path file = tempDir.resolve("sample.xlsx");
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Policies");
            Row row = sheet.createRow(0);
            Cell cell = row.createCell(0);
            cell.setCellValue("Rule one");
            workbook.write(Files.newOutputStream(file));
        }

        var result = parser.parse(file, MaterialFormat.XLSX);

        var parsed = (MaterialParserPort.MaterialParseResult.Parsed) result;
        assertThat(parsed.segments()).hasSize(1);
        assertThat(parsed.segments().get(0).locator().type())
                .isEqualTo(MaterialParserPort.LocatorType.XLSX_RANGE);
        assertThat(parsed.segments().get(0).locator().sheet()).isEqualTo("Policies");
        assertThat(parsed.segments().get(0).text()).contains("Rule one");
    }

    @Test
    void parsesUtf8TextWithBom() throws Exception {
        Path file = tempDir.resolve("sample.txt");
        Files.write(file, new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF,
                'H', 'e', 'l', 'l', 'o', '\n', 'W', 'o', 'r', 'l', 'd'});

        var result = parser.parse(file, MaterialFormat.TXT);

        var parsed = (MaterialParserPort.MaterialParseResult.Parsed) result;
        assertThat(parsed.segments().get(0).locator().type())
                .isEqualTo(MaterialParserPort.LocatorType.TXT_LINES);
        assertThat(parsed.segments().get(0).text()).contains("Hello", "World");
    }

    @Test
    void rejectsIllegalTextEncoding() throws Exception {
        Path file = tempDir.resolve("latin1.txt");
        Files.write(file, "café".getBytes(StandardCharsets.ISO_8859_1));

        assertThatThrownBy(() -> parser.parse(file, MaterialFormat.TXT))
                .isInstanceOf(MaterialParseException.class)
                .hasMessageContaining("TXT_ILLEGAL_ENCODING");
    }

    @Test
    void rejectsPseudoMimeMismatch() throws Exception {
        Path file = tempDir.resolve("fake.pdf");
        Files.writeString(file, "This is plain text, not a PDF.");

        assertThatThrownBy(() -> parser.parse(file, MaterialFormat.PDF))
                .isInstanceOf(MaterialParseException.class)
                .hasMessageContaining("MIME_TYPE_MISMATCH");
    }

    @Test
    void rejectsMacroFormats() throws Exception {
        Path file = tempDir.resolve("macro.docm");
        Files.writeString(file, "ignored");

        assertThatThrownBy(() -> parser.parse(file, MaterialFormat.DOCX))
                .isInstanceOf(MaterialParseException.class)
                .hasMessageContaining("UNSUPPORTED_LEGACY_OR_MACRO_FORMAT");
    }

    @Test
    void rejectsZipSlipInDocx() throws Exception {
        Path file = tempDir.resolve("zipslip.docx");
        try (OutputStream out = Files.newOutputStream(file);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("../evil.txt"));
            zip.write("evil".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertThatThrownBy(() -> parser.parse(file, MaterialFormat.DOCX))
                .isInstanceOf(MaterialParseException.class)
                .hasMessageContaining("OOXML_ZIP_SLIP_DETECTED");
    }

    @Test
    void rejectsCompressionBomb() throws Exception {
        Path file = tempDir.resolve("bomb.docx");
        try (OutputStream out = Files.newOutputStream(file);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            ZipEntry big = new ZipEntry("repeating.bin");
            byte[] zeros = new byte[10 * 1024 * 1024];
            big.setMethod(ZipEntry.DEFLATED);
            zip.putNextEntry(big);
            zip.write(zeros);
            zip.closeEntry();
        }

        assertThatThrownBy(() -> parser.parse(file, MaterialFormat.DOCX))
                .isInstanceOf(MaterialParseException.class)
                .hasMessageContaining("OOXML_COMPRESSION_BOMB");
    }
}
