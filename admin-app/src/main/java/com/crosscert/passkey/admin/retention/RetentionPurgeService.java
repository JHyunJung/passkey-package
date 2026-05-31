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
 *
 * <p>각 purge 는 ROWNUM 으로 캡된 native batch DELETE 를 {@link #BATCH} 행 미만이
 * 반환될 때까지 반복한다. 첫 prod 실행 시 수개월 누적분을 한 방에 지우는 unbounded
 * DELETE 가 긴 row-lock·undo 를 만들고, 그 사이 scheduler lease TTL 이 만료되면
 * second instance 가 동일 행에 lock 경합하는 것을 방지한다(작은 트랜잭션으로 분할).
 */
@Service
public class RetentionPurgeService {

    /**
     * 한 batch DELETE(=한 native 쿼리)가 지우는 최대 행 수. repository ROWNUM 캡과 동일.
     * 반환 행 수가 BATCH 와 같으면 아직 남았다는 뜻이므로 반복, 미만이면 종료.
     */
    static final int BATCH = 1000;

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
        int total = 0, n;
        do {
            n = invitations.deleteConsumedOrExpiredBefore(cutoff, BATCH);
            total += n;
        } while (n == BATCH);
        return total;
    }

    public int purgeResetTokens(Instant cutoff) {
        int total = 0, n;
        do {
            n = resetTokens.deleteConsumedOrExpiredBefore(cutoff, BATCH);
            total += n;
        } while (n == BATCH);
        return total;
    }

    public int purgeRecoveryCodes(Instant cutoff) {
        int total = 0, n;
        do {
            n = recoveryCodes.deleteUsedBefore(cutoff, BATCH);
            total += n;
        } while (n == BATCH);
        return total;
    }

    public int purgeSnapshots(Instant cutoff) {
        int total = 0, n;
        do {
            n = snapshots.deleteTakenBefore(cutoff, BATCH);
            total += n;
        } while (n == BATCH);
        return total;
    }

    public int purgeMdsHistory(Instant cutoff) {
        int total = 0, n;
        do {
            n = mdsHistory.purgeStartedBefore(cutoff, BATCH);
            total += n;
        } while (n == BATCH);
        return total;
    }
}
