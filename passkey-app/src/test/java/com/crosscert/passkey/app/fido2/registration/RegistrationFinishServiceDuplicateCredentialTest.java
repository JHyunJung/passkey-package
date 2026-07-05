package com.crosscert.passkey.app.fido2.registration;

import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationFinishRequest;
import com.crosscert.passkey.app.fido2.CeremonyMetrics;
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore;
import com.crosscert.passkey.app.fido2.challenge.RegistrationChallenge;
import com.crosscert.passkey.app.fido2.mds.MdsVerifier;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.ceremony.CeremonyEventRecorder;
import com.crosscert.passkey.core.config.KstTime;
import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.policy.AaguidPolicyChecker;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.crosscert.passkey.core.tenant.TenantContextHolder;
import com.crosscert.passkey.webauthn.verifier.RegistrationResult;
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * G01 — 중복 credentialId 등록이 unique 제약 위반(DataIntegrityViolationException)으로
 * 500 노출되던 것을, saveAndFlush 를 감싸 409(CREDENTIAL_DUPLICATE)로 매핑하는지 검증한다.
 *
 * <p>tenant/challenge freshness/tenant-match/attestation/format/MDS/AAGUID 검사는
 * 모두 통과시킨 뒤 credentials.saveAndFlush 에서만 실패하도록 모킹해, 실제 저장
 * 단계의 unique violation 을 재현한다.
 */
class RegistrationFinishServiceDuplicateCredentialTest {

    private static final Instant NOW = Instant.parse("2026-05-29T00:00:00Z");

    private final ChallengeStore store = mock(ChallengeStore.class);
    private final WebAuthnVerifier verifier = mock(WebAuthnVerifier.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final CredentialRepository credentials = mock(CredentialRepository.class);
    private final MdsVerifier mds = mock(MdsVerifier.class);
    private final AaguidPolicyChecker aaguidPolicyChecker = mock(AaguidPolicyChecker.class);
    private final Clock clock = Clock.fixed(NOW, KstTime.ZONE);

    @AfterEach
    void clear() { TenantContextHolder.clear(); }

    private RegistrationFinishService newService() {
        return new RegistrationFinishService(
                store, verifier, tenants, credentials, mds, aaguidPolicyChecker,
                new ObjectMapper(), clock,
                new CeremonyMetrics(new SimpleMeterRegistry()), mock(CeremonyEventRecorder.class));
    }

    @Test
    void finish_duplicateCredentialId_mappedToConflict() throws Exception {
        UUID tenantId = UUID.randomUUID();
        TenantContextHolder.set(tenantId);

        RegistrationChallenge ch = new RegistrationChallenge(
                tenantId.toString(), new byte[32], new byte[]{9, 9},
                "Alice", "alice", NOW);
        when(store.takeRegistration("tok")).thenReturn(Optional.of(ch));

        Tenant tenant = mock(Tenant.class);
        when(tenant.getId()).thenReturn(tenantId);
        when(tenant.getAllowedOriginValues()).thenReturn(List.of("https://example.com"));
        when(tenant.getRpId()).thenReturn("example.com");
        when(tenant.isRequireUserVerification()).thenReturn(false);
        when(tenant.getAcceptedFormatValues()).thenReturn(Set.of("packed", "none"));
        when(tenant.isMdsRequired()).thenReturn(false);
        when(tenants.findById(tenantId)).thenReturn(Optional.of(tenant));

        byte[] credentialId = new byte[]{1, 2, 3, 4};
        RegistrationResult result = new RegistrationResult(
                credentialId, new byte[]{5, 6}, 0L, null, "none",
                Set.of(), false, true, false, false);
        when(verifier.verifyRegistration(any())).thenReturn(result);
        when(mds.verify(false, null)).thenReturn(true);

        when(credentials.saveAndFlush(any(Credential.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint (UX_CREDENTIAL_ID) violated"));

        RegistrationFinishService svc = newService();
        RegistrationFinishRequest req = new RegistrationFinishRequest("tok", null);

        assertThatThrownBy(() -> svc.finish(req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CREDENTIAL_DUPLICATE);
    }
}
