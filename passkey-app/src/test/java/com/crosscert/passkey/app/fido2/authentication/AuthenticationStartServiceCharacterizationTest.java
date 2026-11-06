package com.crosscert.passkey.app.fido2.authentication;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationStartRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationStartResponse;
import com.crosscert.passkey.app.fido2.CeremonyMetrics;
import com.crosscert.passkey.app.fido2.challenge.AuthenticationChallenge;
import com.crosscert.passkey.app.fido2.challenge.ChallengeIssuer;
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.ceremony.CeremonyAction;
import com.crosscert.passkey.core.ceremony.CeremonyEventRecorder;
import com.crosscert.passkey.core.config.KstTime;
import com.crosscert.passkey.core.entity.Credential;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Kotlin 전환 전 AuthenticationStartService 의 외부 관찰 가능 동작(실패별 예외 타입·메시지,
 * usernameless/typed 분기, 저장 challenge 필드, 옵션 JSON 바이트, 로그 문구, 메트릭, ceremony 이벤트)을
 * 현재 Java 구현 기준으로 고정하는 특성 테스트. Suspend 테스트가 덮는 분기는 제외.
 */
class AuthenticationStartServiceCharacterizationTest {

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
        logger = (Logger) LoggerFactory.getLogger(AuthenticationStartService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);

        when(tenant.getRpId()).thenReturn("example.com");
        when(tenant.getWebauthnTimeoutMs()).thenReturn(60000);
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

    private AuthenticationStartService svc() {
        return new AuthenticationStartService(tenants, credentials, challenges, store, new ObjectMapper(),
                Clock.fixed(NOW, KstTime.ZONE), new CeremonyMetrics(meters), ceremonyEvents);
    }

    private double count(String result) {
        Counter c = meters.find("passkey_ceremony_total")
                .tags("type", "authentication", "phase", "start", "result", result).counter();
        return c == null ? 0.0 : c.count();
    }

    private List<String> infos() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void noTenantContext_illegalState() {
        assertThatThrownBy(() -> svc().start(new AuthenticationStartRequest(null)))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("authentication/start invoked without tenant context");
        assertThat(count("failure")).isEqualTo(1.0);
        assertThat(infos()).containsExactly("authentication/start entry: userHandlePresent=false");
        verifyNoInteractions(tenants);
    }

    @Test
    void tenantNotFound_illegalState() {
        TenantContextHolder.set(TENANT);
        when(tenants.findById(TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc().start(new AuthenticationStartRequest(null)))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("tenant " + TENANT + " not found");
    }

    @Test
    void suspended_message() {
        TenantContextHolder.set(TENANT);
        when(tenant.isSuspended()).thenReturn(true);

        assertThatThrownBy(() -> svc().start(new AuthenticationStartRequest(null)))
                .isExactlyInstanceOf(BusinessException.class)
                .hasMessage("tenant suspended: " + TENANT);
        assertThat(count("failure")).isEqualTo(1.0);
        verifyNoInteractions(challenges, store);
    }

    @Test
    void invalidBase64UserHandle_rawDecoderException_failureMetric() {
        TenantContextHolder.set(TENANT);

        assertThatThrownBy(() -> svc().start(new AuthenticationStartRequest("***")))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Illegal base64 character");
        assertThat(count("failure")).isEqualTo(1.0);
        verifyNoInteractions(credentials, store);
    }

    @Test
    void usernameless_emptyAllowList_noRepoLookup_nullUserHandleStored() {
        TenantContextHolder.set(TENANT);

        AuthenticationStartResponse resp = svc().start(new AuthenticationStartRequest(null));

        verify(credentials, never()).findByUserHandle(any());
        ArgumentCaptor<AuthenticationChallenge> cap = ArgumentCaptor.forClass(AuthenticationChallenge.class);
        verify(store).putAuthentication(org.mockito.ArgumentMatchers.eq("tok_abcdefghijkl"), cap.capture());
        AuthenticationChallenge ch = cap.getValue();
        assertThat(ch.getTenantId()).isEqualTo(TENANT.toString());
        assertThat(ch.getChallenge()).containsExactly(1, 2, 3);
        assertThat(ch.getUserHandle()).isNull();
        assertThat(ch.getIssuedAt()).isEqualTo(NOW);

        assertThat(resp.getAuthenticationToken()).isEqualTo("tok_abcdefghijkl");
        assertThat(resp.getPublicKeyCredentialRequestOptions().toString()).isEqualTo(
                "{\"challenge\":\"AQID\",\"rpId\":\"example.com\",\"timeout\":60000,"
                        + "\"userVerification\":\"preferred\",\"allowCredentials\":[]}");
        assertThat(infos()).containsExactly(
                "authentication/start entry: userHandlePresent=false",
                "authentication/start issued: tokenTail=efghijkl allowCount=0 timeoutMs=60000");
        verify(ceremonyEvents).recordAfterCommit(TENANT, CeremonyAction.AUTHENTICATION_BEGIN);
        assertThat(count("success")).isEqualTo(1.0);
        assertThat(count("failure")).isEqualTo(0.0);
    }

    @Test
    void typed_allowListInRepoOrder_decodedUserHandleStored() {
        TenantContextHolder.set(TENANT);
        Credential a = mock(Credential.class);
        when(a.getCredentialId()).thenReturn(new byte[]{1});
        Credential b = mock(Credential.class);
        when(b.getCredentialId()).thenReturn(new byte[]{2});
        when(credentials.findByUserHandle(any())).thenReturn(List.of(a, b));
        when(challenges.newToken()).thenReturn("short");

        AuthenticationStartResponse resp = svc().start(new AuthenticationStartRequest("CQk"));

        ArgumentCaptor<AuthenticationChallenge> cap = ArgumentCaptor.forClass(AuthenticationChallenge.class);
        verify(store).putAuthentication(org.mockito.ArgumentMatchers.eq("short"), cap.capture());
        assertThat(cap.getValue().getUserHandle()).containsExactly(9, 9);
        assertThat(resp.getPublicKeyCredentialRequestOptions().get("allowCredentials").toString())
                .isEqualTo("[{\"type\":\"public-key\",\"id\":\"AQ\"},{\"type\":\"public-key\",\"id\":\"Ag\"}]");
        assertThat(infos()).containsExactly(
                "authentication/start entry: userHandlePresent=true",
                "authentication/start issued: tokenTail=*** allowCount=2 timeoutMs=60000");
    }
}
