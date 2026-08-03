package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.port.CredentialCipher;
import com.knowledgemeltingpot.workbench.application.port.ModelConnectionTestPort;
import com.knowledgemeltingpot.workbench.application.security.ModelEndpointPolicy;
import com.knowledgemeltingpot.workbench.application.service.PolicyOnlyModelConnectionTestPort;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
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
    ModelConnectionTestPort modelConnectionTestPort() {
        return new PolicyOnlyModelConnectionTestPort();
    }

    private static List<InetAddress> resolveAll(String host) throws UnknownHostException {
        return List.of(InetAddress.getAllByName(host));
    }
}
