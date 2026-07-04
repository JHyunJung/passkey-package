package com.crosscert.passkey.app.fido2.registration;

import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartRequest;
import com.crosscert.passkey.app.fido2.CeremonyMetrics;
import com.crosscert.passkey.app.fido2.challenge.ChallengeIssuer;
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.ceremony.CeremonyEventRecorder;
import com.crosscert.passkey.core.config.KstTime;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.crosscert.passkey.core.tenant.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * G18 — userHandle 은 WebAuthn 스펙상 user.id 상한(1..64 바이트)을 넘으면 안 된다.
 * 기존에는 이 검증이 전혀 없어 65바이트+ userHandle 도 /registration/start 를
 * 통과했고, Oracle credential.user_handle RAW(64) 컬럼에 저장 시(finish 단계)
 * ORA-12899 → DataIntegrityViolationException → catch-all 500 으로 노출됐다.
 *
 * <p>이 테스트는 start() 진입부에서 base64url 디코드 직후 길이 위반을
 * fail-fast BusinessException(400, INVALID_INPUT)으로 거부하는지 검증한다.
 */
class RegistrationStartServiceUserHandleValidationTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final CredentialRepository credentials = mock(CredentialRepository.class);
    private final ChallengeIssuer challenges = mock(ChallengeIssuer.class);
    private final ChallengeStore store = mock(ChallengeStore.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-29T00:00:00Z"), KstTime.ZONE);

    @AfterEach
    void clear() { TenantContextHolder.clear(); }

    private RegistrationStartService newService() {
        return new RegistrationStartService(
                tenants, credentials, challenges, store, mapper, clock,
                new CeremonyMetrics(new SimpleMeterRegistry()), mock(CeremonyEventRecorder.class));
    }

    private void stubTenant(UUID tenantId) {
        TenantContextHolder.set(tenantId);
        Tenant t = mock(Tenant.class);
        when(t.getRpId()).thenReturn("example.com");
        when(t.getRpName()).thenReturn("Example");
        when(tenants.findById(tenantId)).thenReturn(Optional.of(t));
        when(challenges.newChallengeBytes()).thenReturn(new byte[32]);
        when(challenges.newToken()).thenReturn("tok_test_token_value");
    }

    @Test
    void start_rejects65ByteUserHandle_withInvalidInput() {
        UUID tenantId = UUID.randomUUID();
        stubTenant(tenantId);
        byte[] oversized = new byte[65]; // WebAuthn user.id cap is 64 bytes
        String userHandleB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(oversized);
        RegistrationStartRequest req = new RegistrationStartRequest(userHandleB64, "Disp", "alice");

        assertThatThrownBy(() -> newService().start(req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void start_rejectsEmptyUserHandle_withInvalidInput() {
        UUID tenantId = UUID.randomUUID();
        stubTenant(tenantId);
        RegistrationStartRequest req = new RegistrationStartRequest("", "Disp", "alice");

        assertThatThrownBy(() -> newService().start(req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void start_rejectsNonBase64urlUserHandle_withInvalidInput() {
        UUID tenantId = UUID.randomUUID();
        stubTenant(tenantId);
        // '+' and '/' are not part of the base64url alphabet
        RegistrationStartRequest req = new RegistrationStartRequest("abc+def/", "Disp", "alice");

        assertThatThrownBy(() -> newService().start(req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void start_accepts64ByteUserHandle_atSpecMax() {
        UUID tenantId = UUID.randomUUID();
        stubTenant(tenantId);
        byte[] atMax = new byte[64];
        String userHandleB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(atMax);
        when(credentials.findCredentialIdsByUserHandle(atMax)).thenReturn(List.of());
        RegistrationStartRequest req = new RegistrationStartRequest(userHandleB64, "Disp", "alice");

        var resp = newService().start(req);

        assertThat(resp).isNotNull();
    }

    @Test
    void start_accepts1ByteUserHandle_atSpecMin() {
        UUID tenantId = UUID.randomUUID();
        stubTenant(tenantId);
        byte[] atMin = new byte[]{7};
        String userHandleB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(atMin);
        when(credentials.findCredentialIdsByUserHandle(atMin)).thenReturn(List.of());
        RegistrationStartRequest req = new RegistrationStartRequest(userHandleB64, "Disp", "alice");

        var resp = newService().start(req);

        assertThat(resp).isNotNull();
    }
}
