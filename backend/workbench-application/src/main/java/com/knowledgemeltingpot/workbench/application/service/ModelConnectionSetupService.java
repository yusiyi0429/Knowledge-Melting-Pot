package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.domain.ModelConfigVersion;
import com.knowledgemeltingpot.workbench.domain.ModelConnection;
import com.knowledgemeltingpot.workbench.domain.ModelProvider;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelConnectionSetupService {
    static final BigDecimal DEFAULT_TEMPERATURE = new BigDecimal("0.2");
    static final int DEFAULT_MAX_OUTPUT_TOKENS = 8192;

    private final ModelEndpointRuleService endpointRules;
    private final ModelConnectionService modelConnections;

    public ModelConnectionSetupService(ModelEndpointRuleService endpointRules,
            ModelConnectionService modelConnections) {
        this.endpointRules = endpointRules;
        this.modelConnections = modelConnections;
    }

    @Transactional
    public Configuration configure(String name, ModelProvider provider, String baseUrl, char[] credential,
            boolean enabled, String modelId, boolean allowPrivateAddresses, UUID actorId, String traceId) {
        Endpoint endpoint = endpoint(baseUrl);
        endpointRules.ensureHost(endpoint.host(), endpoint.port(), endpoint.allowHttp(),
                allowPrivateAddresses, actorId, traceId);
        ModelConnection connection = modelConnections.create(name, provider, baseUrl, credential,
                enabled, actorId, traceId);
        ModelConfigVersion version = modelConnections.createVersion(connection.id(), modelId,
                DEFAULT_TEMPERATURE, DEFAULT_MAX_OUTPUT_TOKENS, actorId, traceId);
        return new Configuration(connection, version);
    }

    private static Endpoint endpoint(String rawBaseUrl) {
        URI uri;
        try {
            uri = URI.create(rawBaseUrl.trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Base URL 必须是完整的 HTTP 或 HTTPS 地址", exception);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Base URL 必须使用 HTTP 或 HTTPS");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Base URL 必须包含有效主机名或 IPv4 地址");
        }
        int port = uri.getPort() >= 0 ? uri.getPort() : (scheme.equals("https") ? 443 : 80);
        return new Endpoint(uri.getHost(), port, scheme.equals("http"));
    }

    private record Endpoint(String host, int port, boolean allowHttp) {
    }

    public record Configuration(ModelConnection connection, ModelConfigVersion configVersion) {
    }
}
