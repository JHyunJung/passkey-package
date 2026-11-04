package com.crosscert.passkey.admin.funnel;

import java.util.List;

public final class FunnelDto {
    private FunnelDto() {}

    /**
     * series 는 등록·인증 시도/성공을 합친 일별 값(기존 JSON 호환용)이다. 화면은 등록·인증을 따로 보는
     * registrationSeries / authenticationSeries 와 인증 실패 원인(authFailures)을 쓴다.
     */
    public record View(
            int windowDays,
            Stage registration,
            Stage authentication,
            double conversion,
            List<DailyPoint> series,
            List<EventCount> byEventType,
            List<DailyPoint> registrationSeries,
            List<DailyPoint> authenticationSeries,
            List<FailureCount> authFailures
    ) {
        /** 새 필드 없이 만들던 기존 호출부(테스트 포함)용 — 새 목록은 비어 있다. */
        public View(int windowDays, Stage registration, Stage authentication, double conversion,
                    List<DailyPoint> series, List<EventCount> byEventType) {
            this(windowDays, registration, authentication, conversion, series, byEventType, List.of(), List.of(), List.of());
        }
    }

    public record Stage(long attempts, long success, double ratio) {}

    public record DailyPoint(String day, long attempts, long success) {}

    public record EventCount(String type, long n) {}

    /** reason 은 credential_auth_event.failure_reason 코드(null 이면 원인 미기록). */
    public record FailureCount(String reason, long n) {}
}
