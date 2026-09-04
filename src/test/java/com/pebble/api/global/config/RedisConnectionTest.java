package com.pebble.api.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RedisConnectionTest {

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Test
    @DisplayName("Redis 연결 팩토리가 정상적으로 동작하여 ping 응답을 받는다")
    void redis_connection_success() {
        assertThat(redisConnectionFactory).isNotNull();

        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            String pingResult = connection.ping();
            assertThat(pingResult).isEqualTo("PONG");
        }
    }

    @Test
    @DisplayName("RedisTemplate을 통한 데이터 저장, 조회, 삭제가 정상 동작한다")
    void redisTemplate_operations_success() {
        assertThat(redisTemplate).isNotNull();

        String testKey = "test:init:key";
        String testValue = "pebble-init-value";

        redisTemplate.opsForValue().set(testKey, testValue);

        Object retrieved = redisTemplate.opsForValue().get(testKey);
        assertThat(retrieved).isEqualTo(testValue);

        Boolean deleted = redisTemplate.delete(testKey);
        assertThat(deleted).isTrue();

        Object afterDelete = redisTemplate.opsForValue().get(testKey);
        assertThat(afterDelete).isNull();
    }
}
