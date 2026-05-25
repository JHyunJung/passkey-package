package com.crosscert.passkey.app.fido2.mds;

import com.crosscert.passkey.app.fido2.policy.AttestationPolicy;
import org.springframework.stereotype.Component;

/**
 * Phase 1 stub for MDS-backed authenticator metadata verification.
 *
 * <p>Behavior:
 * <ul>
 *   <li>Tenant has {@code mdsRequired=false} (Phase 1 default): always
 *       returns {@code true} — caller proceeds with registration.</li>
 *   <li>Tenant has {@code mdsRequired=true}: returns {@code false}.
 *       The registration ceremony then translates that to a clear
 *       4xx rejection via IllegalArgumentException — better than the
 *       previous UnsupportedOperationException which surfaced as 500.</li>
 * </ul>
 *
 * <p>Phase 3 replaces this with a real implementation reading
 * {@code mds_blob_cache} populated by the admin scheduler.
 */
@Component
public class MdsStubVerifier {

    public boolean verify(AttestationPolicy policy, byte[] aaguid) {
        if (!policy.mdsRequired()) return true;
        // Tenant has opted into MDS verification but Phase 1 has no MDS
        // data yet. Return false so the registration ceremony rejects
        // with a clear 4xx (caller raises IllegalArgumentException
        // mapped to HTTP 400 by GlobalExceptionHandler).
        return false;
    }
}
