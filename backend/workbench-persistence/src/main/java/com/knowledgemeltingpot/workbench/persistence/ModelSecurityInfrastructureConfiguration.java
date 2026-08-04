package com.knowledgemeltingpot.workbench.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.port.CredentialCipher;
import com.knowledgemeltingpot.workbench.application.port.EmbeddingPort;
import com.knowledgemeltingpot.workbench.application.port.ModelConnectionRepository;
import com.knowledgemeltingpot.workbench.application.port.ModelConnectionTestPort;
import com.knowledgemeltingpot.workbench.application.security.ModelEndpointPolicy;
import com.knowledgemeltingpot.workbench.domain.ModelProvider;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ModelSecurityInfrastructureConfiguration {

    @Bean
    CredentialCipher modelCredentialCipher(
            @Value("${workbench.model-security.master-key-file:${KMP_MODEL_MASTER_KEY_FILE:/run/secrets/kmp_model_master_key}}")
            String masterKeyFile,
            @Value("${workbench.model-security.master-key-base64:${KMP_MODEL_MASTER_KEY:}}")
            String masterKeyBase64) {
        byte[] masterKey = ModelMasterKeyLoader.load(Path.of(masterKeyFile), masterKeyBase64);
        try {
            return new AesGcmEnvelopeCredentialCipher(masterKey);
        } finally {
            Arrays.fill(masterKey, (byte) 0);
        }
    }

    @Bean
    ModelEndpointPolicy modelEndpointPolicy(
            @Value("${workbench.model-security.allowed-hosts:${KMP_ALLOWED_MODEL_HOSTS:}}") String rawAllowedHosts) {
        Set<String> allowedHosts = new LinkedHashSet<>();
        for (String value : rawAllowedHosts.split(",")) {
            if (!value.isBlank()) {
                allowedHosts.add(value.trim());
            }
        }
        return new ModelEndpointPolicy(allowedHosts, ModelSecurityInfrastructureConfiguration::resolveAll);
    }

    @Bean
    HttpClient modelProviderHttpClient(
            @Value("${workbench.model-security.test-connect-timeout:${KMP_MODEL_TEST_CONNECT_TIMEOUT:PT5S}}")
            Duration connectTimeout) {
        if (connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("model Provider connect timeout must be positive");
        }
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    ModelConnectionTestPort modelConnectionTestPort(CredentialCipher credentialCipher,
            ModelEndpointPolicy endpointPolicy, HttpClient modelProviderHttpClient,
            @Value("${workbench.model-security.test-request-timeout:${KMP_MODEL_TEST_REQUEST_TIMEOUT:PT10S}}")
            Duration requestTimeout,
            @Value("${workbench.model-security.test-max-redirects:${KMP_MODEL_TEST_MAX_REDIRECTS:2}}")
            int maxRedirects) {
        return new HttpModelConnectionTestPort(credentialCipher, endpointPolicy, modelProviderHttpClient,
                requestTimeout, maxRedirects);
    }

    @Bean
    EmbeddingPort openAiCompatibleEmbeddingPort(ModelConnectionRepository models,
            CredentialCipher credentialCipher, ModelEndpointPolicy endpointPolicy,
            HttpClient modelProviderHttpClient, ObjectMapper objectMapper, Clock clock,
            @Value("${workbench.embedding.request-timeout:${KMP_EMBEDDING_REQUEST_TIMEOUT:PT1M}}")
            Duration requestTimeout,
            @Value("${workbench.embedding.max-response-bytes:${KMP_EMBEDDING_MAX_RESPONSE_BYTES:16777216}}")
            int maxResponseBytes) {
        return new HttpEmbeddingPort(ModelProvider.OPENAI_COMPATIBLE, models, credentialCipher,
                endpointPolicy, modelProviderHttpClient, objectMapper, clock, requestTimeout, maxResponseBytes);
    }

    @Bean
    EmbeddingPort dashScopeEmbeddingPort(ModelConnectionRepository models,
            CredentialCipher credentialCipher, ModelEndpointPolicy endpointPolicy,
            HttpClient modelProviderHttpClient, ObjectMapper objectMapper, Clock clock,
            @Value("${workbench.embedding.request-timeout:${KMP_EMBEDDING_REQUEST_TIMEOUT:PT1M}}")
            Duration requestTimeout,
            @Value("${workbench.embedding.max-response-bytes:${KMP_EMBEDDING_MAX_RESPONSE_BYTES:16777216}}")
            int maxResponseBytes) {
        return new HttpEmbeddingPort(ModelProvider.DASHSCOPE, models, credentialCipher,
                endpointPolicy, modelProviderHttpClient, objectMapper, clock, requestTimeout, maxResponseBytes);
    }

    private static List<InetAddress> resolveAll(String host) throws UnknownHostException {
        return List.of(InetAddress.getAllByName(host));
    }
}
