package com.knowledgemeltingpot.workbench.content;

import com.knowledgemeltingpot.workbench.application.port.MaterialParseException;
import com.knowledgemeltingpot.workbench.application.port.MaterialParserPort;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class TxtMaterialParser {

    private static final String PARSER_NAME = "strict-utf8-txt";
    private static final String PARSER_VERSION = "1.0";
    private static final int UTF8_BOM_LENGTH = 3;

    MaterialParserPort.MaterialParseResult parse(Path file, int linesPerSegment) throws MaterialParseException {
        byte[] bytes = readBytes(file);
        int offset = 0;
        if (bytes.length >= UTF8_BOM_LENGTH
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            offset = UTF8_BOM_LENGTH;
        }
        String text = decodeStrictUtf8(bytes, offset, bytes.length - offset);
        String[] lines = text.split("\\r?\\n", -1);
        List<MaterialParserPort.ParsedSegment> segments = new ArrayList<>();
        StringBuilder segmentText = new StringBuilder();
        int segmentStart = 0;
        for (int i = 0; i < lines.length; i++) {
            if (!segmentText.isEmpty()) {
                segmentText.append('\n');
            }
            segmentText.append(lines[i]);
            if ((i + 1) % linesPerSegment == 0 || i == lines.length - 1) {
                segments.add(new MaterialParserPort.ParsedSegment(segments.size(),
                        new MaterialParserPort.SegmentLocator(
                                MaterialParserPort.LocatorType.TXT_LINES,
                                null, null, null, null, null, null, null, segmentStart, i),
                        segmentText.toString()));
                segmentText = new StringBuilder();
                segmentStart = i + 1;
            }
        }
        return new MaterialParserPort.MaterialParseResult.Parsed(PARSER_NAME, PARSER_VERSION, segments);
    }

    private byte[] readBytes(Path file) throws MaterialParseException {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new MaterialParseException("TXT_READ_FAILED", exception);
        }
    }

    private String decodeStrictUtf8(byte[] bytes, int offset, int length) throws MaterialParseException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer input = ByteBuffer.wrap(bytes, offset, length);
            CharBuffer output = decoder.decode(input);
            return output.toString();
        } catch (CharacterCodingException exception) {
            throw new MaterialParseException("TXT_ILLEGAL_ENCODING", exception);
        }
    }
}
