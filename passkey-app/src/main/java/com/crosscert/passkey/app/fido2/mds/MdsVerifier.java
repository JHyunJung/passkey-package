package com.crosscert.passkey.app.fido2.mds;

import com.crosscert.passkey.core.mds.MdsAaguidCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Real MDS verifier (Phase 3 T15) — replaces Phase 1's MdsStubVerifier.
 *
 * <p>When the tenant policy has {@code mdsRequired=false}, always
 * returns true (same as Phase 1). When {@code mdsRequired=true}, the
 * AAGUID's <em>last</em> (most recent) status report must NOT be in the
 * blocking set, and the AAGUID must be present in the cached MDS BLOB
 * (fail-closed on absence).
 *
 * <p>Per FIDO MDS spec §5.4, only the most recent status report determines
 * the device posture; earlier reports represent historical state and must
 * not re-block a device that has since been re-certified.
 */
@Slf4j
@Component
public class MdsVerifier {

    private static final Set<String> BLOCKING_STATUSES = Set.of(
            "REVOKED",
            "USER_VERIFICATION_BYPASS",
            "ATTESTATION_KEY_COMPROMISE",
            "USER_KEY_REMOTE_COMPROMISE",
            "USER_KEY_PHYSICAL_COMPROMISE");

    private final MdsAaguidCache cache;

    public MdsVerifier(MdsAaguidCache cache) {
        this.cache = cache;
    }

    public boolean verify(boolean mdsRequired, byte[] aaguid) {
        if (!mdsRequired) return true;

        Optional<MdsAaguidCache.Entry> entryOpt = cache.lookup(aaguid);
        if (entryOpt.isEmpty()) {
            log.warn("MDS entry absent for aaguid {} — fail-closed",
                    MdsAaguidCache.canonicalAaguid(aaguid));
            return false;
        }
        List<String> statuses = entryOpt.get().statuses();
        if (statuses.isEmpty()) {
            log.warn("MDS entry has empty status list for aaguid {} — fail-closed",
                    MdsAaguidCache.canonicalAaguid(aaguid));
            return false;
        }
        // Inspect only the last (most-recent) status report per FIDO MDS spec §5.4.
        String lastStatus = statuses.get(statuses.size() - 1);
        if (BLOCKING_STATUSES.contains(lastStatus)) {
            log.warn("MDS blocking status {} for aaguid {}",
                    lastStatus, MdsAaguidCache.canonicalAaguid(aaguid));
            return false;
        }
        return true;
    }
}
