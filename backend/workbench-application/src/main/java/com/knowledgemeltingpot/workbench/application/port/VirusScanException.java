package com.knowledgemeltingpot.workbench.application.port;

/**
 * Checked exception representing a fail-closed virus-scan failure.
 * Raw scanner output must not be exposed through this message.
 */
public class VirusScanException extends Exception {

    public VirusScanException(String message) {
        super(message);
    }

    public VirusScanException(String message, Throwable cause) {
        super(message, cause);
    }
}
