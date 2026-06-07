package com.crosscert.passkey.core.ceremony;

import com.crosscert.passkey.core.entity.CeremonyEvent;
import com.crosscert.passkey.core.repository.CeremonyEventRepository;
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
 * ceremony 집계 이벤트를 best-effort 로 기록한다. 기록 실패가 등록/인증 ceremony
 * 자체를 깨면 안 된다.
 *
 * <p>핵심: 트랜잭션 <b>경계 전체</b>를 try/catch 로 감싼다. {@code @Transactional}
 * 메서드 안의 try/catch 는 커밋 시점 예외(JPA flush 지연으로 INSERT 가 커밋 때 실행되거나,
 * tenant_id NOT NULL 등 제약 위반)를 잡지 못하고 호출자로 전파시킨다. 그래서
 * TransactionTemplate(REQUIRES_NEW)을 직접 호출하고 그 호출을 try/catch 로 감싼다 —
 * executeWithoutResult 가 커밋을 동기적으로 수행하므로 커밋 실패까지 여기서 삼켜진다.
 * REQUIRES_NEW: 호출 측이 readOnly 이거나 롤백돼도 집계 기록이 독립 커밋된다.
 */
@Component
public class CeremonyEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(CeremonyEventRecorder.class);

    private final CeremonyEventRepository repo;
    private final TransactionTemplate txTemplate;

    public CeremonyEventRecorder(CeremonyEventRepository repo, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.txTemplate = new TransactionTemplate(txManager);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(UUID tenantId, String action) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(action, "action");
        try {
            txTemplate.executeWithoutResult(status -> repo.save(new CeremonyEvent(tenantId, action)));
        } catch (Exception e) {
            log.warn("ceremony_event 기록 실패 (무시): tenant={} action={}", tenantId, action, e);
        }
    }

    /**
     * 현재 트랜잭션이 성공적으로 커밋된 후에 이벤트를 기록한다. finish ceremony 처럼
     * outer 쓰기 트랜잭션의 커밋이 확정돼야 "성공"으로 집계해야 하는 경우에 쓴다.
     * 활성 트랜잭션 동기화가 없으면(테스트/트랜잭션 밖 호출) 즉시 기록으로 폴백한다.
     *
     * <p>설계 트레이드오프(의도적): afterCommit 콜백은 outer 트랜잭션 connection 이
     * 완전히 반납되기 직전에 실행되므로, 여기서 여는 REQUIRES_NEW 기록이 짧은 순간
     * 두 번째 connection 을 요청한다. best-effort·저빈도 집계 지표라 이 잔여
     * 오버랩은 수용한다(실패해도 ceremony 는 안 깨짐). 고동시성이 현실화되면
     * 전용 @Async executor 로 dispatch 하도록 격상한다.
     */
    public void recordAfterCommit(UUID tenantId, String action) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(action, "action");
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    record(tenantId, action);
                }
            });
        } else {
            record(tenantId, action);
        }
    }
}
