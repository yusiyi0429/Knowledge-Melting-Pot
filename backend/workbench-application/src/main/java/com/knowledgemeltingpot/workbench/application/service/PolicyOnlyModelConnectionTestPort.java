package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.application.port.ModelConnectionTestPort;
import com.knowledgemeltingpot.workbench.application.port.ModelConnectionTestResult;
import com.knowledgemeltingpot.workbench.application.security.ValidatedModelEndpoint;
import com.knowledgemeltingpot.workbench.domain.ModelConnection;
import java.time.Instant;

/**
 * Phase-one connection test: validates policy and configuration without sending credentials or making network calls.
 */
public final class PolicyOnlyModelConnectionTestPort implements ModelConnectionTestPort {
    @Override
    public ModelConnectionTestResult test(ModelConnection connection, ValidatedModelEndpoint endpoint,
            Instant testedAt) {
        return new ModelConnectionTestResult("CONFIGURATION_VALIDATED", false, false,
                connection.credentialConfigured(), "model.configuration.validated-offline", testedAt);
    }
}
