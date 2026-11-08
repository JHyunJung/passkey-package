package com.crosscert.passkey.admin.funnel

import com.crosscert.passkey.admin.auth.TenantBoundary
import com.crosscert.passkey.core.ceremony.CeremonyAction
import com.crosscert.passkey.core.ceremony.CredentialAuthResult
import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.repository.CeremonyEventRepository
import com.crosscert.passkey.core.repository.CredentialAuthEventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

private const val REGISTRATION_BEGIN = CeremonyAction.REGISTRATION_BEGIN
private const val REGISTRATION_SUCCESS = CeremonyAction.REGISTRATION_SUCCESS
private const val AUTHENTICATION_BEGIN = CeremonyAction.AUTHENTICATION_BEGIN
private const val AUTHENTICATION_SUCCESS = CeremonyAction.AUTHENTICATION_SUCCESS

/**
 * Phase F3 — Aggregates ceremony_event rows into a registration/authentication
 * funnel view for the admin Funnel page (Gap #3/#9).
 *
 * Action constants follow spec § F3.1 via [CeremonyAction] (single
 * source of truth shared with passkey-app's recorder).
 *
 * Counts come from `ceremony_event`, populated by passkey-app's
 * registration/authentication ceremonies (begin/finish). Empty tenants return
 * 0 counts and the DTO contract yields ratio 0.
 */
@Service
class FunnelService(
    private val repo: CeremonyEventRepository,
    private val authEvents: CredentialAuthEventRepository,
    private val tenantBoundary: TenantBoundary,
) {

    @Transactional(readOnly = true)
    fun compute(tenantId: UUID, windowDays: Int): FunnelDto.View {
        // RP_ADMIN must not query other tenants' funnels. PLATFORM_OPERATOR is unrestricted.
        tenantBoundary.assertCanAccessTenant(tenantId)
        val since = OffsetDateTime.now(KstTime.ZONE).minus(windowDays.toLong(), ChronoUnit.DAYS)

        val regAttempts = repo.countByTenantIdAndActionAndCreatedAtAfter(tenantId, REGISTRATION_BEGIN, since)
        val regSuccess = repo.countByTenantIdAndActionAndCreatedAtAfter(tenantId, REGISTRATION_SUCCESS, since)
        val authAttempts = repo.countByTenantIdAndActionAndCreatedAtAfter(tenantId, AUTHENTICATION_BEGIN, since)
        val authSuccess = repo.countByTenantIdAndActionAndCreatedAtAfter(tenantId, AUTHENTICATION_SUCCESS, since)

        val registration = FunnelDto.Stage(regAttempts, regSuccess, ratio(regSuccess, regAttempts))
        val authentication = FunnelDto.Stage(authAttempts, authSuccess, ratio(authSuccess, authAttempts))

        val totalAttempts = regAttempts + authAttempts
        val totalSuccess = regSuccess + authSuccess
        val conversion = ratio(totalSuccess, totalAttempts)

        val daily = buildSeries(tenantId, since, windowDays)
        val byType = buildByEventType(tenantId, since)
        val failures = authEvents.countByReason(tenantId, CredentialAuthResult.FAILED, since)
            .map { row -> FunnelDto.FailureCount(row[0] as String?, (row[1] as Number).toLong()) }

        return FunnelDto.View(
            windowDays, registration, authentication, conversion, daily.combined, byType,
            daily.registration, daily.authentication, failures,
        )
    }

    private fun ratio(success: Long, attempts: Long): Double {
        if (attempts == 0L) return 0.0
        val r = success.toDouble() / attempts.toDouble()
        // 윈도우 경계에서 begin 은 잘리고 finish 만 포함되면 success>attempts 가 될 수 있다.
        // 성공률 의미상 100% 를 넘을 수 없으므로 1.0 으로 클램프한다.
        return minOf(r, 1.0)
    }

    /** 같은 날짜 축의 일별 값 3종: 합계(기존 series), 등록만, 인증만. */
    private data class Daily(
        val combined: List<FunnelDto.DailyPoint>,
        val registration: List<FunnelDto.DailyPoint>,
        val authentication: List<FunnelDto.DailyPoint>,
    )

    private fun buildSeries(tenantId: UUID, since: OffsetDateTime, windowDays: Int): Daily {
        val byDay = HashMap<LocalDate, LongArray>()
        for (row in repo.aggregateDailyByTenantAndActions(
            tenantId,
            listOf(REGISTRATION_BEGIN, AUTHENTICATION_BEGIN, REGISTRATION_SUCCESS, AUTHENTICATION_SUCCESS),
            since,
        )) {
            // row[0] = TRUNC(created_at) (Oracle DATE; Hibernate 7 -> LocalDateTime), row[1] = action, row[2] = count
            // F06: reinterpret in KST — the same timezone the DB session used to
            // compute TRUNC(created_at) (JVM TZ=Asia/Seoul, hibernate.jdbc.time_zone=
            // Asia/Seoul). Reinterpreting in UTC shifted every daily bucket back by
            // one day and, for KST 00:00–09:00 queries, pushed today's freshest
            // data out of the rendering window below entirely.
            val day = KstTime.dbDay(row[0])
            val action = row[1] as String
            val count = (row[2] as Number).toLong()
            // cell = [등록 시도, 등록 성공, 인증 시도, 인증 성공]
            val cell = byDay.getOrPut(day) { LongArray(4) }
            when (action) {
                REGISTRATION_BEGIN -> cell[0] += count
                REGISTRATION_SUCCESS -> cell[1] += count
                AUTHENTICATION_BEGIN -> cell[2] += count
                AUTHENTICATION_SUCCESS -> cell[3] += count
                else -> {}
            }
        }
        val combined = ArrayList<FunnelDto.DailyPoint>(windowDays)
        val registration = ArrayList<FunnelDto.DailyPoint>(windowDays)
        val authentication = ArrayList<FunnelDto.DailyPoint>(windowDays)
        val today = LocalDate.now(KstTime.ZONE)
        for (i in windowDays - 1 downTo 0) {
            val d = today.minusDays(i.toLong())
            val cell = byDay.getOrDefault(d, LongArray(4))
            // day label as "MM-DD"
            val label = d.toString().substring(5)
            combined.add(FunnelDto.DailyPoint(label, cell[0] + cell[2], cell[1] + cell[3]))
            registration.add(FunnelDto.DailyPoint(label, cell[0], cell[1]))
            authentication.add(FunnelDto.DailyPoint(label, cell[2], cell[3]))
        }
        return Daily(combined, registration, authentication)
    }

    private fun buildByEventType(tenantId: UUID, since: OffsetDateTime): List<FunnelDto.EventCount> =
        repo.aggregateByTenantAndActionsGrouped(
            tenantId,
            listOf(REGISTRATION_BEGIN, REGISTRATION_SUCCESS, AUTHENTICATION_BEGIN, AUTHENTICATION_SUCCESS),
            since,
        ).map { row -> FunnelDto.EventCount(row[0] as String?, (row[1] as Number).toLong()) }
}
