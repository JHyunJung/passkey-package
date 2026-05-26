package com.crosscert.passkey.app.fido2.mds;

import com.crosscert.passkey.app.fido2.policy.AttestationPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MdsVerifierTest {

    private MdsAaguidCache cache;
    private MdsVerifier verifier;
    private final byte[] AAGUID = new byte[]{1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16};

    @BeforeEach
    void setUp() {
        cache = mock(MdsAaguidCache.class);
        verifier = new MdsVerifier(cache);
    }

    @Test
    void mdsNotRequiredAlwaysPasses() {
        AttestationPolicy policy = new AttestationPolicy(
                Set.of("none"), true, false);
        assertThat(verifier.verify(policy, AAGUID)).isTrue();
    }

    @Test
    void mdsRequiredAndEntryAcceptableReturnsTrue() {
        AttestationPolicy policy = new AttestationPolicy(
                Set.of("packed"), true, true);
        when(cache.lookup(any())).thenReturn(Optional.of(
                new MdsAaguidCache.Entry(List.of("FIDO_CERTIFIED_L1"))));
        assertThat(verifier.verify(policy, AAGUID)).isTrue();
    }

    @Test
    void mdsRequiredAndEntryBlockedReturnsFalse() {
        AttestationPolicy policy = new AttestationPolicy(
                Set.of("packed"), true, true);
        when(cache.lookup(any())).thenReturn(Optional.of(
                new MdsAaguidCache.Entry(List.of("FIDO_CERTIFIED_L1", "REVOKED"))));
        assertThat(verifier.verify(policy, AAGUID)).isFalse();
    }

    @Test
    void mdsRequiredAndEntryAbsentReturnsFalse() {
        AttestationPolicy policy = new AttestationPolicy(
                Set.of("packed"), true, true);
        when(cache.lookup(any())).thenReturn(Optional.empty());
        assertThat(verifier.verify(policy, AAGUID)).isFalse();
    }

    @Test
    void compromiseStatusesAreBlocked() {
        AttestationPolicy policy = new AttestationPolicy(
                Set.of("packed"), true, true);
        for (String blocked : List.of(
                "REVOKED", "USER_VERIFICATION_BYPASS",
                "ATTESTATION_KEY_COMPROMISE",
                "USER_KEY_REMOTE_COMPROMISE",
                "USER_KEY_PHYSICAL_COMPROMISE")) {
            when(cache.lookup(any())).thenReturn(Optional.of(
                    new MdsAaguidCache.Entry(List.of("FIDO_CERTIFIED_L1", blocked))));
            assertThat(verifier.verify(policy, AAGUID))
                    .as("status %s must be blocked", blocked)
                    .isFalse();
        }
    }
}
