package com.crosscert.passkey.admin.insights;

import com.crosscert.passkey.admin.funnel.AdoptionDto;
import com.crosscert.passkey.admin.funnel.AuthenticatorNames;
import com.crosscert.passkey.admin.funnel.FunnelDto;
import com.crosscert.passkey.core.ceremony.CeremonyAction;
import com.crosscert.passkey.core.ceremony.CredentialAuthResult;
import com.crosscert.passkey.core.config.KstTime;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.TenantRepository;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 플랫폼 전체 현황 집계. 테넌트마다 쿼리를 돌리지 않고 tenant_id 로 묶어 한 번에 센다(테넌트 수와 무관한 쿼리 수).
 * 정의는 테넌트 인증 현황 탭(FunnelService·AdoptionService)과 같다: 성공률 = ceremony_event finish / begin,
 * 활성 사용자 = 기간 안 인증에 성공한 user handle, 위험 인증 = credential_auth_event FAILED.
 *
 * <p>"확인 필요" 판정은 고른 기간과 무관하게 최근 7일 기준(성공률은 그 앞 7일과 비교)이고, 기준값은 admin.insights.* 로 바꾼다.
 * 호출 권한(PLATFORM_OPERATOR)은 컨트롤러의 @PreAuthorize 가 막는다 — 이 서비스는 tenant 범위가 아니라 전 테넌트를 읽는다.
 */
@Service
public class PlatformInsightsService {

    static final int MONTHS = 3;
    private static final String OK = "'" + CredentialAuthResult.SUCCESS + "'";
    private static final String FAILED = "'" + CredentialAuthResult.FAILED + "'";

    private final EntityManager em;
    private final TenantRepository tenantRepo;
    private final Clock clock;
    private final PlatformInsightsDto.Thresholds thresholds;

    public PlatformInsightsService(EntityManager em, TenantRepository tenantRepo, Clock clock,
                                   @Value("${admin.insights.rate-drop-pp:5}") double rateDropPp,
                                   @Value("${admin.insights.rate-drop-min-attempts:30}") long rateDropMinAttempts,
                                   @Value("${admin.insights.risk-min:1}") long riskMin,
                                   @Value("${admin.insights.single-passkey-ratio:0.5}") double singleRatio,
                                   @Value("${admin.insights.single-passkey-min-users:10}") long singleMinUsers) {
        this.em = em;
        this.tenantRepo = tenantRepo;
        this.clock = clock;
        this.thresholds = new PlatformInsightsDto.Thresholds(rateDropPp, rateDropMinAttempts, riskMin, singleRatio, singleMinUsers);
    }

    @Transactional(readOnly = true)
    public PlatformInsightsDto.View compute(int windowDays) {
        OffsetDateTime now = OffsetDateTime.now(clock.withZone(KstTime.ZONE));
        OffsetDateTime since = now.minus(windowDays, ChronoUnit.DAYS);
        OffsetDateTime d7 = now.minus(7, ChronoUnit.DAYS);
        OffsetDateTime d14 = now.minus(14, ChronoUnit.DAYS);

        Map<UUID, long[]> cer = ceremonies(since, null);        // [regA, regS, authA, authS]
        Map<UUID, long[]> recent = ceremonies(d7, null);
        Map<UUID, long[]> prior = ceremonies(d14, d7);
        Map<UUID, Long> risk = byTenant("select e.tenantId, count(e) from CredentialAuthEvent e where e.result = " + FAILED
                + " and e.createdAt >= :from group by e.tenantId", since, null);
        Map<UUID, Long> recentRisk = byTenant("select e.tenantId, count(e) from CredentialAuthEvent e where e.result = " + FAILED
                + " and e.createdAt >= :from group by e.tenantId", d7, null);
        Map<UUID, Long> active = activeUsers(since, null);
        Map<UUID, Long> creds = byTenant("select c.tenantId, count(c) from Credential c group by c.tenantId", null, null);
        Map<UUID, long[]> users = new HashMap<>();              // [users, single]
        for (Object[] r : em.createQuery("select h.t, count(*), sum(case when h.n = 1 then 1 else 0 end) from"
                + " (select c.tenantId as t, c.userHandle as u, count(c) as n from Credential c group by c.tenantId, c.userHandle) h"
                + " group by h.t", Object[].class).getResultList()) {
            users.put((UUID) r[0], new long[] {num(r[1]), num(r[2])});
        }

        List<PlatformInsightsDto.TenantRow> rows = new ArrayList<>();
        long[] t = new long[11];
        List<Tenant> tenants = tenantRepo.findAll();
        for (Tenant ten : tenants) {
            UUID id = ten.getId();
            long[] c = cer.getOrDefault(id, new long[4]);
            long[] rc = recent.getOrDefault(id, new long[4]);
            long[] pc = prior.getOrDefault(id, new long[4]);
            long[] u = users.getOrDefault(id, new long[2]);
            PlatformInsightsDto.TenantRow row = new PlatformInsightsDto.TenantRow(id, ten.getDisplayName(), ten.getSlug(), ten.getStatus(),
                    active.getOrDefault(id, 0L), c[2], c[3], c[0], c[1], risk.getOrDefault(id, 0L), creds.getOrDefault(id, 0L), u[0], u[1],
                    rc[2], rc[3], pc[2], pc[3], recentRisk.getOrDefault(id, 0L));
            rows.add(row);
            if ("active".equalsIgnoreCase(ten.getStatus())) t[0]++;
            t[1] += row.activeUsers(); t[2] += row.authAttempts(); t[3] += row.authSuccess(); t[4] += row.regAttempts(); t[5] += row.regSuccess();
            t[6] += row.risk(); t[7] += row.credentials(); t[8] += row.users(); t[9] += row.singlePasskeyUsers();
        }
        rows.sort(Comparator.comparingLong(PlatformInsightsDto.TenantRow::activeUsers).reversed()
                .thenComparing(r -> r.name() == null ? "" : r.name()));
        PlatformInsightsDto.Totals totals = new PlatformInsightsDto.Totals(tenants.size(), t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8], t[9]);

        return new PlatformInsightsDto.View(windowDays, totals, rows, alerts(rows), dailyAuth(since, windowDays), months(now), devices(since), thresholds);
    }

    /** 확인 필요 테넌트. 한 테넌트가 여러 기준에 걸리면 각각 한 줄. 순서: 위험 인증 → 성공률 하락 → 1개뿐 비율. */
    List<PlatformInsightsDto.Alert> alerts(List<PlatformInsightsDto.TenantRow> rows) {
        List<PlatformInsightsDto.Alert> risk = new ArrayList<>();
        List<PlatformInsightsDto.Alert> drop = new ArrayList<>();
        List<PlatformInsightsDto.Alert> single = new ArrayList<>();
        for (PlatformInsightsDto.TenantRow r : rows) {
            if (thresholds.riskMin() > 0 && r.recentRisk() >= thresholds.riskMin()) {
                risk.add(new PlatformInsightsDto.Alert(r.tenantId(), r.name(), r.slug(), "RISK",
                        "최근 7일 차단된 위험 인증 " + r.recentRisk() + "건"));
            }
            if (r.recentAuthAttempts() >= thresholds.rateDropMinAttempts() && r.priorAuthAttempts() > 0) {
                double cur = Math.min(1.0, (double) r.recentAuthSuccess() / r.recentAuthAttempts());
                double prev = Math.min(1.0, (double) r.priorAuthSuccess() / r.priorAuthAttempts());
                double pp = (prev - cur) * 100;
                if (pp >= thresholds.rateDropPp() - 1e-9) { // 경계값(정확히 5%p)이 부동소수 오차로 빠지지 않게
                    drop.add(new PlatformInsightsDto.Alert(r.tenantId(), r.name(), r.slug(), "RATE_DROP",
                            String.format(Locale.ROOT, "인증 성공률 %.1f%% → %.1f%% (%.1f%%p 하락, 최근 7일)", prev * 100, cur * 100, pp)));
                }
            }
            if (r.users() >= thresholds.singleMinUsers() && r.users() > 0
                    && (double) r.singlePasskeyUsers() / r.users() >= thresholds.singleRatio()) {
                single.add(new PlatformInsightsDto.Alert(r.tenantId(), r.name(), r.slug(), "SINGLE_PASSKEY",
                        String.format(Locale.ROOT, "패스키 1개뿐인 사용자 %.1f%% (%d명 / %d명)",
                                (double) r.singlePasskeyUsers() / r.users() * 100, r.singlePasskeyUsers(), r.users())));
            }
        }
        List<PlatformInsightsDto.Alert> out = new ArrayList<>(risk);
        out.addAll(drop);
        out.addAll(single);
        return out;
    }

    private Map<UUID, long[]> ceremonies(OffsetDateTime from, OffsetDateTime to) {
        Map<UUID, long[]> out = new HashMap<>();
        var q = em.createQuery("select e.tenantId, e.action, count(e) from CeremonyEvent e where e.action in :actions and e.createdAt >= :from"
                + (to == null ? "" : " and e.createdAt < :to") + " group by e.tenantId, e.action", Object[].class)
                .setParameter("actions", List.of(CeremonyAction.REGISTRATION_BEGIN, CeremonyAction.REGISTRATION_SUCCESS,
                        CeremonyAction.AUTHENTICATION_BEGIN, CeremonyAction.AUTHENTICATION_SUCCESS))
                .setParameter("from", from);
        if (to != null) q.setParameter("to", to);
        for (Object[] r : q.getResultList()) {
            long[] cell = out.computeIfAbsent((UUID) r[0], k -> new long[4]);
            switch ((String) r[1]) {
                case CeremonyAction.REGISTRATION_BEGIN -> cell[0] += num(r[2]);
                case CeremonyAction.REGISTRATION_SUCCESS -> cell[1] += num(r[2]);
                case CeremonyAction.AUTHENTICATION_BEGIN -> cell[2] += num(r[2]);
                case CeremonyAction.AUTHENTICATION_SUCCESS -> cell[3] += num(r[2]);
                default -> { }
            }
        }
        return out;
    }

    private Map<UUID, Long> activeUsers(OffsetDateTime from, OffsetDateTime to) {
        return byTenant("select e.tenantId, count(distinct c.userHandle) from CredentialAuthEvent e, Credential c"
                + " where c.id = e.credentialId and e.result = " + OK + " and e.createdAt >= :from"
                + (to == null ? "" : " and e.createdAt < :to") + " group by e.tenantId", from, to);
    }

    private Map<UUID, Long> byTenant(String jpql, OffsetDateTime from, OffsetDateTime to) {
        var q = em.createQuery(jpql, Object[].class);
        if (from != null) q.setParameter("from", from);
        if (to != null) q.setParameter("to", to);
        Map<UUID, Long> out = new HashMap<>();
        for (Object[] r : q.getResultList()) out.put((UUID) r[0], num(r[1]));
        return out;
    }

    /** 플랫폼 일별 인증 시도/성공. FunnelService 와 같은 TRUNC(created_at) + KST 재해석. */
    private List<FunnelDto.DailyPoint> dailyAuth(OffsetDateTime since, int windowDays) {
        Map<LocalDate, long[]> byDay = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("SELECT TRUNC(created_at), action, COUNT(*) FROM {h-schema}ceremony_event"
                        + " WHERE action IN (:a, :b) AND created_at >= :since GROUP BY TRUNC(created_at), action")
                .setParameter("a", CeremonyAction.AUTHENTICATION_BEGIN)
                .setParameter("b", CeremonyAction.AUTHENTICATION_SUCCESS)
                .setParameter("since", since)
                .getResultList();
        for (Object[] r : rows) {
            long[] cell = byDay.computeIfAbsent(KstTime.dbDay(r[0]), k -> new long[2]);
            if (CeremonyAction.AUTHENTICATION_BEGIN.equals(r[1])) cell[0] += num(r[2]); else cell[1] += num(r[2]);
        }
        List<FunnelDto.DailyPoint> out = new ArrayList<>(windowDays);
        LocalDate today = LocalDate.now(clock.withZone(KstTime.ZONE));
        for (int i = windowDays - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            long[] cell = byDay.getOrDefault(d, new long[2]);
            out.add(new FunnelDto.DailyPoint(d.toString().substring(5), cell[0], cell[1]));
        }
        return out;
    }

    /** 최근 3개월: 인증 성공, 활성 사용자(테넌트별 distinct user handle 의 합), 신규 패스키. */
    private List<AdoptionDto.Month> months(OffsetDateTime now) {
        List<AdoptionDto.Month> out = new ArrayList<>(MONTHS);
        YearMonth thisMonth = YearMonth.from(now);
        for (int i = MONTHS - 1; i >= 0; i--) {
            YearMonth ym = thisMonth.minusMonths(i);
            OffsetDateTime from = ym.atDay(1).atStartOfDay(KstTime.ZONE).toOffsetDateTime();
            OffsetDateTime to = ym.plusMonths(1).atDay(1).atStartOfDay(KstTime.ZONE).toOffsetDateTime();
            long auths = single("select count(e) from CredentialAuthEvent e where e.result = " + OK
                    + " and e.createdAt >= :from and e.createdAt < :to", from, to);
            long mau = activeUsers(from, to).values().stream().mapToLong(Long::longValue).sum();
            long fresh = single("select count(c) from Credential c where c.createdAt >= :from and c.createdAt < :to", from, to);
            out.add(new AdoptionDto.Month(ym.toString(), i == 0, auths, mau, fresh));
        }
        return out;
    }

    private List<AdoptionDto.Device> devices(OffsetDateTime since) {
        Map<String, long[]> byName = new LinkedHashMap<>();
        for (Object[] r : em.createQuery("select c.aaguid, count(c) from Credential c group by c.aaguid", Object[].class).getResultList()) {
            byName.computeIfAbsent(AuthenticatorNames.name((byte[]) r[0]), k -> new long[2])[0] += num(r[1]);
        }
        for (Object[] r : em.createQuery("select c.aaguid, count(e) from CredentialAuthEvent e, Credential c where c.id = e.credentialId"
                + " and e.result = " + OK + " and e.createdAt >= :from group by c.aaguid", Object[].class)
                .setParameter("from", since).getResultList()) {
            byName.computeIfAbsent(AuthenticatorNames.name((byte[]) r[0]), k -> new long[2])[1] += num(r[1]);
        }
        List<AdoptionDto.Device> out = new ArrayList<>();
        byName.forEach((n, v) -> out.add(new AdoptionDto.Device(n, v[0], v[1])));
        out.sort((a, b) -> a.credentials() != b.credentials() ? Long.compare(b.credentials(), a.credentials()) : a.name().compareTo(b.name()));
        return out;
    }

    private long single(String jpql, OffsetDateTime from, OffsetDateTime to) {
        Long n = em.createQuery(jpql, Long.class).setParameter("from", from).setParameter("to", to).getSingleResult();
        return n == null ? 0 : n;
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }
}
