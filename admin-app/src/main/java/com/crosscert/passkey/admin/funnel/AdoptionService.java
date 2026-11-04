package com.crosscert.passkey.admin.funnel;

import com.crosscert.passkey.admin.auth.TenantBoundary;
import com.crosscert.passkey.core.ceremony.CredentialAuthResult;
import com.crosscert.passkey.core.config.KstTime;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 인증 현황 탭의 "패스키 도입·사용" 지표 집계. credential(보관 기한 없음)과 credential_auth_event(90일 보관)를 읽는다.
 * 모두 tenant 범위 JPQL 집계(행을 메모리로 가져오지 않음)이고, 사용자 = credential.user_handle 이다.
 */
@Service
public class AdoptionService {

    /** 월간 표 길이. credential_auth_event 보관 기간(90일) 안에 들어오도록 이번 달 포함 3개월. */
    static final int MONTHS = 3;

    private final EntityManager em;
    private final TenantBoundary tenantBoundary;
    private final Clock clock;

    public AdoptionService(EntityManager em, TenantBoundary tenantBoundary, Clock clock) {
        this.em = em;
        this.tenantBoundary = tenantBoundary;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AdoptionDto.View compute(UUID tenantId, int windowDays) {
        tenantBoundary.assertCanAccessTenant(tenantId);
        OffsetDateTime now = OffsetDateTime.now(clock.withZone(KstTime.ZONE));
        OffsetDateTime since = now.minus(windowDays, ChronoUnit.DAYS);

        long total = count("select count(c) from Credential c where c.tenantId = :t", tenantId);
        long fresh = count("select count(c) from Credential c where c.tenantId = :t and c.createdAt >= :from", tenantId, since, null);
        long activeCreds = count("select count(distinct e.credentialId) from CredentialAuthEvent e"
                + " where e.tenantId = :t and e.result = '" + CredentialAuthResult.SUCCESS + "' and e.createdAt >= :from", tenantId, since, null);
        long activeUsers = activeUsers(tenantId, since, null);
        long users = count("select count(distinct c.userHandle) from Credential c where c.tenantId = :t", tenantId);
        long single = count("select count(*) from (select c.userHandle as h from Credential c where c.tenantId = :t"
                + " group by c.userHandle having count(c) = 1)", tenantId);

        List<AdoptionDto.Month> months = new ArrayList<>(MONTHS);
        YearMonth thisMonth = YearMonth.from(now);
        for (int i = MONTHS - 1; i >= 0; i--) {
            YearMonth ym = thisMonth.minusMonths(i);
            OffsetDateTime from = ym.atDay(1).atStartOfDay(KstTime.ZONE).toOffsetDateTime();
            OffsetDateTime to = ym.plusMonths(1).atDay(1).atStartOfDay(KstTime.ZONE).toOffsetDateTime();
            long auths = count("select count(e) from CredentialAuthEvent e where e.tenantId = :t and e.result = '"
                    + CredentialAuthResult.SUCCESS + "' and e.createdAt >= :from and e.createdAt < :to", tenantId, from, to);
            long newCreds = count("select count(c) from Credential c where c.tenantId = :t and c.createdAt >= :from and c.createdAt < :to",
                    tenantId, from, to);
            months.add(new AdoptionDto.Month(ym.toString(), i == 0, auths, activeUsers(tenantId, from, to), newCreds));
        }

        return new AdoptionDto.View(windowDays, total, fresh, activeCreds, activeUsers, users, single, months, devices(tenantId, since));
    }

    private long activeUsers(UUID tenantId, OffsetDateTime from, OffsetDateTime to) {
        return count("select count(distinct c.userHandle) from CredentialAuthEvent e, Credential c"
                + " where c.id = e.credentialId and e.tenantId = :t and e.result = '" + CredentialAuthResult.SUCCESS + "'"
                + " and e.createdAt >= :from" + (to == null ? "" : " and e.createdAt < :to"), tenantId, from, to);
    }

    /** 인증기 이름별로 합친 패스키 수(많은 순)와 기간 안 인증 성공 건수. */
    private List<AdoptionDto.Device> devices(UUID tenantId, OffsetDateTime since) {
        Map<String, long[]> byName = new LinkedHashMap<>();
        for (Object[] row : em.createQuery("select c.aaguid, count(c) from Credential c where c.tenantId = :t group by c.aaguid", Object[].class)
                .setParameter("t", tenantId).getResultList()) {
            byName.computeIfAbsent(AuthenticatorNames.name((byte[]) row[0]), k -> new long[2])[0] += ((Number) row[1]).longValue();
        }
        for (Object[] row : em.createQuery("select c.aaguid, count(e) from CredentialAuthEvent e, Credential c"
                        + " where c.id = e.credentialId and e.tenantId = :t and e.result = '" + CredentialAuthResult.SUCCESS + "'"
                        + " and e.createdAt >= :from group by c.aaguid", Object[].class)
                .setParameter("t", tenantId).setParameter("from", since).getResultList()) {
            byName.computeIfAbsent(AuthenticatorNames.name((byte[]) row[0]), k -> new long[2])[1] += ((Number) row[1]).longValue();
        }
        List<AdoptionDto.Device> out = new ArrayList<>();
        byName.forEach((name, v) -> out.add(new AdoptionDto.Device(name, v[0], v[1])));
        out.sort((a, b) -> a.credentials() != b.credentials() ? Long.compare(b.credentials(), a.credentials()) : a.name().compareTo(b.name()));
        return out;
    }

    private long count(String jpql, UUID tenantId) {
        return count(jpql, tenantId, null, null);
    }

    private long count(String jpql, UUID tenantId, OffsetDateTime from, OffsetDateTime to) {
        var q = em.createQuery(jpql, Long.class).setParameter("t", tenantId);
        if (from != null) q.setParameter("from", from);
        if (to != null) q.setParameter("to", to);
        Long n = q.getSingleResult();
        return n == null ? 0 : n;
    }
}
