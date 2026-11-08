package com.crosscert.passkey.admin.insights

import com.crosscert.passkey.admin.funnel.AdoptionDto
import com.crosscert.passkey.admin.funnel.AuthenticatorNames
import com.crosscert.passkey.admin.funnel.FunnelDto
import com.crosscert.passkey.core.ceremony.CeremonyAction
import com.crosscert.passkey.core.ceremony.CredentialAuthResult
import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.repository.TenantRepository
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

private const val MONTHS = 3
private const val OK = "'" + CredentialAuthResult.SUCCESS + "'"
private const val FAILED = "'" + CredentialAuthResult.FAILED + "'"

/** JPQL `Object[]` 결과 행의 클래스 — 원소는 null 일 수 있다(원 Java 의 캐스트처럼 null 을 그대로 통과). */
private val ROW = Array<Any?>::class.java

/**
 * 플랫폼 전체 현황 집계. 테넌트마다 쿼리를 돌리지 않고 tenant_id 로 묶어 한 번에 센다(테넌트 수와 무관한 쿼리 수).
 * 정의는 테넌트 인증 현황 탭(FunnelService·AdoptionService)과 같다: 성공률 = ceremony_event finish / begin,
 * 활성 사용자 = 기간 안 인증에 성공한 user handle, 위험 인증 = credential_auth_event FAILED.
 *
 * "확인 필요" 판정은 고른 기간과 무관하게 최근 7일 기준(성공률은 그 앞 7일과 비교)이고, 기준값은 admin.insights.* 로 바꾼다.
 * 호출 권한(PLATFORM_OPERATOR)은 컨트롤러의 @PreAuthorize 가 막는다 — 이 서비스는 tenant 범위가 아니라 전 테넌트를 읽는다.
 */
@Service
class PlatformInsightsService(
    private val em: EntityManager,
    private val tenantRepo: TenantRepository,
    private val clock: Clock,
    @Value("\${admin.insights.rate-drop-pp:5}") rateDropPp: Double,
    @Value("\${admin.insights.rate-drop-min-attempts:30}") rateDropMinAttempts: Long,
    @Value("\${admin.insights.risk-min:1}") riskMin: Long,
    @Value("\${admin.insights.single-passkey-ratio:0.5}") singleRatio: Double,
    @Value("\${admin.insights.single-passkey-min-users:10}") singleMinUsers: Long,
) {

    private val thresholds =
        PlatformInsightsDto.Thresholds(rateDropPp, rateDropMinAttempts, riskMin, singleRatio, singleMinUsers)

    @Transactional(readOnly = true)
    fun compute(windowDays: Int): PlatformInsightsDto.View {
        val now = OffsetDateTime.now(clock.withZone(KstTime.ZONE))
        val since = now.minus(windowDays.toLong(), ChronoUnit.DAYS)
        val d7 = now.minus(7, ChronoUnit.DAYS)
        val d14 = now.minus(14, ChronoUnit.DAYS)

        val cer = ceremonies(since, null) // [regA, regS, authA, authS]
        val recent = ceremonies(d7, null)
        val prior = ceremonies(d14, d7)
        val risk = byTenant(
            "select e.tenantId, count(e) from CredentialAuthEvent e where e.result = " + FAILED +
                " and e.createdAt >= :from group by e.tenantId",
            since, null,
        )
        val recentRisk = byTenant(
            "select e.tenantId, count(e) from CredentialAuthEvent e where e.result = " + FAILED +
                " and e.createdAt >= :from group by e.tenantId",
            d7, null,
        )
        val active = activeUsers(since, null)
        val creds = byTenant("select c.tenantId, count(c) from Credential c group by c.tenantId", null, null)
        val users = HashMap<UUID?, LongArray>() // [users, single]
        for (r in em.createQuery(
            "select h.t, count(*), sum(case when h.n = 1 then 1 else 0 end) from" +
                " (select c.tenantId as t, c.userHandle as u, count(c) as n from Credential c group by c.tenantId, c.userHandle) h" +
                " group by h.t",
            ROW,
        ).resultList) {
            users[r[0] as UUID?] = longArrayOf(num(r[1]), num(r[2]))
        }

        val rows = ArrayList<PlatformInsightsDto.TenantRow>()
        val t = LongArray(11)
        val tenants = tenantRepo.findAll()
        for (ten in tenants) {
            val id = ten.id
            val c = cer.getOrDefault(id, LongArray(4))
            val rc = recent.getOrDefault(id, LongArray(4))
            val pc = prior.getOrDefault(id, LongArray(4))
            val u = users.getOrDefault(id, LongArray(2))
            val row = PlatformInsightsDto.TenantRow(
                id, ten.displayName, ten.slug, ten.status,
                active.getOrDefault(id, 0L), c[2], c[3], c[0], c[1], risk.getOrDefault(id, 0L),
                creds.getOrDefault(id, 0L), u[0], u[1],
                rc[2], rc[3], pc[2], pc[3], recentRisk.getOrDefault(id, 0L),
            )
            rows.add(row)
            if ("active".equals(ten.status, ignoreCase = true)) t[0]++
            t[1] += row.activeUsers; t[2] += row.authAttempts; t[3] += row.authSuccess
            t[4] += row.regAttempts; t[5] += row.regSuccess
            t[6] += row.risk; t[7] += row.credentials; t[8] += row.users; t[9] += row.singlePasskeyUsers
        }
        rows.sortWith(
            compareByDescending<PlatformInsightsDto.TenantRow> { it.activeUsers }
                .thenBy { it.name ?: "" },
        )
        val totals = PlatformInsightsDto.Totals(
            tenants.size.toLong(), t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8], t[9],
        )

        return PlatformInsightsDto.View(
            windowDays, totals, rows, alerts(rows), dailyAuth(since, windowDays), months(now), devices(since), thresholds,
        )
    }

    /**
     * 확인 필요 테넌트. 한 테넌트가 여러 기준에 걸리면 각각 한 줄. 순서: 위험 인증 → 성공률 하락 → 1개뿐 비율.
     *
     * 원 Java 는 package-private(같은 패키지 테스트가 직접 호출) — Kotlin 에는 대응 가시성이 없어 public 으로 둔다.
     */
    fun alerts(rows: List<PlatformInsightsDto.TenantRow>): List<PlatformInsightsDto.Alert> {
        val risk = ArrayList<PlatformInsightsDto.Alert>()
        val drop = ArrayList<PlatformInsightsDto.Alert>()
        val single = ArrayList<PlatformInsightsDto.Alert>()
        for (r in rows) {
            if (thresholds.riskMin > 0 && r.recentRisk >= thresholds.riskMin) {
                risk.add(
                    PlatformInsightsDto.Alert(
                        r.tenantId, r.name, r.slug, "RISK",
                        "최근 7일 차단된 위험 인증 " + r.recentRisk + "건",
                    ),
                )
            }
            if (r.recentAuthAttempts >= thresholds.rateDropMinAttempts && r.priorAuthAttempts > 0) {
                val cur = minOf(1.0, r.recentAuthSuccess.toDouble() / r.recentAuthAttempts)
                val prev = minOf(1.0, r.priorAuthSuccess.toDouble() / r.priorAuthAttempts)
                val pp = (prev - cur) * 100
                if (pp >= thresholds.rateDropPp - 1e-9) { // 경계값(정확히 5%p)이 부동소수 오차로 빠지지 않게
                    drop.add(
                        PlatformInsightsDto.Alert(
                            r.tenantId, r.name, r.slug, "RATE_DROP",
                            String.format(
                                Locale.ROOT, "인증 성공률 %.1f%% → %.1f%% (%.1f%%p 하락, 최근 7일)",
                                prev * 100, cur * 100, pp,
                            ),
                        ),
                    )
                }
            }
            if (r.users >= thresholds.singleMinUsers && r.users > 0 &&
                r.singlePasskeyUsers.toDouble() / r.users >= thresholds.singleRatio
            ) {
                single.add(
                    PlatformInsightsDto.Alert(
                        r.tenantId, r.name, r.slug, "SINGLE_PASSKEY",
                        String.format(
                            Locale.ROOT, "패스키 1개뿐인 사용자 %.1f%% (%d명 / %d명)",
                            r.singlePasskeyUsers.toDouble() / r.users * 100, r.singlePasskeyUsers, r.users,
                        ),
                    ),
                )
            }
        }
        val out = ArrayList<PlatformInsightsDto.Alert>(risk)
        out.addAll(drop)
        out.addAll(single)
        return out
    }

    private fun ceremonies(from: OffsetDateTime, to: OffsetDateTime?): Map<UUID?, LongArray> {
        val out = HashMap<UUID?, LongArray>()
        val q = em.createQuery(
            "select e.tenantId, e.action, count(e) from CeremonyEvent e where e.action in :actions and e.createdAt >= :from" +
                (if (to == null) "" else " and e.createdAt < :to") + " group by e.tenantId, e.action",
            ROW,
        )
            .setParameter(
                "actions",
                listOf(
                    CeremonyAction.REGISTRATION_BEGIN, CeremonyAction.REGISTRATION_SUCCESS,
                    CeremonyAction.AUTHENTICATION_BEGIN, CeremonyAction.AUTHENTICATION_SUCCESS,
                ),
            )
            .setParameter("from", from)
        if (to != null) q.setParameter("to", to)
        for (r in q.resultList) {
            val cell = out.getOrPut(r[0] as UUID?) { LongArray(4) }
            when (r[1] as String) {
                CeremonyAction.REGISTRATION_BEGIN -> cell[0] += num(r[2])
                CeremonyAction.REGISTRATION_SUCCESS -> cell[1] += num(r[2])
                CeremonyAction.AUTHENTICATION_BEGIN -> cell[2] += num(r[2])
                CeremonyAction.AUTHENTICATION_SUCCESS -> cell[3] += num(r[2])
                else -> {}
            }
        }
        return out
    }

    private fun activeUsers(from: OffsetDateTime, to: OffsetDateTime?): Map<UUID?, Long> =
        byTenant(
            "select e.tenantId, count(distinct c.userHandle) from CredentialAuthEvent e, Credential c" +
                " where c.id = e.credentialId and e.result = " + OK + " and e.createdAt >= :from" +
                (if (to == null) "" else " and e.createdAt < :to") + " group by e.tenantId",
            from, to,
        )

    private fun byTenant(jpql: String, from: OffsetDateTime?, to: OffsetDateTime?): Map<UUID?, Long> {
        val q = em.createQuery(jpql, ROW)
        if (from != null) q.setParameter("from", from)
        if (to != null) q.setParameter("to", to)
        val out = HashMap<UUID?, Long>()
        for (r in q.resultList) out[r[0] as UUID?] = num(r[1])
        return out
    }

    /** 플랫폼 일별 인증 시도/성공. FunnelService 와 같은 TRUNC(created_at) + KST 재해석. */
    private fun dailyAuth(since: OffsetDateTime, windowDays: Int): List<FunnelDto.DailyPoint> {
        val byDay = HashMap<LocalDate, LongArray>()
        @Suppress("UNCHECKED_CAST")
        val rows = em.createNativeQuery(
            "SELECT TRUNC(created_at), action, COUNT(*) FROM {h-schema}ceremony_event" +
                " WHERE action IN (:a, :b) AND created_at >= :since GROUP BY TRUNC(created_at), action",
        )
            .setParameter("a", CeremonyAction.AUTHENTICATION_BEGIN)
            .setParameter("b", CeremonyAction.AUTHENTICATION_SUCCESS)
            .setParameter("since", since)
            .resultList as List<Array<Any?>>
        for (r in rows) {
            val cell = byDay.getOrPut(KstTime.dbDay(r[0])) { LongArray(2) }
            if (CeremonyAction.AUTHENTICATION_BEGIN == r[1]) cell[0] += num(r[2]) else cell[1] += num(r[2])
        }
        val out = ArrayList<FunnelDto.DailyPoint>(windowDays)
        val today = LocalDate.now(clock.withZone(KstTime.ZONE))
        for (i in windowDays - 1 downTo 0) {
            val d = today.minusDays(i.toLong())
            val cell = byDay.getOrDefault(d, LongArray(2))
            out.add(FunnelDto.DailyPoint(d.toString().substring(5), cell[0], cell[1]))
        }
        return out
    }

    /** 최근 3개월: 인증 성공, 활성 사용자(테넌트별 distinct user handle 의 합), 신규 패스키. */
    private fun months(now: OffsetDateTime): List<AdoptionDto.Month> {
        val out = ArrayList<AdoptionDto.Month>(MONTHS)
        val thisMonth = YearMonth.from(now)
        for (i in MONTHS - 1 downTo 0) {
            val ym = thisMonth.minusMonths(i.toLong())
            val from = ym.atDay(1).atStartOfDay(KstTime.ZONE).toOffsetDateTime()
            val to = ym.plusMonths(1).atDay(1).atStartOfDay(KstTime.ZONE).toOffsetDateTime()
            val auths = single(
                "select count(e) from CredentialAuthEvent e where e.result = " + OK +
                    " and e.createdAt >= :from and e.createdAt < :to",
                from, to,
            )
            val mau = activeUsers(from, to).values.sum()
            val fresh = single(
                "select count(c) from Credential c where c.createdAt >= :from and c.createdAt < :to",
                from, to,
            )
            out.add(AdoptionDto.Month(ym.toString(), i == 0, auths, mau, fresh))
        }
        return out
    }

    private fun devices(since: OffsetDateTime): List<AdoptionDto.Device> {
        val byName = LinkedHashMap<String, LongArray>()
        for (r in em.createQuery("select c.aaguid, count(c) from Credential c group by c.aaguid", ROW).resultList) {
            byName.getOrPut(AuthenticatorNames.name(r[0] as ByteArray?)) { LongArray(2) }[0] += num(r[1])
        }
        for (r in em.createQuery(
            "select c.aaguid, count(e) from CredentialAuthEvent e, Credential c where c.id = e.credentialId" +
                " and e.result = " + OK + " and e.createdAt >= :from group by c.aaguid",
            ROW,
        ).setParameter("from", since).resultList) {
            byName.getOrPut(AuthenticatorNames.name(r[0] as ByteArray?)) { LongArray(2) }[1] += num(r[1])
        }
        val out = ArrayList<AdoptionDto.Device>()
        byName.forEach { (n, v) -> out.add(AdoptionDto.Device(n, v[0], v[1])) }
        out.sortWith { a, b ->
            if (a.credentials != b.credentials) b.credentials.compareTo(a.credentials) else a.name.compareTo(b.name)
        }
        return out
    }

    private fun single(jpql: String, from: OffsetDateTime, to: OffsetDateTime): Long {
        val n: Long? = em.createQuery(jpql, Long::class.javaObjectType)
            .setParameter("from", from).setParameter("to", to).singleResult
        return n ?: 0L
    }
}

private fun num(o: Any?): Long = if (o == null) 0 else (o as Number).toLong()
