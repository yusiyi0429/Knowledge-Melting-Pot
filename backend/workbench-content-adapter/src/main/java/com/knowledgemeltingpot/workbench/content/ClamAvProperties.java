package com.knowledgemeltingpot.workbench.content;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "workbench.content.clamav")
public record ClamAvProperties(
        boolean enabled,
        String host,
        int port,
        Duration connectTimeout,
        Duration readTimeout,
        long maxStreamLength) {

    @ConstructorBinding
    public ClamAvProperties {
        if (enabled) {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("workbench.content.clamav.host is required");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("workbench.content.clamav.port is invalid");
            }
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(5);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(60);
        }
        if (maxStreamLength <= 0) {
            maxStreamLength = 209715200L;
        }
    }
}
