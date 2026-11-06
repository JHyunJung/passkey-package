package com.crosscert.passkey.app.fido2.registration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartResponse;
import com.crosscert.passkey.app.fido2.CeremonyMetrics;
import com.crosscert.passkey.app.fido2.challenge.ChallengeIssuer;
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore;
import com.crosscert.passkey.app.fido2.challenge.RegistrationChallenge;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.ceremony.CeremonyAction;
import com.crosscert.passkey.core.ceremony.CeremonyEventRecorder;
import com.crosscert.passkey.core.config.KstTime;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.crosscert.passkey.core.tenant.TenantContextHolder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Kotlin 전환 전 RegistrationStartService 의 외부 관찰 가능 동작(실패별 예외 타입·메시지,
 * 옵션 JSON 바이트, 저장 challenge 필드, 로그 문구, 메트릭, ceremony 이벤트, null displayName/username
 * 수용)을 현재 Java 구현 기준으로 고정하는 특성 테스트. Exclude/UserHandleValidation 테스트가 덮는 분기는 제외.
 */
class RegistrationStartServiceCharacterizationTest {

    private static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");
    private static final UUID TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final CredentialRepository credentials = mock(CredentialRepository.class);
    private final ChallengeIssuer challenges = mock(ChallengeIssuer.class);
    private final ChallengeStore store = mock(ChallengeStore.class);
    private final CeremonyEventRecorder ceremonyEvents = mock(CeremonyEventRecorder.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Tenant tenant = mock(Tenant.class);

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(RegistrationStartService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);

        when(tenant.getRpId()).thenReturn("example.com");
        when(tenant.getRpName()).thenReturn("Example");
        when(tenant.getWebauthnTimeoutMs()).thenReturn(60000);
        when(tenant.getAttestationConveyanceLowercase()).thenReturn("direct");
        when(tenant.isRequireUserVerification()).thenReturn(true);
        when(tenants.findById(TENANT)).thenReturn(Optional.of(tenant));
        when(challenges.newChallengeBytes()).thenReturn(new byte[]{1, 2, 3});
        when(challenges.newToken()).thenReturn("tok_abcdefghijkl");
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        logger.detachAppender(appender);
        logger.setLevel(null);
    }

    private RegistrationStartService svc() {
        return new RegistrationStartService(tenants, credentials, challenges, store, new ObjectMapper(),
                Clock.fixed(NOW, KstTime.ZONE), new CeremonyMetrics(meters), ceremonyEvents);
    }

    private double count(String result) {
        Counter c = meters.find("passkey_ceremony_total")
                .tags("type", "registration", "phase", "start", "result", result).counter();
        return c == null ? 0.0 : c.count();
    }

    private List<String> infos() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void noTenantContext_illegalState() {
        assertThatThrownBy(() -> svc().start(new RegistrationStartRequest("CQk", "Disp", "alice")))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("registration/start invoked without tenant context — ApiKeyAuthFilter must have set it");
        assertThat(count("failure")).isEqualTo(1.0);
        assertThat(infos()).containsExactly("registration/start entry: usernamePresent=true displayNameLen=4");
    }

    @Test
    void tenantNotFound_illegalState() {
        TenantContextHolder.set(TENANT);
        when(tenants.findById(TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc().start(new RegistrationStartRequest("CQk", "Disp", "alice")))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("tenant " + TENANT + " not found");
    }

    @Test
    void suspended_message_beforeUserHandleValidation() {
        TenantContextHolder.set(TENANT);
        when(tenant.isSuspended()).thenReturn(true);

        assertThatThrownBy(() -> svc().start(new RegistrationStartRequest(null, null, null)))
                .isExactlyInstanceOf(BusinessException.class)
                .hasMessage("tenant suspended: " + TENANT);
    }

    @Test
    void nullUserHandle_invalidInput_failureMetric() {
        TenantContextHolder.set(TENANT);

        assertThatThrownBy(() -> svc().start(new RegistrationStartRequest(null, "Disp", "alice")))
                .isExactlyInstanceOf(BusinessException.class)
                .hasMessage("userHandle must be valid base64url")
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(count("failure")).isEqualTo(1.0);
        verifyNoInteractions(challenges, store);
    }

    @Test
    void invalidBase64_messageAndNoCause() {
        TenantContextHolder.set(TENANT);

        assertThatThrownBy(() -> svc().start(new RegistrationStartRequest("***", "Disp", "alice")))
                .isExactlyInstanceOf(BusinessException.class)
                .hasMessage("userHandle must be valid base64url")
                .hasNoCause();
    }

    @Test
    void tooLong_messageCarriesLength() {
        TenantContextHolder.set(TENANT);
        String b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[65]);

        assertThatThrownBy(() -> svc().start(new RegistrationStartRequest(b64, "Disp", "alice")))
                .isExactlyInstanceOf(BusinessException.class)
                .hasMessage("userHandle must decode to 1..64 bytes (WebAuthn user.id cap), was 65");
    }

    @Test
    void success_optionsBytes_storedChallenge_logs_event() {
        TenantContextHolder.set(TENANT);
        when(credentials.findCredentialIdsByUserHandle(any())).thenReturn(List.of(new byte[]{4}));

        RegistrationStartResponse resp = svc().start(new RegistrationStartRequest("CQk", "Disp", "alice"));

        assertThat(resp.getRegistrationToken()).isEqualTo("tok_abcdefghijkl");
        assertThat(resp.getPublicKeyCredentialCreationOptions().toString()).isEqualTo(
                "{\"challenge\":\"AQID\",\"rp\":{\"id\":\"example.com\",\"name\":\"Example\"},"
                        + "\"user\":{\"id\":\"CQk\",\"displayName\":\"Disp\",\"name\":\"alice\"},"
                        + "\"pubKeyCredParams\":[{\"type\":\"public-key\",\"alg\":-7},{\"type\":\"public-key\",\"alg\":-257}],"
                        + "\"timeout\":60000,\"attestation\":\"direct\","
                        + "\"excludeCredentials\":[{\"type\":\"public-key\",\"id\":\"BA\"}],"
                        + "\"authenticatorSelection\":{\"userVerification\":\"required\",\"residentKey\":\"preferred\"}}");

        ArgumentCaptor<RegistrationChallenge> cap = ArgumentCaptor.forClass(RegistrationChallenge.class);
        verify(store).putRegistration(eq("tok_abcdefghijkl"), cap.capture());
        RegistrationChallenge ch = cap.getValue();
        assertThat(ch.getTenantId()).isEqualTo(TENANT.toString());
        assertThat(ch.getChallenge()).containsExactly(1, 2, 3);
        assertThat(ch.getUserHandle()).containsExactly(9, 9);
        assertThat(ch.getDisplayName()).isEqualTo("Disp");
        assertThat(ch.getUsername()).isEqualTo("alice");
        assertThat(ch.getIssuedAt()).isEqualTo(NOW);

        assertThat(infos()).containsExactly(
                "registration/start entry: usernamePresent=true displayNameLen=4",
                "registration/start issued: tokenTail=efghijkl timeoutMs=60000");
        verify(ceremonyEvents).recordAfterCommit(TENANT, CeremonyAction.REGISTRATION_BEGIN);
        assertThat(count("success")).isEqualTo(1.0);
        assertThat(count("failure")).isEqualTo(0.0);
    }

    @Test
    void nullDisplayNameAndUsername_acceptedByService_emittedAsJsonNull() {
        // 서비스 직접 호출(컨트롤러 @Valid 우회) 시 null displayName/username 을 그대로 수용한다.
        TenantContextHolder.set(TENANT);
        when(tenant.getRpName()).thenReturn(null);
        when(tenant.isRequireUserVerification()).thenReturn(false);
        when(credentials.findCredentialIdsByUserHandle(any())).thenReturn(List.of());
        when(challenges.newToken()).thenReturn("tok8char");

        RegistrationStartResponse resp = svc().start(new RegistrationStartRequest("CQk", null, null));

        var options = resp.getPublicKeyCredentialCreationOptions();
        assertThat(options.get("rp").toString()).isEqualTo("{\"id\":\"example.com\",\"name\":null}");
        assertThat(options.get("user").toString()).isEqualTo("{\"id\":\"CQk\",\"displayName\":null,\"name\":null}");
        assertThat(options.get("authenticatorSelection").get("userVerification").asString()).isEqualTo("preferred");
        ArgumentCaptor<RegistrationChallenge> cap = ArgumentCaptor.forClass(RegistrationChallenge.class);
        verify(store).putRegistration(eq("tok8char"), cap.capture());
        assertThat(cap.getValue().getDisplayName()).isNull();
        assertThat(cap.getValue().getUsername()).isNull();
        assertThat(infos()).containsExactly(
                "registration/start entry: usernamePresent=false displayNameLen=0",
                "registration/start issued: tokenTail=*** timeoutMs=60000");
    }
}
