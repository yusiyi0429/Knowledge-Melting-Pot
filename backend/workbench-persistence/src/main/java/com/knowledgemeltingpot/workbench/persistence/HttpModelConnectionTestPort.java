package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.port.CredentialCipher;
import com.knowledgemeltingpot.workbench.application.port.ModelConnectionTestPort;
import com.knowledgemeltingpot.workbench.application.port.ModelConnectionTestResult;
import com.knowledgemeltingpot.workbench.application.security.ModelEndpointPolicy;
import com.knowledgemeltingpot.workbench.application.security.ValidatedModelEndpoint;
import com.knowledgemeltingpot.workbench.domain.ModelConnection;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import javax.net.ssl.SSLHandshakeException;

/**
 * Performs a read-only, authenticated Provider probe. Redirects are handled
 * explicitly so every target is checked against the endpoint policy before a
 * credential can be sent.
 */
public final class HttpModelConnectionTestPort implements ModelConnectionTestPort {
    private static final String CONNECTED = "CONNECTED";
    private static final String FAILED = "FAILED";

    private final CredentialCipher credentialCipher;
    private final ModelEndpointPolicy endpointPolicy;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final int maxRedirects;

    public HttpModelConnectionTestPort(CredentialCipher credentialCipher, ModelEndpointPolicy endpointPolicy,
            HttpClient httpClient, Duration requestTimeout, int maxRedirects) {
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("model connection test timeout must be positive");
        }
        if (maxRedirects < 0 || maxRedirects > 5) {
            throw new IllegalArgumentException("model connection test max redirects must be between 0 and 5");
        }
        this.credentialCipher = credentialCipher;
        this.endpointPolicy = endpointPolicy;
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
        this.maxRedirects = maxRedirects;
    }

    @Override
    public ModelConnectionTestResult test(ModelConnection connection, ValidatedModelEndpoint endpoint,
            Instant testedAt) {
        Optional<char[]> unsealed = connection.credentialEnvelope()
                .map(envelope -> credentialCipher.unseal(connection.id(), envelope));
        if (unsealed.isEmpty()) {
            return failed(false, false, "model.connection.credential-missing", testedAt);
        }

        char[] credential = unsealed.orElseThrow();
        try {
            return executeProbe(connection.provider(), probeUri(connection.provider(), endpoint.uri()),
                    new String(credential), testedAt);
        } finally {
            Arrays.fill(credential, '\0');
        }
    }

    private ModelConnectionTestResult executeProbe(ModelProvider provider, URI initialUri, String credential,
            Instant testedAt) {
        URI current = initialUri;
        for (int redirects = 0; redirects <= maxRedirects; redirects++) {
            final ValidatedModelEndpoint validated;
            try {
                validated = endpointPolicy.validate(current.toString());
            } catch (IllegalArgumentException exception) {
                return failed(redirects > 0, true, "model.connection.redirect-rejected", testedAt);
            }

            HttpRequest request = HttpRequest.newBuilder(validated.uri())
                    .timeout(requestTimeout)
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + credential)
                    .header("User-Agent", "Knowledge-Melting-Pot/model-connection-test")
                    .GET()
                    .build();
            final HttpResponse<InputStream> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (HttpTimeoutException exception) {
                return failed(true, true, "model.connection.timeout", testedAt);
            } catch (SSLHandshakeException exception) {
                return failed(true, true, "model.connection.tls-failed", testedAt);
            } catch (ConnectException exception) {
                return failed(true, true, "model.connection.unreachable", testedAt);
            } catch (IOException exception) {
                return failed(true, true, "model.connection.network-failed", testedAt);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return failed(true, true, "model.connection.interrupted", testedAt);
            }

            try (InputStream ignored = response.body()) {
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    boolean json = response.headers().firstValue("Content-Type")
                            .map(value -> value.toLowerCase(java.util.Locale.ROOT)
                                    .startsWith("application/json"))
                            .orElse(false);
                    if (json) {
                        return new ModelConnectionTestResult(CONNECTED, true, true, true,
                                "model.connection.verified", testedAt);
                    }
                    return failed(true, true, "model.connection.unexpected-response", testedAt);
                }
                if (isRedirect(status)) {
                    if (redirects == maxRedirects) {
                        return failed(true, true, "model.connection.too-many-redirects", testedAt);
                    }
                    Optional<String> location = response.headers().firstValue("Location");
                    if (location.isEmpty()) {
                        return failed(true, true, "model.connection.redirect-rejected", testedAt);
                    }
                    URI target;
                    try {
                        target = validated.uri().resolve(location.orElseThrow());
                    } catch (IllegalArgumentException exception) {
                        return failed(true, true, "model.connection.redirect-rejected", testedAt);
                    }
                    if (!sameAuthority(initialUri, target)) {
                        return failed(true, true, "model.connection.redirect-rejected", testedAt);
                    }
                    current = target;
                    continue;
                }
                return failed(true, true, messageForStatus(provider, status), testedAt);
            } catch (IOException exception) {
                return failed(true, true, "model.connection.network-failed", testedAt);
            }
        }
        return failed(true, true, "model.connection.too-many-redirects", testedAt);
    }

    private static URI probeUri(ModelProvider provider, URI baseUri) {
        String basePath = baseUri.getPath() == null ? "" : baseUri.getPath();
        boolean dashScopeNative = provider == ModelProvider.DASHSCOPE
                && !basePath.contains("/compatible-mode/");
        String resource = dashScopeNative ? "files" : "models";
        String path = (basePath.endsWith("/") ? basePath : basePath + "/") + resource;
        try {
            return new URI(baseUri.getScheme(), null, baseUri.getHost(), baseUri.getPort(), path, null, null);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("model Provider probe URL could not be constructed", exception);
        }
    }

    private static String messageForStatus(ModelProvider provider, int status) {
        if (status == 401 || status == 403) {
            return "model.connection.authentication-failed";
        }
        if (status == 404) {
            return provider == ModelProvider.DASHSCOPE
                    ? "model.connection.dashscope-endpoint-not-found"
                    : "model.connection.openai-endpoint-not-found";
        }
        if (status == 429) {
            return "model.connection.rate-limited";
        }
        if (status >= 500) {
            return "model.connection.provider-unavailable";
        }
        return "model.connection.unexpected-response";
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean sameAuthority(URI expected, URI actual) {
        return "https".equalsIgnoreCase(actual.getScheme())
                && expected.getHost().equalsIgnoreCase(actual.getHost())
                && effectivePort(expected) == effectivePort(actual);
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() < 0 ? 443 : uri.getPort();
    }

    private static ModelConnectionTestResult failed(boolean networkAttempted, boolean credentialConfigured,
            String messageCode, Instant testedAt) {
        return new ModelConnectionTestResult(FAILED, networkAttempted, false, credentialConfigured,
                messageCode, testedAt);
    }
}
