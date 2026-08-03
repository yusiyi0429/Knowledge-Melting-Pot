package com.knowledgemeltingpot.workbench.objectstorage;

import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ObjectStorageProperties.class)
@ConditionalOnProperty(prefix = "workbench.object-storage", name = "enabled", havingValue = "true")
public class ObjectStorageAdapterConfiguration {

    @Bean
    S3Client s3Client(ObjectStorageProperties properties) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.internalEndpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyle())
                        .build())
                .build();
    }

    @Bean
    S3Presigner internalS3Presigner(ObjectStorageProperties properties) {
        return presigner(properties.internalEndpoint(), properties);
    }

    @Bean
    S3Presigner publicS3Presigner(ObjectStorageProperties properties) {
        return presigner(properties.resolvedPublicEndpoint(), properties);
    }

    @Bean
    S3ObjectStorageAdapter objectStorageAdapter(S3Client client, S3Presigner internalS3Presigner,
            S3Presigner publicS3Presigner, ObjectStorageProperties properties) {
        return new S3ObjectStorageAdapter(client, internalS3Presigner, publicS3Presigner, properties);
    }

    private static S3Presigner presigner(String endpoint, ObjectStorageProperties properties) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyle())
                        .build())
                .build();
    }

    private static StaticCredentialsProvider credentials(ObjectStorageProperties properties) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
    }
}
