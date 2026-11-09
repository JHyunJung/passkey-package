package com.crosscert.passkey.admin.retention

import com.crosscert.passkey.admin.mds.MdsHistoryService
import com.crosscert.passkey.core.repository.AdminPasswordResetTokenRepository
import com.crosscert.passkey.core.repository.AdminSignupRequestRepository
import com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository
import com.crosscert.passkey.core.repository.CeremonyEventRepository
import com.crosscert.passkey.core.repository.CredentialAuthEventRepository
import com.crosscert.passkey.core.repository.TenantWebauthnSnapshotRepository
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/**
 * P1-4 데이터 retention purge. 테이블별 독립 메서드로 — RetentionPurgeJob 이
 * 각각을 try/catch 로 격리 호출한다(한 테이블 실패가 나머지를 막지 않음).
 * 각 메서드는 cutoff 이전의 비활성 행만 삭제(활성 데이터는 쿼리 조건상 보존).
 *
 * 각 purge 는 ROWNUM 으로 캡된 native batch DELETE 를 [BATCH] 행 미만이
 * 반환될 때까지 반복한다. 첫 prod 실행 시 수개월 누적분을 한 방에 지우는 unbounded
 * DELETE 가 긴 row-lock·undo 를 만들고, 그 사이 scheduler lease TTL 이 만료되면
 * second instance 가 동일 행에 lock 경합하는 것을 방지한다(작은 트랜잭션으로 분할).
 */
@Service
class RetentionPurgeService(
    private val signupRequests: AdminSignupRequestRepository,
    private val resetTokens: AdminPasswordResetTokenRepository,
    private val recoveryCodes: AdminUserRecoveryCodeRepository,
    private val snapshots: TenantWebauthnSnapshotRepository,
    private val mdsHistory: MdsHistoryService,
    private val ceremonyEvents: CeremonyEventRepository,
    private val credentialAuthEvents: CredentialAuthEventRepository,
) {

    /** 보존기간 지난 미처리 가입 요청 삭제 — 방치된 요청이 대기 상한을 영구 점유하지 않게. */
    fun purgeSignupRequests(cutoff: OffsetDateTime): Int =
        purgeInBatches { signupRequests.deleteRequestedBefore(cutoff, BATCH) }

    fun purgeResetTokens(cutoff: OffsetDateTime): Int =
        purgeInBatches { resetTokens.deleteConsumedOrExpiredBefore(cutoff, BATCH) }

    fun purgeRecoveryCodes(cutoff: OffsetDateTime): Int =
        purgeInBatches { recoveryCodes.deleteUsedBefore(cutoff, BATCH) }

    fun purgeSnapshots(cutoff: OffsetDateTime): Int =
        purgeInBatches { snapshots.deleteTakenBefore(cutoff, BATCH) }

    fun purgeMdsHistory(cutoff: OffsetDateTime): Int =
        purgeInBatches { mdsHistory.purgeStartedBefore(cutoff, BATCH) }

    fun purgeCeremonyEvents(cutoff: OffsetDateTime): Int =
        purgeInBatches { ceremonyEvents.deleteCreatedBefore(cutoff, BATCH) }

    fun purgeCredentialAuthEvents(cutoff: OffsetDateTime): Int =
        purgeInBatches { credentialAuthEvents.deleteCreatedBefore(cutoff, BATCH) }

    /** 한 batch 가 [BATCH] 행을 지우면 아직 남았다는 뜻이므로 반복, 미만이면 종료. 총 삭제 건수 반환. */
    private inline fun purgeInBatches(deleteBatch: () -> Int): Int {
        var total = 0
        var n: Int
        do {
            n = deleteBatch()
            total += n
        } while (n == BATCH)
        return total
    }

    companion object {
        /**
         * 한 batch DELETE(=한 native 쿼리)가 지우는 최대 행 수. repository ROWNUM 캡과 동일.
         * 반환 행 수가 BATCH 와 같으면 아직 남았다는 뜻이므로 반복, 미만이면 종료.
         */
        internal const val BATCH = 1000
    }
}
