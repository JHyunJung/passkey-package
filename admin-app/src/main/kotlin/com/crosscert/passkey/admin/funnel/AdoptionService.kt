package com.crosscert.passkey.admin.funnel

import com.crosscert.passkey.admin.auth.TenantBoundary
import com.crosscert.passkey.core.ceremony.CredentialAuthResult
import com.crosscert.passkey.core.config.KstTime
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 월간 표 길이. credential_auth_event 보관 기간(90일) 안에 들어오도록 이번 달 포함 3개월. */
private const val MONTHS = 3

/**
 * 인증 현황 탭의 "패스키 도입·사용" 지표 집계. credential(보관 기한 없음)과 credential_auth_event(90일 보관)를 읽는다.
 * 모두 tenant 범위 JPQL 집계(행을 메모리로 가져오지 않음)이고, 사용자 = credential.user_handle 이다.
 */
@Service
class AdoptionService(
    private val em: EntityManager,
    private val tenantBoundary: TenantBoundary,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun compute(tenantId: UUID, windowDays: Int): AdoptionDto.View {
        tenantBoundary.assertCanAccessTenant(tenantId)
        val now = OffsetDateTime.now(clock.withZone(KstTime.ZONE))
        val since = now.minus(windowDays.toLong(), ChronoUnit.DAYS)

        val total = count("select count(c) from Credential c where c.tenantId = :t", tenantId)
        val fresh = count("select count(c) from Credential c where c.tenantId = :t and c.createdAt >= :from", tenantId, since, null)
        val activeCreds = count(
            "select count(distinct e.credentialId) from CredentialAuthEvent e" +
                " where e.tenantId = :t and e.result = '" + CredentialAuthResult.SUCCESS + "' and e.createdAt >= :from",
            tenantId, since, null,
        )
        val activeUsers = activeUsers(tenantId, since, null)
        val users = count("select count(distinct c.userHandle) from Credential c where c.tenantId = :t", tenantId)
        val single = count(
            "select count(*) from (select c.userHandle as h from Credential c where c.tenantId = :t" +
                " group by c.userHandle having count(c) = 1)",
            tenantId,
        )

        val months = ArrayList<AdoptionDto.Month>(MONTHS)
        val thisMonth = YearMonth.from(now)
        for (i in MONTHS - 1 downTo 0) {
            val ym = thisMonth.minusMonths(i.toLong())
            val from = ym.atDay(1).atStartOfDay(KstTime.ZONE).toOffsetDateTime()
            val to = ym.plusMonths(1).atDay(1).atStartOfDay(KstTime.ZONE).toOffsetDateTime()
            val auths = count(
                "select count(e) from CredentialAuthEvent e where e.tenantId = :t and e.result = '" +
                    CredentialAuthResult.SUCCESS + "' and e.createdAt >= :from and e.createdAt < :to",
                tenantId, from, to,
            )
            val newCreds = count(
                "select count(c) from Credential c where c.tenantId = :t and c.createdAt >= :from and c.createdAt < :to",
                tenantId, from, to,
            )
            months.add(AdoptionDto.Month(ym.toString(), i == 0, auths, activeUsers(tenantId, from, to), newCreds))
        }

        return AdoptionDto.View(windowDays, total, fresh, activeCreds, activeUsers, users, single, months, devices(tenantId, since))
    }

    private fun activeUsers(tenantId: UUID, from: OffsetDateTime, to: OffsetDateTime?): Long =
        count(
            "select count(distinct c.userHandle) from CredentialAuthEvent e, Credential c" +
                " where c.id = e.credentialId and e.tenantId = :t and e.result = '" + CredentialAuthResult.SUCCESS + "'" +
                " and e.createdAt >= :from" + (if (to == null) "" else " and e.createdAt < :to"),
            tenantId, from, to,
        )

    /** 인증기 이름별로 합친 패스키 수(많은 순)와 기간 안 인증 성공 건수. */
    private fun devices(tenantId: UUID, since: OffsetDateTime): List<AdoptionDto.Device> {
        val byName = LinkedHashMap<String, LongArray>()
        for (row in em.createQuery(
            "select c.aaguid, count(c) from Credential c where c.tenantId = :t group by c.aaguid",
            ROW,
        ).setParameter("t", tenantId).resultList) {
            byName.getOrPut(AuthenticatorNames.name(row[0] as ByteArray?)) { LongArray(2) }[0] += (row[1] as Number).toLong()
        }
        for (row in em.createQuery(
            "select c.aaguid, count(e) from CredentialAuthEvent e, Credential c" +
                " where c.id = e.credentialId and e.tenantId = :t and e.result = '" + CredentialAuthResult.SUCCESS + "'" +
                " and e.createdAt >= :from group by c.aaguid",
            ROW,
        ).setParameter("t", tenantId).setParameter("from", since).resultList) {
            byName.getOrPut(AuthenticatorNames.name(row[0] as ByteArray?)) { LongArray(2) }[1] += (row[1] as Number).toLong()
        }
        val out = ArrayList<AdoptionDto.Device>()
        byName.forEach { (name, v) -> out.add(AdoptionDto.Device(name, v[0], v[1])) }
        out.sortWith { a, b ->
            if (a.credentials != b.credentials) b.credentials.compareTo(a.credentials) else a.name.compareTo(b.name)
        }
        return out
    }

    private fun count(jpql: String, tenantId: UUID, from: OffsetDateTime? = null, to: OffsetDateTime? = null): Long {
        val q = em.createQuery(jpql, Long::class.javaObjectType).setParameter("t", tenantId)
        if (from != null) q.setParameter("from", from)
        if (to != null) q.setParameter("to", to)
        val n: Long? = q.singleResult
        return n ?: 0L
    }
}

/** JPQL `Object[]` 결과 행의 클래스 — 원소는 null 일 수 있다(원 Java 의 캐스트처럼 null 을 그대로 통과). */
private val ROW = Array<Any?>::class.java
