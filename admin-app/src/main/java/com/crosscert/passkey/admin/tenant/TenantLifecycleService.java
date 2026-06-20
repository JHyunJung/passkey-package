package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.entity.ApiKey;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.ApiKeyRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** P0-2: 테넌트 status 전이 (suspend/activate) + suspend 시 활성 API 키 일괄 revoke. */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantLifecycleService {

    private final TenantRepository tenants;
    private final ApiKeyRepository apiKeys;
    private final AuditLogService audit;
    private final Clock clock;

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @Transactional
    public void suspend(UUID tenantId, UUID actorId, String actorEmail) {
        Tenant t = tenants.findById(tenantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_FOUND));
        t.suspend();
        tenants.save(t);
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<ApiKey> active = apiKeys.findActiveByTenantId(tenantId, now);
        for (ApiKey k : active) {
            k.revoke(now);
        }
        audit.append(new AuditAppendRequest(actorId, actorEmail, "TENANT_SUSPEND",
                "TENANT", tenantId.toString(), tenantId,
                Map.of("revokedKeys", active.size())));
        log.warn("tenant suspended: tenantId={} revokedKeys={} actor={}",
                tenantId, active.size(), actorEmail);
    }

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @Transactional
    public void activate(UUID tenantId, UUID actorId, String actorEmail) {
        Tenant t = tenants.findById(tenantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_FOUND));
        t.activate();
        tenants.save(t);
        audit.append(new AuditAppendRequest(actorId, actorEmail, "TENANT_ACTIVATE",
                "TENANT", tenantId.toString(), tenantId, Map.of()));
        log.info("tenant activated: tenantId={} actor={}", tenantId, actorEmail);
    }
}
