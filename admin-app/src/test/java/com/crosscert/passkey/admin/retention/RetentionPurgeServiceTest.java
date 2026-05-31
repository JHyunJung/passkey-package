package com.crosscert.passkey.admin.retention;

import com.crosscert.passkey.admin.mds.MdsHistoryService;
import com.crosscert.passkey.core.repository.AdminPasswordResetTokenRepository;
import com.crosscert.passkey.core.repository.AdminUserInvitationRepository;
import com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository;
import com.crosscert.passkey.core.repository.TenantWebauthnSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetentionPurgeServiceTest {

    @Mock AdminUserInvitationRepository invitations;
    @Mock AdminPasswordResetTokenRepository resetTokens;
    @Mock AdminUserRecoveryCodeRepository recoveryCodes;
    @Mock TenantWebauthnSnapshotRepository snapshots;
    @Mock MdsHistoryService mdsHistory;
    RetentionPurgeService service;

    @BeforeEach
    void setUp() {
        service = new RetentionPurgeService(invitations, resetTokens, recoveryCodes, snapshots, mdsHistory);
    }

    @Test
    void purgeInvitations_delegates_cutoff_and_returns_count() {
        Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");
        when(invitations.deleteConsumedOrExpiredBefore(eq(cutoff))).thenReturn(3);
        assertThat(service.purgeInvitations(cutoff)).isEqualTo(3);
    }

    @Test
    void purgeRecoveryCodes_delegates_to_used_before() {
        Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");
        when(recoveryCodes.deleteUsedBefore(eq(cutoff))).thenReturn(7);
        assertThat(service.purgeRecoveryCodes(cutoff)).isEqualTo(7);
    }

    @Test
    void purgeMdsHistory_delegates_to_history_service() {
        Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");
        when(mdsHistory.purgeStartedBefore(eq(cutoff))).thenReturn(5);
        assertThat(service.purgeMdsHistory(cutoff)).isEqualTo(5);
    }
}
