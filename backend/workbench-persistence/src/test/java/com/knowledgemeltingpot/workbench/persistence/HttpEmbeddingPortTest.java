package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.knowledgemeltingpot.workbench.application.error.EmbeddingProviderException;
import com.knowledgemeltingpot.workbench.application.port.CredentialCipher;
import com.knowledgemeltingpot.workbench.application.port.ModelConnectionRepository;
import com.knowledgemeltingpot.workbench.application.security.ModelEndpointPolicy;
import com.knowledgemeltingpot.workbench.domain.ChunkLocator;
import com.knowledgemeltingpot.workbench.domain.CredentialEnvelope;
import com.knowledgemeltingpot.workbench.domain.EmbeddingProfileVersion;
import com.knowledgemeltingpot.workbench.domain.MaterialChunk;
import com.knowledgemeltingpot.workbench.domain.ModelConnection;
import com.knowledgemeltingpot.workbench.domain.ModelConnectionValidationStatus;
import com.knowledgemeltingpot.workbench.domain.ModelProvider;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HttpEmbeddingPortTest {
    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

    @Test
    void embedsChineseChunksThroughOpenAiCompatibleEndpointAndNormalizesVectors() throws Exception {
        Fixture fixture = fixture(ModelProvider.OPENAI_COMPATIBLE, "https://api.example.com/v1");
        HttpResponse<InputStream> response = jsonResponse(200, """
                {"data":[{"index":0,"embedding":[3.0,4.0]}]}
                """);
        when(fixture.client.send(any(HttpRequest.class), anyInputStreamHandler()))
                .thenReturn(response);
        MaterialChunk chunk = MaterialChunk.fromParsed(UUID.randomUUID(), UUID.randomUUID(), 0,
                "SRC-chinese-0", new ChunkLocator(ChunkLocator.LocatorType.TXT_LINES,
                        null, null, null, null, null, null, null, null, 1, 1),
                "逾期超过三十天时进入重点复核。", "1", NOW);

        var result = fixture.port.embed(List.of(chunk), fixture.profile);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().vector()).containsExactly(0.6f, 0.8f);
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(fixture.client).send(request.capture(), anyInputStreamHandler());
        assertThat(request.getValue().uri()).isEqualTo(URI.create("https://api.example.com/v1/embeddings"));
        assertThat(request.getValue().headers().firstValue("Authorization")).contains("Bearer embedding-key");
        assertThat(fixture.cleartext).containsOnly('\0');
    }

    @Test
    void usesNativeDashScopeEndpointAndQueryIndex() throws Exception {
        Fixture fixture = fixture(ModelProvider.DASHSCOPE, "https://api.example.com/api/v1");
        HttpResponse<InputStream> response = jsonResponse(200, """
                {"output":{"embeddings":[{"text_index":0,"embedding":[0.0,2.0]}]}}
                """);
        when(fixture.client.send(any(HttpRequest.class), anyInputStreamHandler()))
                .thenReturn(response);

        List<Float> vector = fixture.port.embedQuery("如何判断风险等级？", fixture.profile);

        assertThat(vector).containsExactly(0f, 1f);
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(fixture.client).send(request.capture(), anyInputStreamHandler());
        assertThat(request.getValue().uri()).isEqualTo(URI.create(
                "https://api.example.com/api/v1/services/embeddings/text-embedding/text-embedding"));
    }

    @Test
    void rejectsProviderErrorsWithoutExposingResponseBody() throws Exception {
        Fixture fixture = fixture(ModelProvider.OPENAI_COMPATIBLE, "https://api.example.com/v1");
        HttpResponse<InputStream> response = jsonResponse(401, "{\"error\":\"secret upstream detail\"}");
        when(fixture.client.send(any(HttpRequest.class), anyInputStreamHandler()))
                .thenReturn(response);

        assertThatThrownBy(() -> fixture.port.embedQuery("查询", fixture.profile))
                .isInstanceOfSatisfying(EmbeddingProviderException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("EMBEDDING_AUTHENTICATION_FAILED");
                    assertThat(exception.getMessage()).doesNotContain("secret upstream detail", "embedding-key");
                });
    }

    private static Fixture fixture(ModelProvider provider, String baseUrl) throws Exception {
        UUID connectionId = UUID.randomUUID();
        CredentialEnvelope envelope = new CredentialEnvelope("kmp1.test");
        ModelConnection connection = new ModelConnection(connectionId, "embedding", provider,
                URI.create(baseUrl), Optional.of(envelope), true,
                ModelConnectionValidationStatus.CONNECTIVITY_VERIFIED, NOW, UUID.randomUUID(), NOW, NOW);
        EmbeddingProfileVersion profile = new EmbeddingProfileVersion(UUID.randomUUID(), connectionId,
                provider.name(), "text-embedding-v4", 2, "v1", "L2", "COSINE", true, NOW);
        ModelConnectionRepository models = mock(ModelConnectionRepository.class);
        when(models.findConnection(connectionId)).thenReturn(Optional.of(connection));
        CredentialCipher cipher = mock(CredentialCipher.class);
        char[] cleartext = "embedding-key".toCharArray();
        when(cipher.unseal(connectionId, envelope)).thenReturn(cleartext);
        HttpClient client = mock(HttpClient.class);
        ModelEndpointPolicy policy = new ModelEndpointPolicy(Set.of("api.example.com"),
                host -> List.of(InetAddress.getByName("8.8.8.8")));
        HttpEmbeddingPort port = new HttpEmbeddingPort(provider, models, cipher, policy, client,
                JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(10), 1_048_576);
        return new Fixture(port, client, profile, cleartext);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse.BodyHandler<InputStream> anyInputStreamHandler() {
        return any(HttpResponse.BodyHandler.class);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<InputStream> jsonResponse(int status, String body) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(
                Map.of("Content-Type", List.of("application/json; charset=utf-8")), (name, value) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return response;
    }

    private record Fixture(HttpEmbeddingPort port, HttpClient client,
            EmbeddingProfileVersion profile, char[] cleartext) {
    }
}
