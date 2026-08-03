package com.knowledgemeltingpot.workbench.application.port;

/**
 * Checked exception for parse-time failures such as unsupported legacy formats,
 * archive-budget violations, illegal encoding or malformed OOXML.
 */
public class MaterialParseException extends Exception {

    public MaterialParseException(String message) {
        super(message);
    }

    public MaterialParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
