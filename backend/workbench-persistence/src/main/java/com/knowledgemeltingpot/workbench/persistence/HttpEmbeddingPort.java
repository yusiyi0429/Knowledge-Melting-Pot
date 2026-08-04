package com.knowledgemeltingpot.workbench.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.error.EmbeddingProviderException;
import com.knowledgemeltingpot.workbench.application.port.CredentialCipher;
import com.knowledgemeltingpot.workbench.application.port.EmbeddingPort;
import com.knowledgemeltingpot.workbench.application.port.ModelConnectionRepository;
import com.knowledgemeltingpot.workbench.application.security.ModelEndpointPolicy;
import com.knowledgemeltingpot.workbench.domain.ChunkEmbedding;
import com.knowledgemeltingpot.workbench.domain.EmbeddingProfileVersion;
import com.knowledgemeltingpot.workbench.domain.MaterialChunk;
import com.knowledgemeltingpot.workbench.domain.ModelConnection;
import com.knowledgemeltingpot.workbench.domain.ModelConnectionValidationStatus;
import com.knowledgemeltingpot.workbench.domain.ModelProvider;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLHandshakeException;

/** JDK HTTP adapter for OpenAI-compatible and DashScope-native dense embeddings. */
public final class HttpEmbeddingPort implements EmbeddingPort {
    private static final int MAX_BATCH_SIZE = 10;

    private final ModelProvider provider;
    private final ModelConnectionRepository models;
    private final CredentialCipher credentialCipher;
    private final ModelEndpointPolicy endpointPolicy;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration requestTimeout;
    private final int maxResponseBytes;

    public HttpEmbeddingPort(ModelProvider provider, ModelConnectionRepository models,
            CredentialCipher credentialCipher, ModelEndpointPolicy endpointPolicy, HttpClient httpClient,
            ObjectMapper objectMapper, Clock clock, Duration requestTimeout, int maxResponseBytes) {
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("embedding request timeout must be positive");
        }
        if (maxResponseBytes < 1_024 || maxResponseBytes > 64 * 1_024 * 1_024) {
            throw new IllegalArgumentException("embedding response budget is outside the safety bound");
        }
        this.provider = provider;
        this.models = models;
        this.credentialCipher = credentialCipher;
        this.endpointPolicy = endpointPolicy;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.requestTimeout = requestTimeout;
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public String provider() {
        return provider.name();
    }

    @Override
    public List<ChunkEmbedding> embed(List<MaterialChunk> chunks, EmbeddingProfileVersion profile) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<List<Float>> vectors = embedTexts(chunks.stream().map(MaterialChunk::content).toList(),
                profile, "document");
        Instant now = Instant.now(clock);
        List<ChunkEmbedding> result = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            MaterialChunk chunk = chunks.get(index);
            result.add(new ChunkEmbedding(chunk.id(), profile.id(), chunk.contentHash(), profile.dimension(),
                    vectors.get(index), now));
        }
        return List.copyOf(result);
    }

    @Override
    public List<Float> embedQuery(String query, EmbeddingProfileVersion profile) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("embedding query must not be blank");
        }
        return embedTexts(List.of(query), profile, "query").getFirst();
    }

    private List<List<Float>> embedTexts(List<String> texts, EmbeddingProfileVersion profile, String textType) {
        requireProfile(profile);
        ModelConnection connection = models.findConnection(profile.modelConnectionId())
                .orElseThrow(() -> failure("EMBEDDING_CONFIGURATION_INVALID", false));
        requireConnection(connection);
        char[] credential = connection.credentialEnvelope()
                .map(envelope -> credentialCipher.unseal(connection.id(), envelope))
                .orElseThrow(() -> failure("EMBEDDING_CONFIGURATION_INVALID", false));
        try {
            List<List<Float>> result = new ArrayList<>(texts.size());
            for (int start = 0; start < texts.size(); start += MAX_BATCH_SIZE) {
                int end = Math.min(start + MAX_BATCH_SIZE, texts.size());
                result.addAll(requestBatch(connection, profile, texts.subList(start, end), textType,
                        new String(credential)));
            }
            return List.copyOf(result);
        } finally {
            Arrays.fill(credential, '\0');
        }
    }

    private List<List<Float>> requestBatch(ModelConnection connection, EmbeddingProfileVersion profile,
            List<String> texts, String textType, String credential) {
        boolean nativeDashScope = provider == ModelProvider.DASHSCOPE
                && !connection.baseUrl().getPath().contains("/compatible-mode/");
        URI endpoint = endpointPolicy.validate(embeddingUri(connection.baseUrl(), nativeDashScope).toString()).uri();
        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(requestBody(profile, texts, textType, nativeDashScope));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw failure("EMBEDDING_CONFIGURATION_INVALID", false);
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + credential)
                .header("User-Agent", "Knowledge-Melting-Pot/embedding")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        final HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException exception) {
            throw failure("EMBEDDING_TIMEOUT", true);
        } catch (SSLHandshakeException exception) {
            throw failure("EMBEDDING_TLS_FAILED", false);
        } catch (ConnectException exception) {
            throw failure("EMBEDDING_CONNECTION_UNAVAILABLE", true);
        } catch (IOException exception) {
            throw failure("EMBEDDING_NETWORK_FAILED", true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("EMBEDDING_INTERRUPTED", true);
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw statusFailure(status);
            }
            boolean json = response.headers().firstValue("Content-Type")
                    .map(value -> value.toLowerCase(java.util.Locale.ROOT).startsWith("application/json"))
                    .orElse(false);
            if (!json) {
                throw failure("EMBEDDING_RESPONSE_INVALID", false);
            }
            byte[] responseBytes = body.readNBytes(maxResponseBytes + 1);
            try {
                if (responseBytes.length > maxResponseBytes) {
                    throw failure("EMBEDDING_RESPONSE_TOO_LARGE", false);
                }
                JsonNode root = objectMapper.readTree(responseBytes);
                return parseVectors(root, texts.size(), profile, nativeDashScope);
            } finally {
                Arrays.fill(responseBytes, (byte) 0);
            }
        } catch (EmbeddingProviderException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure("EMBEDDING_RESPONSE_INVALID", false);
        }
    }

    private Map<String, Object> requestBody(EmbeddingProfileVersion profile, List<String> texts,
            String textType, boolean nativeDashScope) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", profile.modelId());
        if (nativeDashScope) {
            body.put("input", Map.of("texts", texts));
            body.put("parameters", Map.of("dimension", profile.dimension(), "output_type", "dense",
                    "text_type", textType));
        } else {
            body.put("input", texts);
            body.put("dimensions", profile.dimension());
            body.put("encoding_format", "float");
        }
        return body;
    }

    private List<List<Float>> parseVectors(JsonNode root, int expectedCount,
            EmbeddingProfileVersion profile, boolean nativeDashScope) {
        JsonNode items = nativeDashScope ? root.path("output").path("embeddings") : root.path("data");
        if (!items.isArray() || items.size() != expectedCount) {
            throw failure("EMBEDDING_RESPONSE_INVALID", false);
        }
        List<IndexedVector> indexed = new ArrayList<>(expectedCount);
        Set<Integer> seen = new HashSet<>();
        for (JsonNode item : items) {
            JsonNode indexNode = nativeDashScope ? item.path("text_index") : item.path("index");
            if (!indexNode.canConvertToInt()) {
                throw failure("EMBEDDING_RESPONSE_INVALID", false);
            }
            int index = indexNode.intValue();
            if (index < 0 || index >= expectedCount || !seen.add(index)) {
                throw failure("EMBEDDING_RESPONSE_INVALID", false);
            }
            indexed.add(new IndexedVector(index, readVector(item.path("embedding"), profile)));
        }
        indexed.sort(Comparator.comparingInt(IndexedVector::index));
        return indexed.stream().map(IndexedVector::vector).toList();
    }

    private List<Float> readVector(JsonNode node, EmbeddingProfileVersion profile) {
        if (!node.isArray() || node.size() != profile.dimension()) {
            throw failure("EMBEDDING_DIMENSION_MISMATCH", false);
        }
        List<Float> vector = new ArrayList<>(node.size());
        double normSquared = 0;
        for (JsonNode value : node) {
            if (!value.isNumber()) {
                throw failure("EMBEDDING_RESPONSE_INVALID", false);
            }
            float number = value.floatValue();
            if (!Float.isFinite(number)) {
                throw failure("EMBEDDING_RESPONSE_INVALID", false);
            }
            vector.add(number);
            normSquared += (double) number * number;
        }
        if ("L2".equals(profile.normalization())) {
            double norm = Math.sqrt(normSquared);
            if (!Double.isFinite(norm) || norm == 0) {
                throw failure("EMBEDDING_RESPONSE_INVALID", false);
            }
            for (int index = 0; index < vector.size(); index++) {
                vector.set(index, (float) (vector.get(index) / norm));
            }
        }
        return List.copyOf(vector);
    }

    private void requireProfile(EmbeddingProfileVersion profile) {
        if (!provider.name().equals(profile.provider())) {
            throw failure("EMBEDDING_CONFIGURATION_INVALID", false);
        }
    }

    private void requireConnection(ModelConnection connection) {
        if (connection.provider() != provider || !connection.enabled() || !connection.credentialConfigured()
                || connection.validationStatus() != ModelConnectionValidationStatus.CONNECTIVITY_VERIFIED) {
            throw failure("EMBEDDING_CONFIGURATION_INVALID", false);
        }
        endpointPolicy.validate(connection.baseUrl().toString());
    }

    private static URI embeddingUri(URI base, boolean nativeDashScope) {
        String resource = nativeDashScope
                ? "services/embeddings/text-embedding/text-embedding"
                : "embeddings";
        String basePath = base.getPath() == null ? "" : base.getPath();
        String path = (basePath.endsWith("/") ? basePath : basePath + "/") + resource;
        try {
            return new URI(base.getScheme(), null, base.getHost(), base.getPort(), path, null, null);
        } catch (URISyntaxException exception) {
            throw failure("EMBEDDING_CONFIGURATION_INVALID", false);
        }
    }

    private static EmbeddingProviderException statusFailure(int status) {
        if (status == 401 || status == 403) {
            return failure("EMBEDDING_AUTHENTICATION_FAILED", false);
        }
        if (status == 429) {
            return failure("EMBEDDING_RATE_LIMITED", true);
        }
        if (status >= 500) {
            return failure("EMBEDDING_PROVIDER_UNAVAILABLE", true);
        }
        if (status >= 300 && status < 400) {
            return failure("EMBEDDING_REDIRECT_REJECTED", false);
        }
        return failure("EMBEDDING_REQUEST_REJECTED", false);
    }

    private static EmbeddingProviderException failure(String code, boolean retryable) {
        return new EmbeddingProviderException(code, retryable);
    }

    private record IndexedVector(int index, List<Float> vector) {
    }
}
