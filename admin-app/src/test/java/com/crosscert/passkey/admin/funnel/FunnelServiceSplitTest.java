package com.crosscert.passkey.admin.funnel;

import com.crosscert.passkey.admin.auth.TenantBoundary;
import com.crosscert.passkey.core.ceremony.CeremonyAction;
import com.crosscert.passkey.core.ceremony.CredentialAuthResult;
import com.crosscert.passkey.core.config.KstTime;
import com.crosscert.passkey.core.repository.CeremonyEventRepository;
import com.crosscert.passkey.core.repository.CredentialAuthEventRepository;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 인증 현황 탭용: 일별 값을 등록·인증으로 나누고(기존 series 는 합계 그대로), 인증 실패를 원인별로 센다. */
class FunnelServiceSplitTest {

    private final CeremonyEventRepository repo = mock(CeremonyEventRepository.class);
    private final CredentialAuthEventRepository authEvents = mock(CredentialAuthEventRepository.class);
    private final FunnelService service = new FunnelService(repo, authEvents, mock(TenantBoundary.class));

    @Test
    void splitsDailySeriesAndCountsFailures() {
        UUID tenant = UUID.randomUUID();
        LocalDate today = LocalDate.now(KstTime.ZONE);
        Timestamp day = Timestamp.from(today.atStartOfDay(KstTime.ZONE).toInstant());
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] {day, CeremonyAction.REGISTRATION_BEGIN, 4L});
        rows.add(new Object[] {day, CeremonyAction.REGISTRATION_SUCCESS, 3L});
        rows.add(new Object[] {day, CeremonyAction.AUTHENTICATION_BEGIN, 10L});
        rows.add(new Object[] {day, CeremonyAction.AUTHENTICATION_SUCCESS, 9L});
        when(repo.aggregateDailyByTenantAndActions(eq(tenant), any(), any())).thenReturn(rows);
        when(repo.aggregateByTenantAndActionsGrouped(any(), any(), any())).thenReturn(List.of());
        when(repo.countByTenantIdAndActionAndCreatedAtAfter(any(), anyString(), any())).thenReturn(0L);
        List<Object[]> fails = new ArrayList<>();
        fails.add(new Object[] {"SIGNATURE_INVALID", 5L});
        fails.add(new Object[] {null, 1L});
        when(authEvents.countByReason(eq(tenant), eq(CredentialAuthResult.FAILED), any())).thenReturn(fails);

        FunnelDto.View v = service.compute(tenant, 7);

        String label = today.toString().substring(5);
        assertThat(v.getRegistrationSeries()).hasSize(7);
        assertThat(v.getAuthenticationSeries()).hasSize(7);
        assertThat(v.getRegistrationSeries().get(6)).isEqualTo(new FunnelDto.DailyPoint(label, 4, 3));
        assertThat(v.getAuthenticationSeries().get(6)).isEqualTo(new FunnelDto.DailyPoint(label, 10, 9));
        // 기존 JSON 의 series 는 합계 그대로
        assertThat(v.getSeries().get(6)).isEqualTo(new FunnelDto.DailyPoint(label, 14, 12));
        assertThat(v.getRegistrationSeries().get(0).getAttempts()).isZero();
        assertThat(v.getAuthFailures()).containsExactly(new FunnelDto.FailureCount("SIGNATURE_INVALID", 5), new FunnelDto.FailureCount(null, 1));
    }
}
