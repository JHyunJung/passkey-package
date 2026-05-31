package com.crosscert.passkey.admin.retention;

import com.crosscert.passkey.admin.mds.MdsHistoryService;
import com.crosscert.passkey.core.repository.AdminPasswordResetTokenRepository;
import com.crosscert.passkey.core.repository.AdminUserInvitationRepository;
import com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository;
import com.crosscert.passkey.core.repository.TenantWebauthnSnapshotRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * P1-4 데이터 retention purge. 테이블별 독립 메서드로 — RetentionPurgeJob 이
 * 각각을 try/catch 로 격리 호출한다(한 테이블 실패가 나머지를 막지 않음).
 * 각 메서드는 cutoff 이전의 비활성 행만 삭제(활성 데이터는 쿼리 조건상 보존).
 */
@Service
public class RetentionPurgeService {

    private final AdminUserInvitationRepository invitations;
    private final AdminPasswordResetTokenRepository resetTokens;
    private final AdminUserRecoveryCodeRepository recoveryCodes;
    private final TenantWebauthnSnapshotRepository snapshots;
    private final MdsHistoryService mdsHistory;

    public RetentionPurgeService(AdminUserInvitationRepository invitations,
                                 AdminPasswordResetTokenRepository resetTokens,
                                 AdminUserRecoveryCodeRepository recoveryCodes,
                                 TenantWebauthnSnapshotRepository snapshots,
                                 MdsHistoryService mdsHistory) {
        this.invitations = invitations;
        this.resetTokens = resetTokens;
        this.recoveryCodes = recoveryCodes;
        this.snapshots = snapshots;
        this.mdsHistory = mdsHistory;
    }

    public int purgeInvitations(Instant cutoff) {
        return invitations.deleteConsumedOrExpiredBefore(cutoff);
    }

    public int purgeResetTokens(Instant cutoff) {
        return resetTokens.deleteConsumedOrExpiredBefore(cutoff);
    }

    public int purgeRecoveryCodes(Instant cutoff) {
        return recoveryCodes.deleteUsedBefore(cutoff);
    }

    public int purgeSnapshots(Instant cutoff) {
        return snapshots.deleteTakenBefore(cutoff);
    }

    public int purgeMdsHistory(Instant cutoff) {
        return mdsHistory.purgeStartedBefore(cutoff);
    }
}
