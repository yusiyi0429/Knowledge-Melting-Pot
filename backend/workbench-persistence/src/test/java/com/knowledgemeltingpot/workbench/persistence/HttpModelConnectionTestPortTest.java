package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.application.port.CredentialCipher;
import com.knowledgemeltingpot.workbench.application.port.ModelConnectionTestResult;
import com.knowledgemeltingpot.workbench.application.security.ModelEndpointPolicy;
import com.knowledgemeltingpot.workbench.domain.CredentialEnvelope;
import com.knowledgemeltingpot.workbench.domain.ModelConnection;
import com.knowledgemeltingpot.workbench.domain.ModelConnectionValidationStatus;
import com.knowledgemeltingpot.workbench.domain.ModelProvider;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HttpModelConnectionTestPortTest {
    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");
    private CredentialCipher cipher;
    private HttpClient client;
    private ModelEndpointPolicy policy;

    @BeforeEach
    void setUp() throws Exception {
        cipher = mock(CredentialCipher.class);
        client = mock(HttpClient.class);
        policy = new ModelEndpointPolicy(Set.of("api.example.com"),
                host -> List.of(InetAddress.getByName("8.8.8.8")));
    }

    @Test
    void verifiesOpenAiCompatibleProviderWithAuthenticatedModelsProbe() throws Exception {
        ModelConnection connection = connection(ModelProvider.OPENAI_COMPATIBLE,
                "https://api.example.com/v1", true);
        char[] unsealed = "provider-test-key".toCharArray();
        when(cipher.unseal(connection.id(), connection.credentialEnvelope().orElseThrow())).thenReturn(unsealed);
        HttpResponse<InputStream> successful = jsonResponse(200);
        when(client.send(any(HttpRequest.class), anyInputStreamHandler())).thenReturn(successful);
        HttpModelConnectionTestPort adapter = adapter();

        ModelConnectionTestResult result = adapter.test(connection,
                policy.validate(connection.baseUrl().toString()), NOW);

        assertThat(result.status()).isEqualTo("CONNECTED");
        assertThat(result.networkAttempted()).isTrue();
        assertThat(result.connectivityVerified()).isTrue();
        assertThat(result.messageCode()).isEqualTo("model.connection.verified");
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), anyInputStreamHandler());
        assertThat(request.getValue().uri()).isEqualTo(URI.create("https://api.example.com/v1/models"));
        assertThat(request.getValue().headers().firstValue("Authorization"))
                .contains("Bearer provider-test-key");
        assertThat(unsealed).containsOnly('\0');
    }

    @Test
    void usesReadOnlyFilesProbeForNativeDashScope() throws Exception {
        ModelConnection connection = connection(ModelProvider.DASHSCOPE,
                "https://api.example.com/api/v1", true);
        when(cipher.unseal(connection.id(), connection.credentialEnvelope().orElseThrow()))
                .thenReturn("dashscope-key".toCharArray());
        HttpResponse<InputStream> successful = jsonResponse(200);
        when(client.send(any(HttpRequest.class), anyInputStreamHandler())).thenReturn(successful);

        adapter().test(connection, policy.validate(connection.baseUrl().toString()), NOW);

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), anyInputStreamHandler());
        assertThat(request.getValue().uri()).isEqualTo(URI.create("https://api.example.com/api/v1/files"));
    }

    @Test
    void followsOnlySameAuthorityRedirectAfterPolicyRevalidation() throws Exception {
        ModelConnection connection = connection(ModelProvider.OPENAI_COMPATIBLE,
                "https://api.example.com/v1", true);
        when(cipher.unseal(connection.id(), connection.credentialEnvelope().orElseThrow()))
                .thenReturn("redirect-key".toCharArray());
        HttpResponse<InputStream> redirected = response(307, Map.of("Location", List.of("/v1/catalog")));
        HttpResponse<InputStream> successful = jsonResponse(200);
        when(client.send(any(HttpRequest.class), anyInputStreamHandler()))
                .thenReturn(redirected)
                .thenReturn(successful);

        ModelConnectionTestResult result = adapter().test(connection,
                policy.validate(connection.baseUrl().toString()), NOW);

        assertThat(result.connectivityVerified()).isTrue();
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, org.mockito.Mockito.times(2)).send(requests.capture(), anyInputStreamHandler());
        assertThat(requests.getAllValues()).extracting(HttpRequest::uri)
                .containsExactly(URI.create("https://api.example.com/v1/models"),
                        URI.create("https://api.example.com/v1/catalog"));
    }

    @Test
    void rejectsCrossHostRedirectBeforeForwardingCredential() throws Exception {
        ModelConnection connection = connection(ModelProvider.OPENAI_COMPATIBLE,
                "https://api.example.com/v1", true);
        when(cipher.unseal(connection.id(), connection.credentialEnvelope().orElseThrow()))
                .thenReturn("redirect-key".toCharArray());
        HttpResponse<InputStream> redirected = response(302,
                Map.of("Location", List.of("https://attacker.example/v1/models")));
        when(client.send(any(HttpRequest.class), anyInputStreamHandler()))
                .thenReturn(redirected);

        ModelConnectionTestResult result = adapter().test(connection,
                policy.validate(connection.baseUrl().toString()), NOW);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.messageCode()).isEqualTo("model.connection.redirect-rejected");
        verify(client).send(any(HttpRequest.class), anyInputStreamHandler());
    }

    @Test
    void mapsAuthenticationAndTimeoutFailuresWithoutProviderPayloads() throws Exception {
        ModelConnection authentication = connection(ModelProvider.OPENAI_COMPATIBLE,
                "https://api.example.com/v1", true);
        when(cipher.unseal(authentication.id(), authentication.credentialEnvelope().orElseThrow()))
                .thenReturn("bad-key".toCharArray());
        HttpResponse<InputStream> unauthorized = response(401, Map.of());
        when(client.send(any(HttpRequest.class), anyInputStreamHandler())).thenReturn(unauthorized);

        ModelConnectionTestResult denied = adapter().test(authentication,
                policy.validate(authentication.baseUrl().toString()), NOW);

        assertThat(denied.status()).isEqualTo("FAILED");
        assertThat(denied.networkAttempted()).isTrue();
        assertThat(denied.messageCode()).isEqualTo("model.connection.authentication-failed");

        HttpClient timeoutClient = mock(HttpClient.class);
        when(timeoutClient.send(any(HttpRequest.class), anyInputStreamHandler()))
                .thenThrow(new HttpTimeoutException("sensitive upstream detail"));
        when(cipher.unseal(authentication.id(), authentication.credentialEnvelope().orElseThrow()))
                .thenReturn("timeout-key".toCharArray());
        HttpModelConnectionTestPort timeoutAdapter = new HttpModelConnectionTestPort(cipher, policy,
                timeoutClient, Duration.ofSeconds(10), 2);

        ModelConnectionTestResult timedOut = timeoutAdapter.test(authentication,
                policy.validate(authentication.baseUrl().toString()), NOW);

        assertThat(timedOut.messageCode()).isEqualTo("model.connection.timeout");
        assertThat(timedOut.toString()).doesNotContain("sensitive upstream detail", "timeout-key");
    }

    @Test
    void missingCredentialDoesNotAttemptNetwork() throws Exception {
        ModelConnection connection = connection(ModelProvider.OPENAI_COMPATIBLE,
                "https://api.example.com/v1", false);

        ModelConnectionTestResult result = adapter().test(connection,
                policy.validate(connection.baseUrl().toString()), NOW);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.networkAttempted()).isFalse();
        assertThat(result.credentialConfigured()).isFalse();
        assertThat(result.messageCode()).isEqualTo("model.connection.credential-missing");
        verify(client, never()).send(any(HttpRequest.class), anyInputStreamHandler());
    }

    private HttpModelConnectionTestPort adapter() {
        return new HttpModelConnectionTestPort(cipher, policy, client, Duration.ofSeconds(10), 2);
    }

    private static ModelConnection connection(ModelProvider provider, String baseUrl, boolean credential) {
        UUID id = UUID.randomUUID();
        return new ModelConnection(id, "test connection", provider, URI.create(baseUrl),
                credential ? Optional.of(new CredentialEnvelope("kmp1.test-envelope")) : Optional.empty(),
                true, ModelConnectionValidationStatus.UNTESTED, null, UUID.randomUUID(), NOW, NOW);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse.BodyHandler<InputStream> anyInputStreamHandler() {
        return any(HttpResponse.BodyHandler.class);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<InputStream> response(int status, Map<String, List<String>> headers) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(InputStream.nullInputStream());
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        return response;
    }

    private static HttpResponse<InputStream> jsonResponse(int status) {
        return response(status, Map.of("Content-Type", List.of("application/json; charset=utf-8")));
    }
}
