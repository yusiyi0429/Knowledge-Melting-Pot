package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.ModelEndpointRule;
import java.util.Optional;

@FunctionalInterface
public interface ModelEndpointRuleSource {
    Optional<ModelEndpointRule> findByNormalizedHost(String host);
}
