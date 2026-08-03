package com.knowledgemeltingpot.workbench.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class SessionConfigurationTest {
    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    @Test
    void defaultRuntimeUsesFlywayManagedJdbcSessions() throws IOException {
        var properties = loader.load("application", new ClassPathResource("application.yaml")).getFirst();

        assertThat(properties.getProperty("spring.session.jdbc.initialize-schema")).isEqualTo("never");
        assertThat(properties.getProperty("server.servlet.session.cookie.name")).isEqualTo("KMP_SESSION");
        assertThat(properties.getProperty("server.servlet.session.cookie.http-only")).isEqualTo(true);
    }

    @Test
    void runtimeFailsClosedUnlessJdbcSessionRepositoryExists() {
        assertThat(Arrays.stream(JdbcSessionPersistenceGuard.class.getConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes())))
                .containsExactly(org.springframework.session.jdbc.JdbcIndexedSessionRepository.class);
    }

    @Test
    void productionProfileDefaultsSecureCookiesToTrue() throws IOException {
        var properties = loader.load("application-prod", new ClassPathResource("application-prod.yaml")).getFirst();

        assertThat(properties.getProperty("server.servlet.session.cookie.secure").toString()).contains(":true}");
        assertThat(properties.getProperty("workbench.security.secure-cookies").toString()).contains(":true}");
    }
}
