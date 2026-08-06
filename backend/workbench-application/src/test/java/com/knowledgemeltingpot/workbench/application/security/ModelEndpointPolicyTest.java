package com.knowledgemeltingpot.workbench.application.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgemeltingpot.workbench.domain.ModelEndpointRule;
import java.net.InetAddress;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelEndpointPolicyTest {

    @Test
    void acceptsOnlyHttpsExactAllowlistedPublicHosts() throws Exception {
        ModelEndpointPolicy policy = new ModelEndpointPolicy(Set.of("api.example.com"),
                host -> List.of(InetAddress.getByName("8.8.8.8")));

        ValidatedModelEndpoint endpoint = policy.validate("https://API.EXAMPLE.COM/v1");

        assertThat(endpoint.uri()).isEqualTo(URI.create("https://api.example.com/v1"));
        assertThatThrownBy(() -> policy.validate("http://api.example.com/v1"))
                .hasMessageContaining("HTTP");
        assertThatThrownBy(() -> policy.validate("https://child.api.example.com/v1"))
                .hasMessageContaining("可信列表");
        assertThatThrownBy(() -> policy.validate("https://api.example.com/v1?token=value"))
                .hasMessageContaining("query");
        assertThatThrownBy(() -> policy.validate("https://api.example.com/v1/../admin"))
                .hasMessageContaining("canonical");
        assertThatThrownBy(() -> policy.validate("https://api.example.com/%2e%2e/admin"))
                .hasMessageContaining("encoded");
    }

    @Test
    void rejectsEveryResolvedPrivateOrSpecialAddress() throws Exception {
        for (String address : List.of("0.0.0.0", "127.0.0.1", "10.0.0.1", "172.16.0.1",
                "192.168.1.1", "169.254.1.1", "100.64.1.1", "224.0.0.1", "::", "::1", "fe80::1",
                "fd00::1", "ff02::1", "192.0.2.1", "198.51.100.1", "203.0.113.1", "2001:db8::1")) {
            ModelEndpointPolicy policy = new ModelEndpointPolicy(Set.of("api.example.com"),
                    host -> List.of(InetAddress.getByName(address)));

            assertThatThrownBy(() -> policy.validate("https://api.example.com/v1"))
                    .as("address %s", address)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsAHostWhenAnyDnsAnswerIsPrivate() throws Exception {
        ModelEndpointPolicy policy = new ModelEndpointPolicy(Set.of("api.example.com"), host -> List.of(
                InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")));

        assertThatThrownBy(() -> policy.validate("https://api.example.com/v1"))
                .hasMessageContaining("禁止访问");
    }

    @Test
    void requiresExplicitWhitelistEntries() {
        assertThatThrownBy(() -> new ModelEndpointPolicy(Set.of(), host -> List.of()))
                .hasMessageContaining("whitelist");
        assertThatThrownBy(() -> new ModelEndpointPolicy(Set.of("*.example.com"), host -> List.of()))
                .hasMessageContaining("精确");
    }

    @Test
    void acceptsExplicitlyConfiguredPrivateHttpEndpointButStillRejectsLoopback() throws Exception {
        UUID actorId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        ModelEndpointRule rule = new ModelEndpointRule(UUID.randomUUID(), "llm.bank.local", Set.of(8000),
                true, true, actorId, actorId, now, now);
        ModelEndpointPolicy privatePolicy = new ModelEndpointPolicy(Set.of(),
                host -> host.equals(rule.host()) ? Optional.of(rule) : Optional.empty(),
                host -> List.of(InetAddress.getByName("10.20.30.40")));

        assertThat(privatePolicy.validate("http://llm.bank.local:8000/v1").uri())
                .isEqualTo(URI.create("http://llm.bank.local:8000/v1"));
        assertThatThrownBy(() -> privatePolicy.validate("http://llm.bank.local:9000/v1"))
                .hasMessageContaining("端口 9000");

        ModelEndpointPolicy loopbackPolicy = new ModelEndpointPolicy(Set.of(), host -> Optional.of(rule),
                host -> List.of(InetAddress.getByName("127.0.0.1")));
        assertThatThrownBy(() -> loopbackPolicy.validate("http://llm.bank.local:8000/v1"))
                .hasMessageContaining("禁止访问");
    }
}
