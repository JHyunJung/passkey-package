package com.crosscert.passkey.admin.policy;

import com.crosscert.passkey.core.entity.TenantAaguidPolicy;
import com.crosscert.passkey.core.mds.MdsAaguidCache;
import com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AaguidPolicyService {

    private static final Logger log = LoggerFactory.getLogger(AaguidPolicyService.class);

    private final TenantAaguidPolicyRepository repo;
    private final MdsAaguidCache mdsCache;

    public AaguidPolicyService(TenantAaguidPolicyRepository repo, MdsAaguidCache mdsCache) {
        this.repo = repo;
        this.mdsCache = mdsCache;
    }

    private static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }

    @Transactional(readOnly = true)
    public AaguidPolicyDto.View get(UUID tenantId) {
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
        p.setUpdatedAt(Instant.now());
        p.setUpdatedBy(updatedBy);
        repo.save(p);

        int entryCount = req.entries() == null ? 0 : req.entries().size();
        log.info("aaguid policy updated: tenantId={} mode={} mdsStrict={} entries={}",
                tenantId, req.mode(), req.mdsStrict(), entryCount);

        return get(tenantId);
    }
}
