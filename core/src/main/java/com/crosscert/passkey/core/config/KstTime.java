package com.crosscert.passkey.core.config;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 프로젝트 단일 기준 타임존(KST). 한국 전용·서머타임 없음 → 고정 +09:00. */
public final class KstTime {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    public static final ZoneOffset OFFSET = ZoneOffset.ofHours(9);
    private KstTime() {}

    /**
     * 네이티브 쿼리의 {@code TRUNC(created_at)}(Oracle DATE) 결과를 KST 날짜로.
     *
     * <p>DB 세션 타임존이 KST 라 TRUNC 결과는 KST 날짜의 자정(벽시계)이다. Hibernate 7 은 이 값을
     * {@link LocalDateTime}(벽시계 그대로)으로, Hibernate 6 은 {@link java.util.Date}/{@code Timestamp}
     * (JDBC 타임존 KST 로 읽은 순간)로 돌려준다. 어느 쪽이든 같은 KST 날짜가 나온다.
     *
     * @throws IllegalArgumentException null 이거나 날짜로 해석할 수 없는 타입
     */
    public static LocalDate dbDay(Object truncatedDay) {
        if (truncatedDay instanceof LocalDateTime ldt) return ldt.toLocalDate();
        if (truncatedDay instanceof LocalDate d) return d;
        // java.sql.Date 는 java.util.Date 의 하위 타입이지만 toInstant() 가 UnsupportedOperationException 을
        // 던진다 — 시간 없는 날짜 그 자체이므로 먼저 걸러 toLocalDate() 로.
        if (truncatedDay instanceof java.sql.Date sd) return sd.toLocalDate();
        if (truncatedDay instanceof java.util.Date ud) return ud.toInstant().atZone(ZONE).toLocalDate();
        throw new IllegalArgumentException("unsupported DB day value: "
                + (truncatedDay == null ? "null" : truncatedDay.getClass().getName()));
    }
}
