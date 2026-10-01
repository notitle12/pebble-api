package com.pebble.api.auth.infrastructure.naver;

import com.pebble.api.auth.infrastructure.redis.RefreshTokenProperties;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({NaverOAuthProperties.class, RefreshTokenProperties.class})
public class NaverOAuthConfiguration {

    @Bean
    RestClient naverRestClient(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return builder.requestFactory(factory).build();
    }
}
