package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.application.error.ConflictException;
import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.application.port.CredentialCipher;
import com.knowledgemeltingpot.workbench.application.port.ModelConnectionRepository;
import com.knowledgemeltingpot.workbench.application.port.ModelConnectionTestPort;
import com.knowledgemeltingpot.workbench.application.port.ModelConnectionTestResult;
import com.knowledgemeltingpot.workbench.application.security.ModelEndpointPolicy;
import com.knowledgemeltingpot.workbench.application.security.ValidatedModelEndpoint;
import com.knowledgemeltingpot.workbench.domain.CredentialEnvelope;
import com.knowledgemeltingpot.workbench.domain.ModelConfigVersion;
import com.knowledgemeltingpot.workbench.domain.ModelConnection;
import com.knowledgemeltingpot.workbench.domain.ModelConnectionValidationStatus;
import com.knowledgemeltingpot.workbench.domain.ModelProvider;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelConnectionService {
    private final ModelConnectionRepository repository;
    private final CredentialCipher credentialCipher;
    private final ModelEndpointPolicy endpointPolicy;
    private final ModelConnectionTestPort connectionTestPort;
    private final AuditService auditService;
    private final Clock clock;

    public ModelConnectionService(ModelConnectionRepository repository, CredentialCipher credentialCipher,
            ModelEndpointPolicy endpointPolicy, ModelConnectionTestPort connectionTestPort,
            AuditService auditService, Clock clock) {
        this.repository = repository;
        this.credentialCipher = credentialCipher;
        this.endpointPolicy = endpointPolicy;
        this.connectionTestPort = connectionTestPort;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public ModelConnection create(String name, ModelProvider provider, String rawBaseUrl, char[] credential,
            boolean enabled, UUID actorId, String traceId) {
        ValidatedModelEndpoint endpoint = endpointPolicy.validate(rawBaseUrl);
        Instant now = Instant.now(clock);
        UUID connectionId = UUID.randomUUID();
        Optional<CredentialEnvelope> envelope = hasCredential(credential)
                ? Optional.of(credentialCipher.seal(connectionId, credential))
                : Optional.empty();
        ModelConnection connection = repository.save(new ModelConnection(connectionId, name, provider,
                endpoint.uri(), envelope, enabled, ModelConnectionValidationStatus.UNTESTED, null,
                actorId, now, now));
        auditService.record(actorId, "MODEL_CONNECTION_CREATED", "MODEL_CONNECTION", connection.id(),
                Map.of("name", connection.name(), "provider", connection.provider().name(),
                        "credentialConfigured", connection.credentialConfigured()), traceId);
        return connection;
    }

    @Transactional(readOnly = true)
    public List<ModelConnection> list() {
        return repository.findConnections();
    }

    @Transactional(readOnly = true)
    public ModelConnection get(UUID id) {
        return repository.findConnection(id)
                .orElseThrow(() -> new NotFoundException("model connection not found: " + id));
    }

    @Transactional
    public ModelConnection update(UUID id, String name, ModelProvider provider, String rawBaseUrl,
            char[] replacementCredential, boolean clearCredential, boolean enabled, UUID actorId, String traceId) {
        if (clearCredential && hasCredential(replacementCredential)) {
            throw new IllegalArgumentException("credential and clearCredential cannot be supplied together");
        }
        ModelConnection existing = get(id);
        ValidatedModelEndpoint endpoint = endpointPolicy.validate(rawBaseUrl);
        Optional<CredentialEnvelope> envelope = existing.credentialEnvelope();
        if (clearCredential) {
            envelope = Optional.empty();
        } else if (hasCredential(replacementCredential)) {
            envelope = Optional.of(credentialCipher.seal(existing.id(), replacementCredential));
        }
        ModelConnection updated = repository.save(new ModelConnection(existing.id(), name, provider,
                endpoint.uri(), envelope, enabled, ModelConnectionValidationStatus.UNTESTED, null,
                existing.createdBy(), existing.createdAt(), Instant.now(clock)));
        auditService.record(actorId, "MODEL_CONNECTION_UPDATED", "MODEL_CONNECTION", updated.id(),
                Map.of("name", updated.name(), "provider", updated.provider().name(),
                        "credentialConfigured", updated.credentialConfigured()), traceId);
        return updated;
    }

    @Transactional
    public void delete(UUID id, UUID actorId, String traceId) {
        get(id);
        if (!repository.softDelete(id, Instant.now(clock))) {
            throw new NotFoundException("model connection not found: " + id);
        }
        auditService.record(actorId, "MODEL_CONNECTION_DELETED", "MODEL_CONNECTION", id, Map.of(), traceId);
    }

    public ModelConnectionTestResult test(UUID id, UUID actorId, String traceId) {
        ModelConnection connection = get(id);
        ValidatedModelEndpoint endpoint = endpointPolicy.validate(connection.baseUrl().toString());
        Instant testedAt = Instant.now(clock);
        ModelConnectionTestResult result = connectionTestPort.test(connection, endpoint, testedAt);
        repository.recordConnectionTest(id, connection.updatedAt(), result.connectivityVerified(), testedAt)
                .orElseThrow(() -> new ConflictException("model connection changed during connectivity test"));
        auditService.record(actorId, "MODEL_CONNECTION_TESTED", "MODEL_CONNECTION", id,
                Map.of("status", result.status(), "networkAttempted", result.networkAttempted(),
                        "connectivityVerified", result.connectivityVerified(),
                        "messageCode", result.messageCode()), traceId);
        return result;
    }

    @Transactional
    public ModelConfigVersion createVersion(UUID connectionId, String modelId, BigDecimal temperature,
            int maxOutputTokens, UUID actorId, String traceId) {
        get(connectionId);
        ModelConfigVersion version = repository.appendConfigVersion(UUID.randomUUID(), connectionId, modelId,
                temperature, maxOutputTokens, actorId, Instant.now(clock));
        auditService.record(actorId, "MODEL_CONFIG_VERSION_CREATED", "MODEL_CONFIG_VERSION", version.id(),
                Map.of("modelConnectionId", connectionId, "version", version.version(),
                        "modelId", version.modelId()), traceId);
        return version;
    }

    @Transactional(readOnly = true)
    public List<ModelConfigVersion> listVersions(UUID connectionId) {
        get(connectionId);
        return repository.findConfigVersions(connectionId);
    }

    @Transactional(readOnly = true)
    public ModelConfigVersion getVersion(UUID id) {
        return repository.findConfigVersion(id)
                .orElseThrow(() -> new NotFoundException("model config version not found: " + id));
    }

    private static boolean hasCredential(char[] credential) {
        if (credential == null || credential.length == 0) {
            return false;
        }
        for (char character : credential) {
            if (!Character.isWhitespace(character)) {
                return true;
            }
        }
        return false;
    }
}
