package com.crosscert.passkey.app.fido2.mds

import com.crosscert.passkey.core.mds.MdsAaguidCache
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(MdsVerifier::class.java)

private val BLOCKING_STATUSES = setOf(
    "REVOKED",
    "USER_VERIFICATION_BYPASS",
    "ATTESTATION_KEY_COMPROMISE",
    "USER_KEY_REMOTE_COMPROMISE",
    "USER_KEY_PHYSICAL_COMPROMISE",
)

/**
 * Real MDS verifier (Phase 3 T15) — replaces Phase 1's MdsStubVerifier.
 *
 * When the tenant policy has `mdsRequired=false`, always
 * returns true (same as Phase 1). When `mdsRequired=true`, the
 * AAGUID's *last* (most recent) status report must NOT be in the
 * blocking set, and the AAGUID must be present in the cached MDS BLOB
 * (fail-closed on absence).
 *
 * Per FIDO MDS spec §5.4, only the most recent status report determines
 * the device posture; earlier reports represent historical state and must
 * not re-block a device that has since been re-certified.
 */
@Component
class MdsVerifier(private val cache: MdsAaguidCache) {

    /**
     * [aaguid] 는 nullable — 인증기가 AAGUID 를 주지 않으면 null 이 온다. `mdsRequired=false` 면
     * 그대로 통과, `true` 면 캐시 조회/정규화([MdsAaguidCache.canonicalAaguid])가 원 동작대로 처리한다.
     */
    fun verify(mdsRequired: Boolean, aaguid: ByteArray?): Boolean {
        if (!mdsRequired) return true

        val entry = cache.lookup(aaguid).orElse(null)
        if (entry == null) {
            log.warn("MDS entry absent for aaguid {} — fail-closed", MdsAaguidCache.canonicalAaguid(aaguid))
            return false
        }
        val statuses = entry.statuses()
        if (statuses.isEmpty()) {
            log.warn(
                "MDS entry has empty status list for aaguid {} — fail-closed",
                MdsAaguidCache.canonicalAaguid(aaguid),
            )
            return false
        }
        // Inspect only the last (most-recent) status report per FIDO MDS spec §5.4.
        val lastStatus = statuses.last()
        if (lastStatus in BLOCKING_STATUSES) {
            log.warn("MDS blocking status {} for aaguid {}", lastStatus, MdsAaguidCache.canonicalAaguid(aaguid))
            return false
        }
        return true
    }
}
