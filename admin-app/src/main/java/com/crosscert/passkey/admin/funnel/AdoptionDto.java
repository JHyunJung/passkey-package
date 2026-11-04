package com.crosscert.passkey.admin.funnel;

import java.util.List;

/**
 * 인증 현황 탭의 "패스키 도입·사용" 지표. 고객사 보고(도입 추이·기기 분포)와 내부 사용량(MAU·월 인증 건수)에 쓴다.
 * 기간(windowDays) 지표는 FunnelDto 와 같은 기준(지금부터 windowDays 일 전 이후)이다.
 */
public final class AdoptionDto {
    private AdoptionDto() {}

    public record View(
            int windowDays,
            long totalCredentials,      // 지금 등록돼 있는 패스키
            long newCredentials,        // 기간 안에 등록된 패스키
            long activeCredentials,     // 기간 안에 인증에 성공한 패스키
            long activeUsers,           // 기간 안에 인증에 성공한 사용자(user handle)
            long totalUsers,            // 패스키가 1개 이상 있는 사용자
            long singlePasskeyUsers,    // 패스키가 딱 1개인 사용자
            List<Month> months,         // 최근 3개월(오래된 달 → 이번 달). 인증 기록 보관 90일 안
            List<Device> devices        // 인증기별 패스키 수(많은 순)
    ) {}

    /** month = "YYYY-MM"(KST). current = 이번 달(진행 중). */
    public record Month(String month, boolean current, long authSuccess, long activeUsers, long newCredentials) {}

    /** name 은 인증기 이름(같은 이름의 AAGUID 는 합침). credentials = 등록된 패스키 수, authSuccess = 기간 안 인증 성공 건수. */
    public record Device(String name, long credentials, long authSuccess) {}
}
