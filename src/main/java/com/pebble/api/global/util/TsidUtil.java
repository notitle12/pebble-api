package com.pebble.api.global.util;

import com.github.f4b6a3.tsid.Tsid;
import com.github.f4b6a3.tsid.TsidCreator;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TsidUtil {

    public static Long nextId() {
        return TsidCreator.getTsid().toLong();
    }

    public static String nextStringId() {
        return TsidCreator.getTsid().toString();
    }

    public static Long toLong(String tsidString) {
        if (tsidString == null || tsidString.isBlank()) {
            return null;
        }
        return Tsid.from(tsidString).toLong();
    }

    public static String toString(Long tsidLong) {
        if (tsidLong == null) {
            return null;
        }
        return Tsid.from(tsidLong).toString();
    }
}
