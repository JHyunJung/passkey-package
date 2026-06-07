package com.crosscert.passkey.admin.funnel;

import com.crosscert.passkey.admin.auth.TenantBoundary;
import com.crosscert.passkey.core.ceremony.CeremonyAction;
import com.crosscert.passkey.core.repository.CeremonyEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
    private final TenantBoundary tenantBoundary;

    public FunnelService(CeremonyEventRepository repo, TenantBoundary tenantBoundary) {
        this.repo = repo;
        this.tenantBoundary = tenantBoundary;
    }

    @Transactional(readOnly = true)
    public FunnelDto.View compute(UUID tenantId, int windowDays) {
        // RP_ADMIN must not query other tenants' funnels. PLATFORM_OPERATOR is unrestricted.
        tenantBoundary.assertCanAccessTenant(tenantId);
        Instant since = Instant.now().minus(windowDays, ChronoUnit.DAYS);

        long regAttempts  = repo.countByTenantIdAndActionAndCreatedAtAfter(tenantId, REGISTRATION_BEGIN, since);
        long regSuccess   = repo.countByTenantIdAndActionAndCreatedAtAfter(tenantId, REGISTRATION_SUCCESS, since);
        long authAttempts = repo.countByTenantIdAndActionAndCreatedAtAfter(tenantId, AUTHENTICATION_BEGIN, since);
        long authSuccess  = repo.countByTenantIdAndActionAndCreatedAtAfter(tenantId, AUTHENTICATION_SUCCESS, since);

        FunnelDto.Stage registration   = new FunnelDto.Stage(regAttempts,  regSuccess,  ratio(regSuccess,  regAttempts));
        FunnelDto.Stage authentication = new FunnelDto.Stage(authAttempts, authSuccess, ratio(authSuccess, authAttempts));

        long totalAttempts = regAttempts + authAttempts;
        long totalSuccess  = regSuccess + authSuccess;
        double conversion  = ratio(totalSuccess, totalAttempts);

        List<FunnelDto.DailyPoint> series = buildSeries(tenantId, since, windowDays);
        List<FunnelDto.EventCount> byType = buildByEventType(tenantId, since);

        return new FunnelDto.View(windowDays, registration, authentication, conversion, series, byType);
    }

    private double ratio(long success, long attempts) {
        return attempts == 0 ? 0.0 : (double) success / (double) attempts;
    }

    private List<FunnelDto.DailyPoint> buildSeries(UUID tenantId, Instant since, int windowDays) {
        Map<LocalDate, long[]> byDay = new HashMap<>();
        for (Object[] row : repo.aggregateDailyByTenantAndActions(
                tenantId,
                List.of(REGISTRATION_BEGIN, AUTHENTICATION_BEGIN, REGISTRATION_SUCCESS, AUTHENTICATION_SUCCESS),
                since)) {
            // row[0] = TRUNC(created_at) (Oracle DATE -> java.sql.Timestamp), row[1] = action, row[2] = count
            Object dayCol = row[0];
            Instant dayInstant = dayCol instanceof Timestamp ts
                    ? ts.toInstant()
                    : ((java.util.Date) dayCol).toInstant();
            LocalDate day = dayInstant.atZone(ZoneOffset.UTC).toLocalDate();
            String action = (String) row[1];
            long count = ((Number) row[2]).longValue();
            long[] cell = byDay.computeIfAbsent(day, k -> new long[]{0L, 0L});
            if (action.equals(REGISTRATION_BEGIN) || action.equals(AUTHENTICATION_BEGIN)) {
                cell[0] += count;
            } else {
                cell[1] += count;
            }
        }
        List<FunnelDto.DailyPoint> series = new ArrayList<>(windowDays);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int i = windowDays - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            long[] cell = byDay.getOrDefault(d, new long[]{0L, 0L});
            // day label as "MM-DD"
            String label = d.toString().substring(5);
            series.add(new FunnelDto.DailyPoint(label, cell[0], cell[1]));
        }
        return series;
    }

    private List<FunnelDto.EventCount> buildByEventType(UUID tenantId, Instant since) {
        return repo.aggregateByTenantAndActionsGrouped(
                tenantId,
                List.of(REGISTRATION_BEGIN, REGISTRATION_SUCCESS, AUTHENTICATION_BEGIN, AUTHENTICATION_SUCCESS),
                since)
                .stream()
                .map(row -> new FunnelDto.EventCount((String) row[0], ((Number) row[1]).longValue()))
                .toList();
    }
}
