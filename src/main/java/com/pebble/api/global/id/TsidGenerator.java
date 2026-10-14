package com.pebble.api.global.id;

import com.github.f4b6a3.tsid.TsidCreator;

public final class TsidGenerator {

    private TsidGenerator() {
    }

    public static long generate() {
        return TsidCreator.getTsid().toLong();
    }
}
