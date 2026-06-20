package com.crosscert.passkey.admin.policy;

import com.crosscert.passkey.admin.auth.TenantBoundary;
import com.crosscert.passkey.core.entity.TenantAaguidPolicy;
import com.crosscert.passkey.core.mds.MdsAaguidCache;
import com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crosscert.passkey.core.config.KstTime;

import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class AaguidPolicyService {

    private final TenantAaguidPolicyRepository repo;
    private final MdsAaguidCache mdsCache;
    private final TenantBoundary tenantBoundary;

    public AaguidPolicyService(TenantAaguidPolicyRepository repo,
                               MdsAaguidCache mdsCache,
                               TenantBoundary tenantBoundary) {
        this.repo = repo;
        this.mdsCache = mdsCache;
        this.tenantBoundary = tenantBoundary;
    }

    private static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }

    @Transactional(readOnly = true)
    public AaguidPolicyDto.View get(UUID tenantId) {
        // 테넌트 경계 검사를 조회보다 먼저 — RP_ADMIN 이 다른 테넌트 정책 존재 여부를
        // 엿보지 못하도록(정보 노출 방지). PLATFORM_OPERATOR 는 무조건 통과.
        tenantBoundary.assertCanAccessTenant(tenantId);
        TenantAaguidPolicy p = repo.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("aaguid policy not found for tenant " + tenantId));
        List<AaguidPolicyDto.Entry> entries = p.getEntries().stream()
                .map(e -> {
                    // MdsAaguidCache.Entry only stores statuses — no name field available
                    // mdsName is reserved for future expansion when full MetadataStatement is cached
                    String mdsName = null;
                    return new AaguidPolicyDto.Entry(e.getAaguid(), e.getNote(), mdsName);
                })
                .toList();
        return new AaguidPolicyDto.View(
                p.getTenantId(), p.getMode(), p.isMdsStrict(),
                entries, p.getUpdatedAt(), p.getUpdatedBy());
    }

    @Transactional
    public AaguidPolicyDto.View update(UUID tenantId, AaguidPolicyDto.UpdateRequest req, String updatedBy) {
        // 경계 검사를 변경보다 먼저 — RP_ADMIN 은 자기 테넌트만 수정 가능.
        tenantBoundary.assertCanAccessTenant(tenantId);
        TenantAaguidPolicy p = repo.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("aaguid policy not found for tenant " + tenantId));
        p.setMode(req.mode());
        p.setMdsStrict(req.mdsStrict());
        p.clearEntries();
        if (req.entries() != null) {
            for (var e : req.entries()) {
                p.addEntry(e.aaguid(), e.note());
            }
        }
        p.setUpdatedAt(OffsetDateTime.now(KstTime.ZONE));
        p.setUpdatedBy(updatedBy);
        repo.save(p);

        int entryCount = req.entries() == null ? 0 : req.entries().size();
        log.info("aaguid policy updated: tenantId={} mode={} mdsStrict={} entries={}",
                tenantId, req.mode(), req.mdsStrict(), entryCount);

        return get(tenantId);
    }
}
