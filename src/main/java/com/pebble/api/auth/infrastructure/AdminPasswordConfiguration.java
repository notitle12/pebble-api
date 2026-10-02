package com.pebble.api.auth.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AdminPasswordConfiguration {
    @Bean
    PasswordEncoder adminPasswordEncoder() {
        // Argon2id: salt 16바이트, hash 32바이트, 병렬도 1, 메모리 19MiB, 반복 2회.
        return new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
    }
}
