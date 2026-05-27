package com.crosscert.passkey.app.fido2.mds;

import com.crosscert.passkey.core.mds.MdsAaguidCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

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
        assertThat(verifier.verify(false, AAGUID)).isTrue();
    }

    @Test
    void mdsRequiredAndEntryAcceptableReturnsTrue() {
        when(cache.lookup(any())).thenReturn(Optional.of(
                new MdsAaguidCache.Entry(List.of("FIDO_CERTIFIED_L1"))));
        assertThat(verifier.verify(true, AAGUID)).isTrue();
    }

    @Test
    void mdsRequiredAndEntryBlockedReturnsFalse() {
        when(cache.lookup(any())).thenReturn(Optional.of(
                new MdsAaguidCache.Entry(List.of("FIDO_CERTIFIED_L1", "REVOKED"))));
        assertThat(verifier.verify(true, AAGUID)).isFalse();
    }

    @Test
    void mdsRequiredAndEntryAbsentReturnsFalse() {
        when(cache.lookup(any())).thenReturn(Optional.empty());
        assertThat(verifier.verify(true, AAGUID)).isFalse();
    }

    @Test
    void compromiseStatusesAreBlocked() {
        for (String blocked : List.of(
                "REVOKED", "USER_VERIFICATION_BYPASS",
                "ATTESTATION_KEY_COMPROMISE",
                "USER_KEY_REMOTE_COMPROMISE",
                "USER_KEY_PHYSICAL_COMPROMISE")) {
            when(cache.lookup(any())).thenReturn(Optional.of(
                    new MdsAaguidCache.Entry(List.of("FIDO_CERTIFIED_L1", blocked))));
            assertThat(verifier.verify(true, AAGUID))
                    .as("status %s must be blocked", blocked)
                    .isFalse();
        }
    }
}
