package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.entity.ApiKey;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.ApiKeyRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** P0-2: 테넌트 status 전이 (suspend/activate) + suspend 시 활성 API 키 일괄 revoke. */
@Service
public class TenantLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(TenantLifecycleService.class);

    private final TenantRepository tenants;
    private final ApiKeyRepository apiKeys;
    private final AuditLogService audit;
    private final Clock clock;

    public TenantLifecycleService(TenantRepository tenants, ApiKeyRepository apiKeys,
                                  AuditLogService audit, Clock clock) {
        this.tenants = tenants;
        this.apiKeys = apiKeys;
        this.audit = audit;
        this.clock = clock;
    }

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @Transactional
    public void suspend(UUID tenantId, UUID actorId, String actorEmail) {
        Tenant t = tenants.findById(tenantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_FOUND));
        t.suspend();
        tenants.save(t);
        List<ApiKey> active = apiKeys.findActiveByTenantId(tenantId);
        for (ApiKey k : active) {
            k.revoke(clock.instant());
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
