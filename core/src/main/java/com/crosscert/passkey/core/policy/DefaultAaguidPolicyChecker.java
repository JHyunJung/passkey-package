package com.crosscert.passkey.core.policy;

import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.entity.TenantAaguidPolicy;
import com.crosscert.passkey.core.mds.MdsAaguidCache;
import com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * AAGUID 정책 기본 구현체.
 *
 * <ul>
 *   <li>mode=ANY   → 모든 AAGUID 허용 (mds_strict 만 검사)
 *   <li>mode=ALLOWLIST → entries 에 포함된 AAGUID 만 허용
 *   <li>mode=DENYLIST  → entries 에 포함된 AAGUID 차단
 * </ul>
 *
 * <p>정책 레코드가 DB 에 없으면 ANY(pass-through) 로 동작한다.
 * 이 경우 mds_strict 도 false 로 처리되어 MDS 미등록 AAGUID 도 통과한다.
 */
@Component
public class DefaultAaguidPolicyChecker implements AaguidPolicyChecker {

    private static final Logger log = LoggerFactory.getLogger(DefaultAaguidPolicyChecker.class);

    private final TenantAaguidPolicyRepository policyRepo;
    private final MdsAaguidCache mdsCache;

    public DefaultAaguidPolicyChecker(TenantAaguidPolicyRepository policyRepo,
                                      MdsAaguidCache mdsCache) {
        this.policyRepo = policyRepo;
        this.mdsCache = mdsCache;
    }

    @Override
    public void check(UUID tenantId, UUID aaguid) {
        // aaguid null 이면 일부 attestation format 에서 aaguid 가 없는 경우 — pass-through
        if (aaguid == null) {
            log.debug("[AaguidPolicy] aaguid=null, tenant={} → pass-through", tenantId);
            return;
        }

        // 정책이 없으면 기본 ANY(pass-through)
        TenantAaguidPolicy policy = policyRepo.findById(tenantId).orElse(null);
        if (policy == null) {
            log.debug("[AaguidPolicy] no policy for tenant={} → pass-through", tenantId);
            return;
        }

        String aaguidStr = aaguid.toString();
        String tenantStr = tenantId.toString();

        // ── mds_strict 검사: MDS 에 없는 AAGUID 는 차단 ──────────────────
        if (policy.isMdsStrict()) {
            // MdsAaguidCache.lookup() 은 byte[] 를 받으므로 UUID → byte[] 변환
            byte[] aaguidBytes = uuidToBytes(aaguid);
            boolean knownByMds = mdsCache.lookup(aaguidBytes).isPresent();
            if (!knownByMds) {
                log.warn("[AaguidPolicy] mds_strict 위반: tenant={} aaguid={} (MDS 미등록)",
                        tenantStr, aaguidStr);
                throw new AaguidPolicyViolationException(tenantStr, aaguidStr,
                        ErrorCode.ATTESTATION_REJECTED);
            }
        }

        // ── mode 검사 ──────────────────────────────────────────────────────
        TenantAaguidPolicy.Mode mode = policy.getMode();
        if (mode == TenantAaguidPolicy.Mode.ANY) {
            log.debug("[AaguidPolicy] mode=ANY tenant={} aaguid={} → pass", tenantStr, aaguidStr);
            return;
        }

        Set<UUID> entrySet = policy.getEntries().stream()
                .map(TenantAaguidPolicy.Entry::getAaguid)
                .collect(Collectors.toSet());

        if (mode == TenantAaguidPolicy.Mode.ALLOWLIST) {
            if (!entrySet.contains(aaguid)) {
                log.warn("[AaguidPolicy] ALLOWLIST 차단: tenant={} aaguid={}", tenantStr, aaguidStr);
                throw new AaguidPolicyViolationException(tenantStr, aaguidStr,
                        ErrorCode.ATTESTATION_REJECTED);
            }
            log.debug("[AaguidPolicy] ALLOWLIST 허용: tenant={} aaguid={}", tenantStr, aaguidStr);
        } else if (mode == TenantAaguidPolicy.Mode.DENYLIST) {
            if (entrySet.contains(aaguid)) {
                log.warn("[AaguidPolicy] DENYLIST 차단: tenant={} aaguid={}", tenantStr, aaguidStr);
                throw new AaguidPolicyViolationException(tenantStr, aaguidStr,
                        ErrorCode.AAGUID_REVOKED);
            }
            log.debug("[AaguidPolicy] DENYLIST 통과: tenant={} aaguid={}", tenantStr, aaguidStr);
        }
    }

    /** UUID → 16-byte big-endian array (MdsAaguidCache 와 동일한 형식). */
    private static byte[] uuidToBytes(UUID uuid) {
        byte[] bytes = new byte[16];
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (msb & 0xff);
            msb >>= 8;
        }
        for (int i = 15; i >= 8; i--) {
            bytes[i] = (byte) (lsb & 0xff);
            lsb >>= 8;
        }
        return bytes;
    }
}
