package com.pebble.api.global.config;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class JpaPostgresConnectionTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("PostgreSQL DataSource가 정상 연결되고 데이터베이스명이 PostgreSQL이다")
    void dataSource_connection_success() throws SQLException {
        assertThat(dataSource).isNotNull();

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            assertThat(metaData.getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
    }

    @Test
    @DisplayName("JPA EntityManager를 통해 PostgreSQL 쿼리가 정상 실행된다")
    void jpa_query_execution_success() {
        assertThat(entityManager).isNotNull();

        Object result = entityManager.createNativeQuery("SELECT 1").getSingleResult();
        assertThat(result).isNotNull();
        assertThat(String.valueOf(result)).isEqualTo("1");
    }
}
