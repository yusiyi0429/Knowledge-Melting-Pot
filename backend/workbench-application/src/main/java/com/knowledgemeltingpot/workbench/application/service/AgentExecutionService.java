package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.application.port.AgentExecutionAttemptRepository;
import com.knowledgemeltingpot.workbench.domain.AgentExecutionAttempt;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentExecutionService {
    private final AgentExecutionAttemptRepository repository;

    public AgentExecutionService(AgentExecutionAttemptRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<AgentExecutionAttempt> findByJob(UUID jobId) {
        return repository.findByJob(jobId);
    }
}
