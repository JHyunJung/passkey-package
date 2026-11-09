package com.crosscert.passkey.admin.funnel

class FunnelDto private constructor() {

    /**
     * series 는 등록·인증 시도/성공을 합친 일별 값(기존 JSON 호환용)이다. 화면은 등록·인증을 따로 보는
     * registrationSeries / authenticationSeries 와 인증 실패 원인(authFailures)을 쓴다.
     */
    data class View(
        val windowDays: Int,
        val registration: Stage?,
        val authentication: Stage?,
        val conversion: Double,
        val series: List<DailyPoint>?,
        val byEventType: List<EventCount>?,
        val registrationSeries: List<DailyPoint>?,
        val authenticationSeries: List<DailyPoint>?,
        val authFailures: List<FailureCount>?,
    ) {
        /** 새 필드 없이 만들던 기존 호출부(테스트 포함)용 — 새 목록은 비어 있다. */
        constructor(
            windowDays: Int,
            registration: Stage?,
            authentication: Stage?,
            conversion: Double,
            series: List<DailyPoint>?,
            byEventType: List<EventCount>?,
        ) : this(windowDays, registration, authentication, conversion, series, byEventType, listOf(), listOf(), listOf())
    }

    data class Stage(val attempts: Long, val success: Long, val ratio: Double)

    data class DailyPoint(val day: String?, val attempts: Long, val success: Long)

    data class EventCount(val type: String?, val n: Long)

    /** reason 은 credential_auth_event.failure_reason 코드(null 이면 원인 미기록). */
    data class FailureCount(val reason: String?, val n: Long)
}
