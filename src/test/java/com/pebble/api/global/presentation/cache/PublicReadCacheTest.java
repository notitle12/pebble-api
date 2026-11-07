package com.pebble.api.global.presentation.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PublicReadCacheTest {
    @Test
    void expiresAndBoundsMemory() {
        var clock = new AtomicLong();
        var cache = new PublicReadCache(mock(JdbcTemplate.class), clock::get);
        byte[] body = "{\"data\":1}".getBytes(StandardCharsets.UTF_8);
        cache.put("old", body, "application/json");
        body[0] = 0;
        assertThat(cache.get("old").body()[0]).isEqualTo((byte)'{');
        clock.set(PublicReadCache.TTL_NANOS);
        assertThat(cache.get("old")).isNull();
        for (int i = 0; i <= PublicReadCache.MAX_ENTRIES; i++) cache.put("key"+i, new byte[]{1}, "application/json");
        assertThat(cache.get("key0")).isNull();
        assertThat(cache.get("key128")).isNotNull();
        cache.put("large", new byte[PublicReadCache.MAX_BODY_BYTES+1], "application/json");
        assertThat(cache.get("large")).isNull();
    }

    @Test
    void bypassesUncommittedCallerTransactionsAndDatabaseFailures() {
        var jdbc = mock(JdbcTemplate.class);
        var cache = new PublicReadCache(jdbc);
        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThat(cache.snapshot()).isEmpty();
            verifyNoInteractions(jdbc);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        cache.put("existing", new byte[]{1}, "application/json");
        when(jdbc.queryForObject("select pg_current_snapshot()::text", String.class))
                .thenThrow(new DataAccessResourceFailureException("test unavailable"));
        assertThat(cache.snapshot()).isEmpty();
        assertThat(cache.get("existing")).isNull();
    }
}
