package com.crosscert.passkey.admin.retention;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.scheduler.SchedulerLeaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RetentionPurgeJobTest {

    @Mock RetentionPurgeService service;
    @Mock SchedulerLeaseService leases;
    @Mock AuditLogService audit;
    Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
    RetentionPurgeJob job;

    @BeforeEach
    void setUp() {
        job = new RetentionPurgeJob(service, leases, audit, clock,
                Duration.ofDays(90), Duration.ofDays(30), Duration.ofDays(180),
                Duration.ofDays(365), Duration.ofDays(90));
    }

    @Test
    void skips_when_lease_not_acquired() {
        when(leases.tryAcquire(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        job.runOnce();
        verifyNoInteractions(service);
        verifyNoInteractions(audit);
    }

    @Test
    void one_table_failure_does_not_block_others_and_audits() {
        when(leases.tryAcquire(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(service.purgeInvitations(any())).thenReturn(2);
        when(service.purgeResetTokens(any())).thenThrow(new RuntimeException("db error"));
        when(service.purgeRecoveryCodes(any())).thenReturn(1);
        when(service.purgeSnapshots(any())).thenReturn(0);
        when(service.purgeMdsHistory(any())).thenReturn(4);

        job.runOnce();

        verify(service).purgeInvitations(any());
        verify(service).purgeResetTokens(any());
        verify(service).purgeRecoveryCodes(any());
        verify(service).purgeSnapshots(any());
        verify(service).purgeMdsHistory(any());
        ArgumentCaptor<AuditAppendRequest> cap = ArgumentCaptor.forClass(AuditAppendRequest.class);
        verify(audit).append(cap.capture());
        assertThat(cap.getValue().action()).isEqualTo("RETENTION_PURGE");
        assertThat(cap.getValue().payload().get("invitationsPurged")).isEqualTo(2);
        // sentinel 제거: 실패한 테이블은 count 키 자체가 없다(합산 오염 방지).
        assertThat(cap.getValue().payload()).doesNotContainKey("resetTokensPurged");
        assertThat(cap.getValue().payload().get("failed").toString()).contains("resetTokens");
        // lease 는 정상·예외 어느 경로든 즉시 반환.
        verify(leases).release(anyString(), anyString());
    }

    @Test
    void all_success_audits_every_count_and_empty_failed() {
        when(leases.tryAcquire(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(service.purgeInvitations(any())).thenReturn(5);
        when(service.purgeResetTokens(any())).thenReturn(3);
        when(service.purgeRecoveryCodes(any())).thenReturn(0);
        when(service.purgeSnapshots(any())).thenReturn(7);
        when(service.purgeMdsHistory(any())).thenReturn(2);

        job.runOnce();

        ArgumentCaptor<AuditAppendRequest> cap = ArgumentCaptor.forClass(AuditAppendRequest.class);
        verify(audit).append(cap.capture());
        var payload = cap.getValue().payload();
        assertThat(payload.get("invitationsPurged")).isEqualTo(5);
        assertThat(payload.get("resetTokensPurged")).isEqualTo(3);
        assertThat(payload.get("recoveryCodesPurged")).isEqualTo(0);
        assertThat(payload.get("snapshotsPurged")).isEqualTo(7);
        assertThat(payload.get("mdsHistoryPurged")).isEqualTo(2);
        assertThat((java.util.List<?>) payload.get("failed")).isEmpty();
        verify(leases).release(anyString(), anyString());
    }

    @Test
    void all_zero_still_audits_with_zero_counts_and_empty_failed() {
        when(leases.tryAcquire(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(service.purgeInvitations(any())).thenReturn(0);
        when(service.purgeResetTokens(any())).thenReturn(0);
        when(service.purgeRecoveryCodes(any())).thenReturn(0);
        when(service.purgeSnapshots(any())).thenReturn(0);
        when(service.purgeMdsHistory(any())).thenReturn(0);

        job.runOnce();

        ArgumentCaptor<AuditAppendRequest> cap = ArgumentCaptor.forClass(AuditAppendRequest.class);
        verify(audit).append(cap.capture());
        var payload = cap.getValue().payload();
        assertThat(payload.get("invitationsPurged")).isEqualTo(0);
        assertThat(payload.get("resetTokensPurged")).isEqualTo(0);
        assertThat(payload.get("recoveryCodesPurged")).isEqualTo(0);
        assertThat(payload.get("snapshotsPurged")).isEqualTo(0);
        assertThat(payload.get("mdsHistoryPurged")).isEqualTo(0);
        assertThat((java.util.List<?>) payload.get("failed")).isEmpty();
        verify(leases).release(anyString(), anyString());
    }
}
