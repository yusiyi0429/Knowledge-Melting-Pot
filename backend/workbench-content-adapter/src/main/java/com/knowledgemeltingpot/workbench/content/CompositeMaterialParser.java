package com.knowledgemeltingpot.workbench.content;

import com.knowledgemeltingpot.workbench.application.port.MaterialParseException;
import com.knowledgemeltingpot.workbench.application.port.MaterialParserPort;
import com.knowledgemeltingpot.workbench.domain.MaterialFormat;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.tika.Tika;

public class CompositeMaterialParser implements MaterialParserPort {

    private static final int LINES_PER_SEGMENT = 100;

    private final Tika tika = new Tika();
    private final PdfMaterialParser pdfParser = new PdfMaterialParser();
    private final DocxMaterialParser docxParser = new DocxMaterialParser();
    private final XlsxMaterialParser xlsxParser = new XlsxMaterialParser();
    private final TxtMaterialParser txtParser = new TxtMaterialParser();

    @Override
    public String detectMediaType(Path file) throws MaterialParseException {
        try {
            return tika.detect(file.toFile());
        } catch (IOException exception) {
            throw new MaterialParseException("MEDIA_TYPE_DETECTION_FAILED", exception);
        }
    }

    @Override
    public MaterialParseResult parse(Path file, MaterialFormat format) throws MaterialParseException {
        rejectLegacyAndMacroFormats(file);
        String detected = detectMediaType(file);
        if (!format.mediaType().equals(detected)) {
            throw new MaterialParseException("MIME_TYPE_MISMATCH: expected " + format.mediaType()
                    + " but detected " + detected);
        }
        if (format == MaterialFormat.DOCX || format == MaterialFormat.XLSX) {
            OoxmlBudgetChecker.check(file);
        }
        return switch (format) {
            case PDF -> pdfParser.parse(file);
            case DOCX -> docxParser.parse(file);
            case XLSX -> xlsxParser.parse(file);
            case TXT -> txtParser.parse(file, LINES_PER_SEGMENT);
        };
    }

    private void rejectLegacyAndMacroFormats(Path file) throws MaterialParseException {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".doc") || name.endsWith(".xls") || name.endsWith(".docm") || name.endsWith(".xlsm")) {
            throw new MaterialParseException("UNSUPPORTED_LEGACY_OR_MACRO_FORMAT");
        }
    }
}
