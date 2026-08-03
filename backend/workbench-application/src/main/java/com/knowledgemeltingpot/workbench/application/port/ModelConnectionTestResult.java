package com.knowledgemeltingpot.workbench.application.port;

import java.time.Instant;

public record ModelConnectionTestResult(
        String status,
        boolean networkAttempted,
        boolean connectivityVerified,
        boolean credentialConfigured,
        String messageCode,
        Instant testedAt) {
}
