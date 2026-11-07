package com.pebble.api.global.presentation.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.pebble.api.support.AuthenticationTestSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real servlet/security chain and separately committed DB writes; no test-wide transaction. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicReadCacheIntegrationTest extends AuthenticationTestSupport {
    private static final long CATEGORY_ID = 919191919191L;
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PublicReadCache cache;
    @Autowired PlatformTransactionManager transactions;
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void cleanup() {
        jdbc.update("delete from category where id=?", CATEGORY_ID);
        cache.clear();
    }

    @Test
    void realGetHitsAndCommittedEditsAndRemovalInvalidateImmediately() throws Exception {
        cache.clear();
        jdbc.update("""
                insert into category(id,name,slug,display_order,status,created_at,updated_at)
                values(?, 'cache-original','cache-integration',999,'ACTIVE',now(),now())
                """, CATEGORY_ID);
        assertThat(get(null).body()).contains("cache-original");
        assertThat(get(null).headers().firstValue("X-Pebble-Read-Cache")).contains("HIT");

        // The uncommitted writer must neither expose its data nor leave a reusable stale key after commit.
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("update category set name='cache-updated' where id=?", CATEGORY_ID);
            try { assertThat(get(null).body()).contains("cache-original").doesNotContain("cache-updated"); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        });
        var updated = get(null);
        assertThat(updated.body()).contains("cache-updated").doesNotContain("cache-original");
        assertThat(updated.headers().firstValue("X-Pebble-Read-Cache")).contains("MISS");
        assertThat(get(null).headers().firstValue("X-Pebble-Read-Cache")).contains("HIT");
        jdbc.update("update category set status='INACTIVE' where id=?", CATEGORY_ID);
        assertThat(get(null).body()).doesNotContain("cache-updated");
        jdbc.update("delete from category where id=?", CATEGORY_ID);
        assertThat(get(null).headers().firstValue("X-Pebble-Read-Cache")).contains("MISS");
    }

    @Test
    void warmCachePreservesSecurityAndRepresentationChecks() throws Exception {
        cache.clear();
        int unsupportedStatus = get("text/plain").statusCode();
        assertThat(unsupportedStatus).isGreaterThanOrEqualTo(400);
        get(null);
        assertThat(get(null).headers().firstValue("X-Pebble-Read-Cache")).contains("HIT");
        var unsupported = get("text/plain");
        assertThat(unsupported.statusCode()).isEqualTo(unsupportedStatus);
        assertThat(unsupported.headers().firstValue("X-Pebble-Read-Cache")).isEmpty();
        var request = HttpRequest.newBuilder(uri()).header("Authorization", "Bearer invalid").GET().build();
        var denied = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(401);
        assertThat(denied.headers().firstValue("X-Pebble-Read-Cache")).isEmpty();
        request = HttpRequest.newBuilder(uri()).header("Cookie", "session=example").GET().build();
        var cookie = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(cookie.statusCode()).isEqualTo(200);
        assertThat(cookie.headers().firstValue("X-Pebble-Read-Cache")).isEmpty();
    }

    private URI uri() { return URI.create("http://localhost:" + port + "/api/v1/categories"); }
    private HttpResponse<String> get(String accept) throws Exception {
        var request = HttpRequest.newBuilder(uri()).GET();
        if (accept != null) request.header("Accept", accept);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
