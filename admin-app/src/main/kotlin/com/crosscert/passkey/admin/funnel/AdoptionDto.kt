package com.crosscert.passkey.admin.funnel

/**
 * 인증 현황 탭의 "패스키 도입·사용" 지표. 고객사 보고(도입 추이·기기 분포)와 내부 사용량(MAU·월 인증 건수)에 쓴다.
 * 기간(windowDays) 지표는 FunnelDto 와 같은 기준(지금부터 windowDays 일 전 이후)이다.
 */
class AdoptionDto private constructor() {

    data class View(
        val windowDays: Int,
        val totalCredentials: Long, // 지금 등록돼 있는 패스키
        val newCredentials: Long, // 기간 안에 등록된 패스키
        val activeCredentials: Long, // 기간 안에 인증에 성공한 패스키
        val activeUsers: Long, // 기간 안에 인증에 성공한 사용자(user handle)
        val totalUsers: Long, // 패스키가 1개 이상 있는 사용자
        val singlePasskeyUsers: Long, // 패스키가 딱 1개인 사용자
        val months: List<Month>?, // 최근 3개월(오래된 달 → 이번 달). 인증 기록 보관 90일 안
        val devices: List<Device>?, // 인증기별 패스키 수(많은 순)
    )

    /** month = "YYYY-MM"(KST). current = 이번 달(진행 중). */
    data class Month(
        val month: String?,
        val current: Boolean,
        val authSuccess: Long,
        val activeUsers: Long,
        val newCredentials: Long,
    )

    /**
     * name 은 인증기 이름(같은 이름의 AAGUID 는 합침). credentials = 등록된 패스키 수, authSuccess = 기간 안 인증 성공 건수.
     *
     * name 은 non-null — 값은 항상 [AuthenticatorNames.name] 결과이고, 정렬(이름 비교)이 null 을 받지 않는다.
     */
    data class Device(
        val name: String,
        val credentials: Long,
        val authSuccess: Long,
    )
}
