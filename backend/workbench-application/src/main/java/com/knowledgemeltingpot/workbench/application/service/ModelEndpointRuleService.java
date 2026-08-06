package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.application.port.ModelEndpointRuleRepository;
import com.knowledgemeltingpot.workbench.application.security.ModelEndpointPolicy;
import com.knowledgemeltingpot.workbench.domain.ModelEndpointRule;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelEndpointRuleService {
    private static final int MAX_PORTS_PER_RULE = 32;

    private final ModelEndpointRuleRepository repository;
    private final AuditService audit;
    private final Clock clock;

    public ModelEndpointRuleService(ModelEndpointRuleRepository repository, AuditService audit, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ModelEndpointRule> list() {
        return repository.findAll();
    }

    @Transactional
    public ModelEndpointRule create(String host, Set<Integer> allowedPorts, boolean allowHttp,
            boolean allowPrivateAddresses, UUID actorId, String traceId) {
        String normalizedHost = ModelEndpointPolicy.normalizeConfiguredHost(host);
        Set<Integer> ports = validatedPorts(allowedPorts);
        Instant now = Instant.now(clock);
        ModelEndpointRule saved = repository.save(new ModelEndpointRule(UUID.randomUUID(), normalizedHost, ports,
                allowHttp, allowPrivateAddresses, actorId, actorId, now, now));
        audit.record(actorId, "MODEL_ENDPOINT_RULE_CREATED", "MODEL_ENDPOINT_RULE", saved.id(),
                auditDetails(saved), traceId);
        return saved;
    }

    @Transactional
    public ModelEndpointRule ensurePublicHttpsHost(String host, UUID actorId, String traceId) {
        return ensureHost(host, 443, false, false, actorId, traceId);
    }

    @Transactional
    public ModelEndpointRule ensureHost(String host, int port, boolean allowHttp,
            boolean allowPrivateAddresses, UUID actorId, String traceId) {
        String normalizedHost = ModelEndpointPolicy.normalizeConfiguredHost(host);
        validatedPorts(Set.of(port));
        return repository.findByNormalizedHost(normalizedHost)
                .map(existing -> {
                    boolean unchanged = existing.allowedPorts().contains(port)
                            && (!allowHttp || existing.allowHttp())
                            && (!allowPrivateAddresses || existing.allowPrivateAddresses());
                    if (unchanged) {
                        return existing;
                    }
                    Set<Integer> ports = new HashSet<>(existing.allowedPorts());
                    ports.add(port);
                    return update(existing.id(), existing.host(), ports, existing.allowHttp() || allowHttp,
                            existing.allowPrivateAddresses() || allowPrivateAddresses, actorId, traceId);
                })
                .orElseGet(() -> create(normalizedHost, Set.of(port), allowHttp, allowPrivateAddresses,
                        actorId, traceId));
    }

    @Transactional
    public ModelEndpointRule update(UUID id, String host, Set<Integer> allowedPorts, boolean allowHttp,
            boolean allowPrivateAddresses, UUID actorId, String traceId) {
        ModelEndpointRule existing = get(id);
        ModelEndpointRule saved = repository.save(new ModelEndpointRule(existing.id(),
                ModelEndpointPolicy.normalizeConfiguredHost(host), validatedPorts(allowedPorts), allowHttp,
                allowPrivateAddresses, existing.createdBy(), actorId, existing.createdAt(), Instant.now(clock)));
        audit.record(actorId, "MODEL_ENDPOINT_RULE_UPDATED", "MODEL_ENDPOINT_RULE", saved.id(),
                auditDetails(saved), traceId);
        return saved;
    }

    @Transactional
    public void delete(UUID id, UUID actorId, String traceId) {
        ModelEndpointRule existing = get(id);
        if (!repository.delete(id)) {
            throw new NotFoundException("model endpoint rule not found: " + id);
        }
        audit.record(actorId, "MODEL_ENDPOINT_RULE_DELETED", "MODEL_ENDPOINT_RULE", id,
                Map.of("host", existing.host()), traceId);
    }

    private ModelEndpointRule get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("model endpoint rule not found: " + id));
    }

    private static Set<Integer> validatedPorts(Set<Integer> ports) {
        if (ports == null || ports.isEmpty()) {
            throw new IllegalArgumentException("至少配置一个允许端口");
        }
        if (ports.size() > MAX_PORTS_PER_RULE) {
            throw new IllegalArgumentException("单条可信主机最多配置 32 个端口");
        }
        if (ports.stream().anyMatch(port -> port == null || port < 1 || port > 65_535)) {
            throw new IllegalArgumentException("端口必须是 1 到 65535 之间的整数");
        }
        return Set.copyOf(ports);
    }

    private static Map<String, ?> auditDetails(ModelEndpointRule rule) {
        return Map.of("host", rule.host(), "allowedPorts", rule.allowedPorts().stream().sorted().toList(),
                "allowHttp", rule.allowHttp(), "allowPrivateAddresses", rule.allowPrivateAddresses());
    }
}
