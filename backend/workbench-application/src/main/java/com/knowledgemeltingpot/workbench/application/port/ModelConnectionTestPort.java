package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.application.security.ValidatedModelEndpoint;
import com.knowledgemeltingpot.workbench.domain.ModelConnection;
import java.time.Instant;

public interface ModelConnectionTestPort {
    ModelConnectionTestResult test(ModelConnection connection, ValidatedModelEndpoint endpoint, Instant testedAt);
}
