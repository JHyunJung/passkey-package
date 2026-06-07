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
 * <p>성공: {@link #recordAfterCommit}(outer 커밋 확정 후). 실패: {@link #record}
 * (즉시, REQUIRES_NEW 독립 커밋) — 인증 실패는 예외→outer 롤백이라 afterCommit
 * 콜백이 안 불리므로 실패 이벤트는 즉시 커밋해야 보존된다.
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

    /** 즉시 독립 커밋 기록(실패 경로용 — outer 롤백과 무관하게 보존). */
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
}
