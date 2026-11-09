package com.crosscert.passkey.admin.insights

import com.crosscert.passkey.admin.funnel.AdoptionDto
import com.crosscert.passkey.admin.funnel.FunnelDto
import java.util.UUID

/** 플랫폼 전체 현황(PLATFORM_OPERATOR 전용). 테넌트 인증 현황 탭과 같은 정의를 전 테넌트에 대해 한 번에 집계한다. */
class PlatformInsightsDto private constructor() {

    data class View(
        val windowDays: Int,
        val totals: Totals?,
        val tenants: List<TenantRow>?, // 활성 사용자 많은 순
        val alerts: List<Alert>?, // 확인이 필요한 테넌트(기준: thresholds)
        val authSeries: List<FunnelDto.DailyPoint>?, // 플랫폼 일별 인증 시도/성공(오래된 날 → 오늘)
        val months: List<AdoptionDto.Month>?, // 최근 3개월(인증 기록 90일 보관)
        val devices: List<AdoptionDto.Device>?, // 전체 인증기 분포
        val thresholds: Thresholds?,
    )

    data class Totals(
        val tenants: Long,
        val activeTenants: Long,
        val activeUsers: Long,
        val authAttempts: Long,
        val authSuccess: Long,
        val regAttempts: Long,
        val regSuccess: Long,
        val risk: Long,
        val credentials: Long,
        val users: Long,
        val singlePasskeyUsers: Long,
    )

    /** 기간 지표 + 확인 필요 판정용 최근 7일·그 앞 7일 인증 시도/성공과 최근 7일 위험 인증. */
    data class TenantRow(
        val tenantId: UUID?,
        val name: String?,
        val slug: String?,
        val status: String?,
        val activeUsers: Long,
        val authAttempts: Long,
        val authSuccess: Long,
        val regAttempts: Long,
        val regSuccess: Long,
        val risk: Long,
        val credentials: Long,
        val users: Long,
        val singlePasskeyUsers: Long,
        val recentAuthAttempts: Long,
        val recentAuthSuccess: Long,
        val priorAuthAttempts: Long,
        val priorAuthSuccess: Long,
        val recentRisk: Long,
    )

    /** kind: RATE_DROP(성공률 하락) · RISK(위험 인증) · SINGLE_PASSKEY(패스키 1개뿐 비율). */
    data class Alert(
        val tenantId: UUID?,
        val name: String?,
        val slug: String?,
        val kind: String?,
        val message: String?,
    )

    data class Thresholds(
        val rateDropPp: Double,
        val rateDropMinAttempts: Long,
        val riskMin: Long,
        val singleRatio: Double,
        val singleMinUsers: Long,
    )
}
