package com.crosscert.passkey.core.ceremony;

import com.crosscert.passkey.core.entity.CredentialAuthEvent;
import com.crosscert.passkey.core.repository.CredentialAuthEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CredentialAuthEventRecorderTest {

    private final CredentialAuthEventRepository repo = mock(CredentialAuthEventRepository.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);

    private CredentialAuthEventRecorder newRecorder() {
        // TransactionTemplate.executeWithoutResult 가 즉시 콜백 실행하도록 getTransaction stub
        when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        return new CredentialAuthEventRecorder(repo, tx);
    }

    @Test
    void recordSavesEvent() {
        CredentialAuthEventRecorder r = newRecorder();
        r.record(UUID.randomUUID(), UUID.randomUUID(), CredentialAuthResult.SUCCESS, null, 5);
        verify(repo).save(any(CredentialAuthEvent.class));
    }

    @Test
    void recordSwallowsRepositoryFailure() {
        CredentialAuthEventRecorder r = newRecorder();
        doThrow(new RuntimeException("db down")).when(repo).save(any());
        // 예외가 전파되지 않아야 한다 (best-effort)
        assertThatCode(() -> r.record(UUID.randomUUID(), UUID.randomUUID(),
                CredentialAuthResult.FAILED, CredentialAuthFailureReason.SIGN_COUNT_REPLAY, 0))
                .doesNotThrowAnyException();
        verify(repo).save(any(CredentialAuthEvent.class));
    }

    /**
     * 핵심 회귀 가드: 기록 실패는 save 시점이 아니라 <b>커밋 시점</b>에 터지는 경우가
     * 가장 위험하다(JPA flush 지연으로 INSERT 가 커밋 때 실행되거나 제약 위반). naive
     * 한 메서드 내 try/catch 는 트랜잭션 경계 밖에서 던져지는 이 예외를 잡지 못하고
     * 호출자(인증 ceremony)로 전파시킨다. CredentialAuthEventRecorder 는
     * TransactionTemplate 호출 자체를 try/catch 로 감싸 커밋 시점 예외까지 삼킨다.
     * CeremonyEventRecorderTest.record_swallowsExceptionFromTransaction 패턴과 동일.
     */
    @Test
    void recordSwallowsCommitTimeFailure() {
        CredentialAuthEventRecorder r = newRecorder();
        // 커밋 경계에서 rollback-only 가 표면화되는 상황을 시뮬레이션.
        // UnexpectedRollbackException 은 RuntimeException 이며, 경계 수준 catch 가 삼킨다.
        doThrow(new UnexpectedRollbackException("commit failed")).when(tx).commit(any());

        assertThatCode(() -> r.record(UUID.randomUUID(), UUID.randomUUID(),
                CredentialAuthResult.FAILED, CredentialAuthFailureReason.SIGNATURE_INVALID, 0))
                .doesNotThrowAnyException();
        // 콜백이 실제로 실행됐는지도 검증 — "그냥 save 를 안 해서" 통과하는 회귀 방지.
        verify(repo).save(any(CredentialAuthEvent.class));
        // 커밋 경계가 실제로 거쳐졌는지 검증한다. 이게 없으면 TransactionTemplate 을
        // 통째로 제거하고 naive try{save}catch 로 바꾼 회귀도 통과해버린다(save mock 은
        // 안 던지므로). getTransaction+commit 호출은 REQUIRES_NEW 경계를 거쳤다는 증거.
        verify(tx).getTransaction(any());
        verify(tx).commit(any());
    }

    @Test
    void recordAfterCommitFallsBackToImmediateWhenNoTransaction() {
        CredentialAuthEventRecorder r = newRecorder();
        // no active synchronization in a plain unit test → immediate path
        r.recordAfterCommit(UUID.randomUUID(), UUID.randomUUID(), CredentialAuthResult.SUCCESS, null, 7);
        verify(repo).save(any(CredentialAuthEvent.class));
    }

    @AfterEach
    void clearSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * 실패 경로(codex P1): 인증 실패는 outer @Transactional 롤백이라 afterCommit 은
     * 안 불린다. 대신 afterCompletion(STATUS_ROLLED_BACK) 으로 락 해제 후 기록해야
     * PESSIMISTIC_WRITE 부모 행과 REQUIRES_NEW 자식 INSERT 의 FK 락 경합을 피한다.
     * 활성 동기화가 있으면 즉시 record 하지 않고 콜백을 등록만 한다.
     */
    @Test
    void recordAfterRollbackDefersUntilRolledBack() {
        CredentialAuthEventRecorder r = newRecorder();
        TransactionSynchronizationManager.initSynchronization();
        try {
            r.recordAfterRollback(UUID.randomUUID(), UUID.randomUUID(),
                    CredentialAuthResult.FAILED, CredentialAuthFailureReason.SIGNATURE_INVALID, 0);
            // 아직 트랜잭션이 끝나지 않았으므로 즉시 기록되면 안 된다.
            verify(repo, never()).save(any(CredentialAuthEvent.class));

            List<TransactionSynchronization> syncs =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).hasSize(1);

            // 롤백 완료를 시뮬레이션 → 이때 비로소 기록된다.
            syncs.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(repo).save(any(CredentialAuthEvent.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * 커밋 완료(STATUS_COMMITTED)는 실패 이벤트가 아니므로 afterCompletion 이 불려도
     * 기록하지 않는다 — 실패 경로 전용 가드.
     */
    @Test
    void recordAfterRollbackDoesNotRecordOnCommit() {
        CredentialAuthEventRecorder r = newRecorder();
        TransactionSynchronizationManager.initSynchronization();
        try {
            r.recordAfterRollback(UUID.randomUUID(), UUID.randomUUID(),
                    CredentialAuthResult.FAILED, CredentialAuthFailureReason.SIGN_COUNT_REPLAY, 0);
            List<TransactionSynchronization> syncs =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).hasSize(1);

            syncs.get(0).afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            verify(repo, never()).save(any(CredentialAuthEvent.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void recordAfterRollbackFallsBackToImmediateWhenNoTransaction() {
        CredentialAuthEventRecorder r = newRecorder();
        // no active synchronization in a plain unit test → immediate path
        r.recordAfterRollback(UUID.randomUUID(), UUID.randomUUID(),
                CredentialAuthResult.FAILED, CredentialAuthFailureReason.SIGNATURE_INVALID, 0);
        verify(repo).save(any(CredentialAuthEvent.class));
    }

    /**
     * codex P2: 롤백 콜백 경로에서도 best-effort 계약(예외 swallow)이 유지되는지 직접
     * 검증한다. 콜백이 record() 에 위임하므로 간접적으로 보장되지만, 누군가 record() 를
     * 우회하도록 바꾸는 회귀를 막기 위해 롤백 경로에서 명시적으로 가드한다.
     */
    @Test
    void recordAfterRollbackSwallowsFailureOnRolledBack() {
        CredentialAuthEventRecorder r = newRecorder();
        doThrow(new RuntimeException("db down")).when(repo).save(any());
        TransactionSynchronizationManager.initSynchronization();
        try {
            r.recordAfterRollback(UUID.randomUUID(), UUID.randomUUID(),
                    CredentialAuthResult.FAILED, CredentialAuthFailureReason.SIGNATURE_INVALID, 0);
            List<TransactionSynchronization> syncs =
                    TransactionSynchronizationManager.getSynchronizations();
            // 롤백 콜백 실행 시 repo.save 가 던져도 호출자(롤백 동기화)로 전파되면 안 된다.
            assertThatCode(() -> syncs.get(0)
                    .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK))
                    .doesNotThrowAnyException();
            verify(repo).save(any(CredentialAuthEvent.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
