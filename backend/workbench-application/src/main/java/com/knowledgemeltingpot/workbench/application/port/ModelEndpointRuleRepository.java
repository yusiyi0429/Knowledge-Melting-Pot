package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.ModelEndpointRule;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ModelEndpointRuleRepository extends ModelEndpointRuleSource {
    ModelEndpointRule save(ModelEndpointRule rule);

    Optional<ModelEndpointRule> findById(UUID id);

    List<ModelEndpointRule> findAll();

    boolean delete(UUID id);
}
