package com.crosscert.passkey.core.ceremony;

import com.crosscert.passkey.core.entity.CeremonyEvent;
import com.crosscert.passkey.core.repository.CeremonyEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * ceremony 집계 이벤트를 best-effort 로 기록한다. 기록 실패가 등록/인증 ceremony
 * 자체를 깨면 안 되므로 예외를 삼킨다(로그만). REQUIRES_NEW 로 별도 트랜잭션을 써,
 * 호출 측 트랜잭션이 readOnly 이거나 롤백돼도 집계 이벤트 기록이 독립적으로 커밋된다.
 */
@Component
public class CeremonyEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(CeremonyEventRecorder.class);

    private final CeremonyEventRepository repo;

    public CeremonyEventRecorder(CeremonyEventRepository repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID tenantId, String action) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(action, "action");
        try {
            repo.save(new CeremonyEvent(tenantId, action));
        } catch (Exception e) {
            log.warn("ceremony_event 기록 실패 (무시): tenant={} action={}", tenantId, action, e);
        }
    }
}
