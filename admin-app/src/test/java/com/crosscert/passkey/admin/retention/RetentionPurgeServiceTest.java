package com.crosscert.passkey.admin.retention;

import com.crosscert.passkey.admin.mds.MdsHistoryService;
import com.crosscert.passkey.core.repository.AdminPasswordResetTokenRepository;
import com.crosscert.passkey.core.repository.AdminUserInvitationRepository;
import com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository;
import com.crosscert.passkey.core.repository.CeremonyEventRepository;
import com.crosscert.passkey.core.repository.CredentialAuthEventRepository;
import com.crosscert.passkey.core.repository.TenantWebauthnSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetentionPurgeServiceTest {

    @Mock AdminUserInvitationRepository invitations;
    @Mock AdminPasswordResetTokenRepository resetTokens;
    @Mock AdminUserRecoveryCodeRepository recoveryCodes;
    @Mock TenantWebauthnSnapshotRepository snapshots;
    @Mock MdsHistoryService mdsHistory;
    @Mock CeremonyEventRepository ceremonyEvents;
    @Mock CredentialAuthEventRepository credentialAuthEvents;
    RetentionPurgeService service;

    @BeforeEach
    void setUp() {
        service = new RetentionPurgeService(
                invitations, resetTokens, recoveryCodes, snapshots, mdsHistory,
                ceremonyEvents, credentialAuthEvents);
    }

    @Test
    void purgeInvitations_delegates_cutoff_and_returns_count() {
        Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");
        // 3 < BATCH(1000) 이므로 do/while 루프는 1회만 돌고 종료.
        when(invitations.deleteConsumedOrExpiredBefore(eq(cutoff), anyInt())).thenReturn(3);
        assertThat(service.purgeInvitations(cutoff)).isEqualTo(3);
    }

    @Test
    void purgeRecoveryCodes_delegates_to_used_before() {
        Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");
        when(recoveryCodes.deleteUsedBefore(eq(cutoff), anyInt())).thenReturn(7);
        assertThat(service.purgeRecoveryCodes(cutoff)).isEqualTo(7);
    }

    @Test
    void purgeMdsHistory_delegates_to_history_service() {
        Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");
        when(mdsHistory.purgeStartedBefore(eq(cutoff), anyInt())).thenReturn(5);
        assertThat(service.purgeMdsHistory(cutoff)).isEqualTo(5);
    }

    @Test
    void purgeCeremonyEvents_delegates_to_created_before() {
        Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");
        when(ceremonyEvents.deleteCreatedBefore(eq(cutoff), anyInt())).thenReturn(9);
        assertThat(service.purgeCeremonyEvents(cutoff)).isEqualTo(9);
    }

    @Test
    void purgeCredentialAuthEvents_delegates_to_created_before() {
        Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");
        when(credentialAuthEvents.deleteCreatedBefore(eq(cutoff), anyInt())).thenReturn(4);
        assertThat(service.purgeCredentialAuthEvents(cutoff)).isEqualTo(4);
    }

    @Test
    void purge_loops_until_batch_not_full_and_sums_totals() {
        Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");
        // 1회차 full batch(1000) → 반복, 2회차 250(<1000) → 종료. 총 1250.
        when(invitations.deleteConsumedOrExpiredBefore(eq(cutoff), anyInt()))
                .thenReturn(RetentionPurgeService.BATCH, 250);
        assertThat(service.purgeInvitations(cutoff))
                .isEqualTo(RetentionPurgeService.BATCH + 250);
    }
}
