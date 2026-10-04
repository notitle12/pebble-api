package com.pebble.api.global.media;

import java.net.URI;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@EnableConfigurationProperties({R2Properties.class, MediaCdnProperties.class})
@ConditionalOnProperty(prefix = "pebble.media.r2", name = "enabled", havingValue = "true")
public class R2MediaConfiguration {
    private static final Pattern BUCKET_NAME = Pattern.compile("(?=.{3,63}$)[a-z0-9](?:[a-z0-9-]*[a-z0-9])?");

    @Bean(destroyMethod = "close")
    S3Client r2S3Client(R2Properties properties) {
        validate(properties);
        return S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(3)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(6))
                        .apiCallAttemptTimeout(Duration.ofSeconds(3))
                        .build())
                .build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner r2S3Presigner(R2Properties properties) {
        validate(properties);
        return S3Presigner.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Bean
    R2ObjectStorage r2ObjectStorage(S3Client client, S3Presigner presigner, R2Properties properties, MediaCdnProperties cdn) {
        return new S3R2ObjectStorage(client, presigner, properties.bucketName(),
                cdn.enabled() ? new MediaCdnSigner(cdn, java.time.Clock.systemUTC()) : null);
    }

    private StaticCredentialsProvider credentials(R2Properties properties) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.accessKeyId(), properties.secretAccessKey()));
    }

    void validate(R2Properties properties) {
        if (properties == null || blank(properties.endpoint()) || blank(properties.accessKeyId())
                || blank(properties.secretAccessKey()) || blank(properties.bucketName()) || blank(properties.region())) {
            throw new IllegalStateException("R2 is enabled but required configuration is missing");
        }
        try {
            URI endpoint = URI.create(properties.endpoint());
            if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                    || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
                throw new IllegalStateException("R2 endpoint must be an HTTPS URL without credentials, query, or fragment");
            }
            if (endpoint.getRawPath() != null && !endpoint.getRawPath().isEmpty() && !"/".equals(endpoint.getRawPath())) {
                throw new IllegalStateException("R2 endpoint must not include a path");
            }
            if (!"auto".equals(properties.region())) {
                throw new IllegalStateException("R2 region must be auto");
            }
            if (!BUCKET_NAME.matcher(properties.bucketName()).matches()) {
                throw new IllegalStateException("R2 bucket name is invalid");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("R2 endpoint or region configuration is invalid");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
