package com.pebble.api.global.presentation.trace;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

/** 요청과 로그를 연결할 ULID traceId를 생성한다. */
public final class TraceIdGenerator {

    private static final char[] BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private TraceIdGenerator() {
    }

    public static String generate() {
        byte[] bytes = new byte[16];
        long timestamp = System.currentTimeMillis();
        for (int index = 5; index >= 0; index--) {
            bytes[index] = (byte) timestamp;
            timestamp >>>= 8;
        }
        byte[] randomness = new byte[10];
        SECURE_RANDOM.nextBytes(randomness);
        System.arraycopy(randomness, 0, bytes, 6, randomness.length);

        BigInteger value = new BigInteger(1, bytes);
        char[] encoded = new char[26];
        for (int index = encoded.length - 1; index >= 0; index--) {
            encoded[index] = BASE32[value.and(BigInteger.valueOf(31)).intValue()];
            value = value.shiftRight(5);
        }
        return new String(encoded);
    }
}
