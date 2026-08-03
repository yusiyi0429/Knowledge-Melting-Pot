package com.knowledgemeltingpot.workbench.application.port;

import java.nio.file.Path;

/**
 * Port for malware scanning. Implementations are fail-closed: any connectivity,
 * timeout, engine-version or unknown-response failure results in an exception.
 */
public interface VirusScanPort {

    ScanReport scan(Path file) throws VirusScanException;

    record ScanReport(boolean clean, String engineVersion, String signatureVersion) {
        public ScanReport {
            engineVersion = engineVersion == null ? "" : engineVersion.trim();
            signatureVersion = signatureVersion == null ? "" : signatureVersion.trim();
        }
    }
}
