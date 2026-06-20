package com.crosscert.passkey.core.config;

import java.time.ZoneId;
import java.time.ZoneOffset;

/** 프로젝트 단일 기준 타임존(KST). 한국 전용·서머타임 없음 → 고정 +09:00. */
public final class KstTime {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    public static final ZoneOffset OFFSET = ZoneOffset.ofHours(9);
    private KstTime() {}
}
