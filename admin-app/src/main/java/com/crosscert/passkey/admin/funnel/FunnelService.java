package com.crosscert.passkey.admin.funnel;

import com.crosscert.passkey.admin.auth.TenantBoundary;
import com.crosscert.passkey.core.ceremony.CeremonyAction;
import com.crosscert.passkey.core.ceremony.CredentialAuthResult;
import com.crosscert.passkey.core.repository.CredentialAuthEventRepository;
import com.crosscert.passkey.core.repository.CeremonyEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crosscert.passkey.core.config.KstTime;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase F3 — Aggregates ceremony_event rows into a registration/authentication
 * funnel view for the admin Funnel page (Gap #3/#9).
 *
 * <p>Action constants follow spec § F3.1 via {@link CeremonyAction} (single
 * source of truth shared with passkey-app's recorder).
 *
 * <p>Counts come from {@code ceremony_event}, populated by passkey-app's
 * registration/authentication ceremonies (begin/finish). Empty tenants return
 * 0 counts and the DTO contract yields ratio 0.
 */
@Service
public class FunnelService {

    static final String REGISTRATION_BEGIN     = CeremonyAction.REGISTRATION_BEGIN;
    static final String REGISTRATION_SUCCESS   = CeremonyAction.REGISTRATION_SUCCESS;
    static final String AUTHENTICATION_BEGIN   = CeremonyAction.AUTHENTICATION_BEGIN;
    static final String AUTHENTICATION_SUCCESS = CeremonyAction.AUTHENTICATION_SUCCESS;

    private final CeremonyEventRepository repo;
    private final CredentialAuthEventRepository authEvents;
    private final TenantBoundary tenantBoundary;

    public FunnelService(CeremonyEventRepository repo, CredentialAuthEventRepository authEvents, TenantBoundary tenantBoundary) {
        this.repo = repo;
        this.authEvents = authEvents;
        this.tenantBoundary = tenantBoundary;
    }

    @Transactional(readOnly = true)
    public FunnelDto.View compute(UUID tenantId, int windowDays) {
        // RP_ADMIN must not query other tenants' funnels. PLATFORM_OPERATOR is unrestricted.
        tenantBoundary.assertCanAccessTenant(tenantId);
        OffsetDateTime since = OffsetDateTime.now(KstTime.ZONE).minus(windowDays, ChronoUnit.DAYS);

        long regAttempts  = repo.countByTenantIdAndActionAndCreatedAtAfter(tenantId, REGISTRATION_BEGIN, since);
        long regSuccess   = repo.countByTenantIdAndActionAndCreatedAtAfter(tenantId, REGISTRATION_SUCCESS, since);
        long authAttempts = repo.countByTenantIdAndActionAndCreatedAtAfter(tenantId, AUTHENTICATION_BEGIN, since);
        long authSuccess  = repo.countByTenantIdAndActionAndCreatedAtAfter(tenantId, AUTHENTICATION_SUCCESS, since);

        FunnelDto.Stage registration   = new FunnelDto.Stage(regAttempts,  regSuccess,  ratio(regSuccess,  regAttempts));
        FunnelDto.Stage authentication = new FunnelDto.Stage(authAttempts, authSuccess, ratio(authSuccess, authAttempts));

        long totalAttempts = regAttempts + authAttempts;
        long totalSuccess  = regSuccess + authSuccess;
        double conversion  = ratio(totalSuccess, totalAttempts);

        Daily daily = buildSeries(tenantId, since, windowDays);
        List<FunnelDto.EventCount> byType = buildByEventType(tenantId, since);
        List<FunnelDto.FailureCount> failures = authEvents.countByReason(tenantId, CredentialAuthResult.FAILED, since).stream()
                .map(row -> new FunnelDto.FailureCount((String) row[0], ((Number) row[1]).longValue()))
                .toList();

        return new FunnelDto.View(windowDays, registration, authentication, conversion, daily.combined(), byType,
                daily.registration(), daily.authentication(), failures);
    }

    private double ratio(long success, long attempts) {
        if (attempts == 0) return 0.0;
        double r = (double) success / (double) attempts;
        // 윈도우 경계에서 begin 은 잘리고 finish 만 포함되면 success>attempts 가 될 수 있다.
        // 성공률 의미상 100% 를 넘을 수 없으므로 1.0 으로 클램프한다.
        return Math.min(r, 1.0);
    }

    /** 같은 날짜 축의 일별 값 3종: 합계(기존 series), 등록만, 인증만. */
    private record Daily(List<FunnelDto.DailyPoint> combined,
                         List<FunnelDto.DailyPoint> registration,
                         List<FunnelDto.DailyPoint> authentication) {}

    private Daily buildSeries(UUID tenantId, OffsetDateTime since, int windowDays) {
        Map<LocalDate, long[]> byDay = new HashMap<>();
        for (Object[] row : repo.aggregateDailyByTenantAndActions(
                tenantId,
                List.of(REGISTRATION_BEGIN, AUTHENTICATION_BEGIN, REGISTRATION_SUCCESS, AUTHENTICATION_SUCCESS),
                since)) {
            // row[0] = TRUNC(created_at) (Oracle DATE; Hibernate 7 -> LocalDateTime), row[1] = action, row[2] = count
            // F06: reinterpret in KST — the same timezone the DB session used to
            // compute TRUNC(created_at) (JVM TZ=Asia/Seoul, hibernate.jdbc.time_zone=
            // Asia/Seoul). Reinterpreting in UTC shifted every daily bucket back by
            // one day and, for KST 00:00–09:00 queries, pushed today's freshest
            // data out of the rendering window below entirely.
            LocalDate day = KstTime.dbDay(row[0]);
            String action = (String) row[1];
            long count = ((Number) row[2]).longValue();
            // cell = [등록 시도, 등록 성공, 인증 시도, 인증 성공]
            long[] cell = byDay.computeIfAbsent(day, k -> new long[4]);
            switch (action) {
                case REGISTRATION_BEGIN -> cell[0] += count;
                case REGISTRATION_SUCCESS -> cell[1] += count;
                case AUTHENTICATION_BEGIN -> cell[2] += count;
                case AUTHENTICATION_SUCCESS -> cell[3] += count;
                default -> { }
            }
        }
        List<FunnelDto.DailyPoint> combined = new ArrayList<>(windowDays);
        List<FunnelDto.DailyPoint> registration = new ArrayList<>(windowDays);
        List<FunnelDto.DailyPoint> authentication = new ArrayList<>(windowDays);
        LocalDate today = LocalDate.now(KstTime.ZONE);
        for (int i = windowDays - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            long[] cell = byDay.getOrDefault(d, new long[4]);
            // day label as "MM-DD"
            String label = d.toString().substring(5);
            combined.add(new FunnelDto.DailyPoint(label, cell[0] + cell[2], cell[1] + cell[3]));
            registration.add(new FunnelDto.DailyPoint(label, cell[0], cell[1]));
            authentication.add(new FunnelDto.DailyPoint(label, cell[2], cell[3]));
        }
        return new Daily(combined, registration, authentication);
    }

    private List<FunnelDto.EventCount> buildByEventType(UUID tenantId, OffsetDateTime since) {
        return repo.aggregateByTenantAndActionsGrouped(
                tenantId,
                List.of(REGISTRATION_BEGIN, REGISTRATION_SUCCESS, AUTHENTICATION_BEGIN, AUTHENTICATION_SUCCESS),
                since)
                .stream()
                .map(row -> new FunnelDto.EventCount((String) row[0], ((Number) row[1]).longValue()))
                .toList();
    }
}
