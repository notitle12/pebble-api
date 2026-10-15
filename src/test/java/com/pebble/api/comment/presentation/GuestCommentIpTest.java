package com.pebble.api.comment.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pebble.api.comment.domain.CommentError;
import com.pebble.api.global.exception.ApplicationException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class GuestCommentIpTest {
    private final GuestCommentIp resolver = new GuestCommentIp("10.20.1.5,2001:db8:1234::5");

    @Test
    void ignoresSpoofedIpHeadersFromAnUntrustedPeer() {
        var request = request("198.51.100.17");
        request.addHeader("X-Real-IP", "203.0.113.9");
        request.addHeader("X-Forwarded-For", "203.0.113.10, 203.0.113.11");
        request.addHeader("CF-Connecting-IP", "203.0.113.12");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.17");
    }

    @Test
    void trustsOneValidIpv4RealIpFromAnExactProxyAddress() {
        var request = request("10.20.1.5");
        request.addHeader("X-Real-IP", "192.0.2.34");

        assertThat(resolver.resolve(request)).isEqualTo("192.0.2.34");
    }

    @Test
    void trustsOneValidIpv6RealIpFromAnExactProxyAddress() {
        var request = request("2001:db8:1234::5");
        request.addHeader("X-Real-IP", "2001:db8::42");

        assertThat(resolver.resolve(request)).isEqualTo("2001:db8:0:0:0:0:0:42");
    }

    @Test
    void failsClosedWhenTrustedProxyOmitsRealIp() {
        assertUnavailable(request("10.20.1.5"));
    }

    @Test
    void failsClosedWhenTrustedProxySendsMultipleOrListRealIps() {
        var multiple = request("10.20.1.5");
        multiple.addHeader("X-Real-IP", "192.0.2.1");
        multiple.addHeader("X-Real-IP", "192.0.2.2");
        assertUnavailable(multiple);

        var list = request("10.20.1.5");
        list.addHeader("X-Real-IP", "192.0.2.1, 192.0.2.2");
        assertUnavailable(list);
    }

    @Test
    void failsClosedForMalformedTrustedRealIpValues() {
        for (String value : new String[]{"", " 192.0.2.1", "example.com", "192.0.2.1:443",
                "999.0.0.1", "2001:db8::1,2001:db8::2"}) {
            var request = request("10.20.1.5");
            request.addHeader("X-Real-IP", value);
            assertUnavailable(request);
        }
    }

    @Test
    void rejectsDnsNamesForAnUntrustedPeerAddress() {
        var request = request("localhost");
        request.addHeader("X-Real-IP", "192.0.2.34");
        request.addHeader("X-Forwarded-For", "192.0.2.35");

        assertUnavailable(request);
    }

    private MockHttpServletRequest request(String remoteAddress) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        return request;
    }

    private void assertUnavailable(MockHttpServletRequest request) {
        assertThatThrownBy(() -> resolver.resolve(request))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.error()).isEqualTo(
                                CommentError.COMMENT_SECURITY_UNAVAILABLE));
    }
}
