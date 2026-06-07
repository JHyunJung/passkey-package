package com.crosscert.passkey.core.ceremony;

import com.crosscert.passkey.core.entity.CredentialAuthEvent;
import com.crosscert.passkey.core.repository.CredentialAuthEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;

/**
 * credential 인증 이벤트를 best-effort 로 기록한다. 기록 실패가 인증 ceremony 를
 * 깨면 안 된다(CeremonyEventRecorder 와 동일 철학·구조).
 *
 * <p>성공: {@link #recordAfterCommit}(outer 커밋 확정 후). 실패: {@link #recordAfterRollback}
 * (outer 롤백 완료 후, 락 해제 뒤). 인증 실패는 예외→outer 롤백이라 afterCommit 콜백이
 * 안 불린다. 또한 credential 행이 PESSIMISTIC_WRITE 로 락된 상태에서 REQUIRES_NEW
 * 자식 INSERT 가 그 행을 FK 참조하면 부모 락 해제를 기다려 enqueue 대기/교착이 날 수
 * 있다(self-deadlock 유사). afterCompletion(ROLLED_BACK) 시점엔 락이 이미 해제돼 안전.
 */
@Component
public class CredentialAuthEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(CredentialAuthEventRecorder.class);

    private final CredentialAuthEventRepository repo;
    private final TransactionTemplate txTemplate;

    public CredentialAuthEventRecorder(CredentialAuthEventRepository repo,
                                       PlatformTransactionManager txManager) {
        this.repo = repo;
        this.txTemplate = new TransactionTemplate(txManager);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 즉시 독립 커밋 기록(REQUIRES_NEW). recordAfterCommit/recordAfterRollback 의 콜백 본체. */
    public void record(UUID credentialId, UUID tenantId, String result,
                       String failureReason, long signCount) {
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(result, "result");
        try {
            txTemplate.executeWithoutResult(status ->
                    repo.save(new CredentialAuthEvent(credentialId, tenantId, result, failureReason, signCount)));
        } catch (Exception e) {
            log.warn("credential_auth_event 기록 실패 (무시): cred={} result={}", credentialId, result, e);
        }
    }

    /** outer 트랜잭션 커밋 확정 후 기록(성공 경로용). 활성 동기화 없으면 즉시 폴백. */
    public void recordAfterCommit(UUID credentialId, UUID tenantId, String result,
                                  String failureReason, long signCount) {
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(result, "result");
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    record(credentialId, tenantId, result, failureReason, signCount);
                }
            });
        } else {
            record(credentialId, tenantId, result, failureReason, signCount);
        }
    }

    /**
     * outer 트랜잭션이 <b>롤백 완료된 후</b> 기록한다(실패 경로용). 인증 실패는 예외로
     * outer @Transactional 이 롤백되므로 afterCommit 은 안 불리지만 afterCompletion 은
     * 불린다. 롤백 완료 시점엔 credential 행의 PESSIMISTIC_WRITE 락이 이미 해제돼 있어
     * REQUIRES_NEW 자식 INSERT 가 부모 행과 FK 락 경합을 일으키지 않는다(즉시 record 가
     * 락 보유 중에 별 connection 으로 FK INSERT 를 시도해 enqueue 대기/교착이 나던 문제를
     * 회피). 활성 동기화가 없으면(테스트/트랜잭션 밖 호출) 즉시 record 로 폴백한다.
     */
    public void recordAfterRollback(UUID credentialId, UUID tenantId, String result,
                                    String failureReason, long signCount) {
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(result, "result");
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        record(credentialId, tenantId, result, failureReason, signCount);
                    }
                }
            });
        } else {
            record(credentialId, tenantId, result, failureReason, signCount);
        }
    }
}
