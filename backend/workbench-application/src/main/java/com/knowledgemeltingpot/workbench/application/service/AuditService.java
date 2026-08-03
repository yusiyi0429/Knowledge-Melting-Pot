package com.knowledgemeltingpot.workbench.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.port.AuditRepository;
import com.knowledgemeltingpot.workbench.domain.AuditEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final AuditRepository auditRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuditService(AuditRepository auditRepository, ObjectMapper objectMapper, Clock clock) {
        this.auditRepository = auditRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void record(UUID actorId, String action, String targetType, UUID targetId,
            Map<String, ?> details, String traceId) {
        auditRepository.append(new AuditEvent(UUID.randomUUID(), actorId, action, targetType, targetId,
                toJson(details), traceId, Instant.now(clock)));
    }

    private String toJson(Map<String, ?> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("audit details are not serializable", exception);
        }
    }
}
