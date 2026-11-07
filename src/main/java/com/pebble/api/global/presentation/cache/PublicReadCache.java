package com.pebble.api.global.presentation.cache;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 같은 primary DB의 가시성이 유지되는 동안만 공개 JSON 계산 결과를 재사용한다. */
@Component
public class PublicReadCache {
    static final int MAX_ENTRIES = 128;
    static final int MAX_BODY_BYTES = 128 * 1024;
    static final long TTL_NANOS = Duration.ofSeconds(10).toNanos();
    private final JdbcTemplate jdbc;
    private final LongSupplier clock;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

    @Autowired
    public PublicReadCache(JdbcTemplate jdbc) { this(jdbc, System::nanoTime); }
    PublicReadCache(JdbcTemplate jdbc, LongSupplier clock) { this.jdbc = jdbc; this.clock = clock; }

    public Optional<String> snapshot() {
        // 호출자 트랜잭션의 미커밋 데이터를 다른 요청과 공유하지 않는다.
        if (TransactionSynchronizationManager.isActualTransactionActive()) return Optional.empty();
        try {
            String value = jdbc.queryForObject("select pg_current_snapshot()::text", String.class);
            return value != null && value.length() <= 4096 ? Optional.of(value) : Optional.empty();
        } catch (DataAccessException unavailable) {
            clear();
            return Optional.empty();
        }
    }

    synchronized Entry get(String key) {
        Entry entry = entries.get(key);
        if (entry != null && clock.getAsLong() - entry.storedAt() >= TTL_NANOS) {
            entries.remove(key);
            return null;
        }
        return entry;
    }

    synchronized void put(String key, byte[] body, String contentType) {
        if (body.length == 0 || body.length > MAX_BODY_BYTES) return;
        entries.put(key, new Entry(body.clone(), contentType, clock.getAsLong()));
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
    }

    synchronized void clear() { entries.clear(); }
    record Entry(byte[] body, String contentType, long storedAt) { }
}
