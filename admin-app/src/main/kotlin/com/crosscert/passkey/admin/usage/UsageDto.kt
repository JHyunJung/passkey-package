package com.crosscert.passkey.admin.usage

import java.time.OffsetDateTime
import java.util.UUID

/** 사용량 정산 화면·REST 응답. 엔티티는 JSON·템플릿에 직접 내보내지 않고 이 값 객체로만 옮긴다. */
object UsageDto {

    /** 진행 중(마감 전) / 마감 / 부분 마감(원본 일부 삭제 후 마감). */
    enum class Status { IN_PROGRESS, CLOSED, PARTIAL }

    /** 마감 숫자와 감사 로그 기록의 대조 결과. 진행 중인 달은 대조할 기록이 없어 [NOT_APPLICABLE]. */
    enum class Verification { MATCH, MISMATCH, NOT_APPLICABLE }

    /** 테넌트 한 줄. 테넌트 목록에 없는 id 는 name = id 문자열, slug = null. */
    data class TenantRow(val tenantId: UUID, val slug: String?, val name: String, val regSuccess: Long, val authSuccess: Long)

    /** 월별 추이 한 줄. 성공률은 화면이 authSuccess / authAttempt 로 계산한다. */
    data class MonthRow(
        val period: String,
        val status: Status,
        val regSuccess: Long,
        val authSuccess: Long,
        val activeTenants: Int,
        val authAttempt: Long,
    )

    /** basis 는 "LEDGER" / "LEGACY". asOf 는 마감 시각 또는 마지막 집계 시각(없으면 null). */
    data class View(
        val period: String,
        val status: Status,
        val basis: String,
        val asOf: OffsetDateTime?,
        val verification: Verification,
        val tenants: List<TenantRow>,
        val totalReg: Long,
        val totalAuth: Long,
        val trend: List<MonthRow>,
        val periods: List<String>,
    )
}
