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
import com.crosscert.passkey.core.policy.AaguidPolicyChecker;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.crosscert.passkey.core.tenant.TenantContextHolder;
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F19 방어심화: /registration/finish 가 challenge.issuedAt 을 앱-레벨에서 검증해
 * Redis TTL 만료를 이중으로 막는지 단위 검증.
 *
 * <p>가드는 store.takeRegistration() 직후 실행되므로 ChallengeStore 와
 * CeremonyMetrics 만 모킹하면 된다. Clock 을 고정해 issuedAt 의 신선/만료를
 * 결정적으로 통제한다.
 */
class RegistrationFinishServiceFreshnessTest {

    private static final Instant NOW = Instant.parse("2026-05-29T00:00:00Z");

    private final ChallengeStore store = mock(ChallengeStore.class);
    private final Clock clock = Clock.fixed(NOW, KstTime.ZONE);

    @AfterEach
    void clear() { TenantContextHolder.clear(); }

    private RegistrationFinishService newService() {
        return new RegistrationFinishService(
                store,
                mock(WebAuthnVerifier.class),
                mock(TenantRepository.class),
                mock(CredentialRepository.class),
                mock(MdsVerifier.class),
                mock(AaguidPolicyChecker.class),
                new ObjectMapper(),
                clock,
                new CeremonyMetrics(new SimpleMeterRegistry()),
                mock(CeremonyEventRecorder.class));
    }

    private RegistrationChallenge challengeIssuedAt(Instant issuedAt) {
        return new RegistrationChallenge(
                UUID.randomUUID().toString(), new byte[32], new byte[]{9, 9},
                "Alice", "alice@example", issuedAt);
    }

    @Test
    void finish_staleChallenge_rejectedAsChallengeInvalid() {
        // issuedAt 이 TTL(5분)보다 1분 더 과거 → 앱-레벨 freshness 위반
        Instant stale = NOW.minus(ChallengeStore.TTL).minusSeconds(60);
        when(store.takeRegistration("tok"))
                .thenReturn(Optional.of(challengeIssuedAt(stale)));

        RegistrationFinishService svc = newService();
        RegistrationFinishRequest req = new RegistrationFinishRequest("tok", null);

        assertThatThrownBy(() -> svc.finish(req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHALLENGE_INVALID);
    }

    @Test
    void finish_freshChallenge_passesFreshnessGuard() {
        // issuedAt = now → freshness 가드 통과. 가드 이후의 tenant-mismatch
        // (컨텍스트 미설정)에서 IllegalArgumentException 으로 멈추는 것이
        // 가드를 통과했다는 증거. CHALLENGE_INVALID 가 아니어야 한다.
        when(store.takeRegistration("tok"))
                .thenReturn(Optional.of(challengeIssuedAt(NOW)));

        RegistrationFinishService svc = newService();
        RegistrationFinishRequest req = new RegistrationFinishRequest("tok", null);

        assertThatThrownBy(() -> svc.finish(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant mismatch");
    }
}
