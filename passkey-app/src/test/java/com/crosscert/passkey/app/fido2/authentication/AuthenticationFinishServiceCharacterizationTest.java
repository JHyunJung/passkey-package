package com.crosscert.passkey.app.fido2.authentication;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationFinishRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationFinishResponse;
import com.crosscert.passkey.app.fido2.CeremonyMetrics;
import com.crosscert.passkey.app.fido2.challenge.AuthenticationChallenge;
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore;
import com.crosscert.passkey.core.alert.SecurityAlertEvent;
import com.crosscert.passkey.core.ceremony.CeremonyAction;
import com.crosscert.passkey.core.ceremony.CeremonyEventRecorder;
import com.crosscert.passkey.core.ceremony.CredentialAuthEventRecorder;
import com.crosscert.passkey.core.ceremony.CredentialAuthFailureReason;
import com.crosscert.passkey.core.ceremony.CredentialAuthResult;
import com.crosscert.passkey.core.config.KstTime;
import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.jwt.IdTokenIssuer;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.crosscert.passkey.core.tenant.TenantContextHolder;
import com.crosscert.passkey.webauthn.verifier.AuthenticationInput;
import com.crosscert.passkey.webauthn.verifier.AuthenticationResult;
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerificationException;
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerifier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Kotlin 전환 전 AuthenticationFinishService 의 외부 관찰 가능 동작(실패별 예외 타입·메시지,
 * 비관적 락 획득 위치/순서, signCount 규칙, 보안 알림 이벤트, 인증 이벤트 기록, 메트릭, 로그 문구,
 * id-token 발급 인자, 응답)을 현재 Java 구현 기준으로 고정하는 특성 테스트.
 */
class AuthenticationFinishServiceCharacterizationTest {

    private static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");
    private static final UUID TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CRED_UUID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final byte[] CRED_ID = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20};
    private static final String CRED_ID_B64 = "AQIDBAUGBwgJCgsMDQ4PEBESExQ";
    private static final byte[] USER = {9, 9};
    private static final byte[] AAGUID = new byte[16];

    private final ChallengeStore store = mock(ChallengeStore.class);
    private final WebAuthnVerifier verifier = mock(WebAuthnVerifier.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final CredentialRepository credentials = mock(CredentialRepository.class);
    private final IdTokenIssuer idTokens = mock(IdTokenIssuer.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final CeremonyEventRecorder ceremonyEvents = mock(CeremonyEventRecorder.class);
    private final CredentialAuthEventRecorder authEvents = mock(CredentialAuthEventRecorder.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Clock clock = Clock.fixed(NOW, KstTime.ZONE);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Tenant tenant = mock(Tenant.class);
    private Credential cred;

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(AuthenticationFinishService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);

        when(tenant.getId()).thenReturn(TENANT);
        when(tenant.getAllowedOriginValues()).thenReturn(List.of("https://a.example"));
        when(tenant.getRpId()).thenReturn("example.com");
        when(tenant.isRequireUserVerification()).thenReturn(false);
        when(tenants.findById(TENANT)).thenReturn(Optional.of(tenant));

        cred = new Credential(TENANT, USER, CRED_ID, new byte[]{5, 6}, AAGUID);
        ReflectionTestUtils.setField(cred, "id", CRED_UUID);
        when(credentials.findByCredentialIdForUpdate(any())).thenReturn(Optional.of(cred));
        when(idTokens.issue(anyString(), any(), any(), any())).thenReturn("jwt.value");
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        logger.detachAppender(appender);
        logger.setLevel(null);
    }

    private AuthenticationFinishService svc() {
        return new AuthenticationFinishService(store, verifier, tenants, credentials, idTokens,
                mapper, clock, new CeremonyMetrics(meters), events, ceremonyEvents, authEvents);
    }

    private void stubChallenge(byte[] userHandle) {
        TenantContextHolder.set(TENANT);
        when(store.takeAuthentication("tok")).thenReturn(Optional.of(
                new AuthenticationChallenge(TENANT.toString(), new byte[]{7, 7}, userHandle, NOW)));
    }

    private AuthenticationFinishRequest req(String rawIdJsonValue) {
        JsonNode pkc = mapper.readTree("{\"id\":\"x\",\"rawId\":" + rawIdJsonValue + "}");
        return new AuthenticationFinishRequest("tok", pkc);
    }

    private AuthenticationFinishRequest validReq() {
        return req("\"" + CRED_ID_B64 + "\"");
    }

    private void storedCount(long n) {
        cred.recordAuthentication(n, null);
    }

    private double count(String result) {
        Counter c = meters.find("passkey_ceremony_total")
                .tags("type", "authentication", "phase", "finish", "result", result).counter();
        return c == null ? 0.0 : c.count();
    }

    private List<String> messages(Level level) {
        return appender.list.stream().filter(e -> e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void nullRequest_npeInsideMeasuredBlock_failureMetricCounted() {
        // 원 Java: try 안 첫 문장(req.getAuthenticationToken())에서 NPE → 실패 메트릭 1 → 재던짐.
        stubChallenge(USER);

        assertThatThrownBy(() -> svc().finish(null))
                .isExactlyInstanceOf(NullPointerException.class);
        assertThat(count("failure")).isEqualTo(1.0);
        assertThat(count("success")).isEqualTo(0.0);
        verifyNoInteractions(store, tenants, credentials, ceremonyEvents, authEvents);
    }

    @Test
    void nullToken_npeInsideMeasuredBlock_failureMetricCounted() {
        // 원 Java: null 토큰이 (실제) Kotlin ChallengeStore 파라미터 검사에서 NPE(try 안) → 실패 메트릭 1.
        // 목 ChallengeStore 는 Kotlin 파라미터 검사를 건너뛰므로 운영과 같은 실제 인스턴스를 쓴다.
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        AuthenticationFinishService real = new AuthenticationFinishService(
                new ChallengeStore(redis, mapper), verifier, tenants, credentials, idTokens,
                mapper, clock, new CeremonyMetrics(meters), events, ceremonyEvents, authEvents);

        assertThatThrownBy(() -> real.finish(new AuthenticationFinishRequest(null, null)))
                .isExactlyInstanceOf(NullPointerException.class);
        assertThat(count("failure")).isEqualTo(1.0);
        verifyNoInteractions(redis, tenants, credentials);
    }

    @Test
    void tokenMissing_illegalArgument() {
        when(store.takeAuthentication("tok")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc().finish(validReq()))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("authentication token missing or expired");
        assertThat(count("failure")).isEqualTo(1.0);
        verifyNoInteractions(credentials);
    }

    @Test
    void stale_logsWarn() {
        TenantContextHolder.set(TENANT);
        Instant stale = NOW.minus(ChallengeStore.TTL).minusMillis(1);
        when(store.takeAuthentication("tok")).thenReturn(Optional.of(
                new AuthenticationChallenge(TENANT.toString(), new byte[]{7}, null, stale)));

        assertThatThrownBy(() -> svc().finish(validReq()))
                .hasMessage("challenge expired");
        assertThat(messages(Level.WARN)).containsExactly(
                "stale authentication challenge rejected: issuedAt=" + stale + " ttl=PT5M");
    }

    @Test
    void tenantMismatch_logsWarn_noLock() {
        UUID other = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        TenantContextHolder.set(other);
        when(store.takeAuthentication("tok")).thenReturn(Optional.of(
                new AuthenticationChallenge(TENANT.toString(), new byte[]{7}, null, NOW)));

        assertThatThrownBy(() -> svc().finish(validReq()))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("tenant mismatch");
        assertThat(messages(Level.WARN)).containsExactly(
                "tenant mismatch on authentication/finish: ctx=" + other + " ch=" + TENANT);
        verifyNoInteractions(tenants, credentials);
    }

    @Test
    void tenantMissing_illegalState() {
        stubChallenge(USER);
        when(tenants.findById(TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc().finish(validReq()))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("tenant " + TENANT + " missing");
        verifyNoInteractions(credentials);
    }

    @Test
    void nullPublicKeyCredential_rawIdInvalid() {
        stubChallenge(USER);

        assertThatThrownBy(() -> svc().finish(new AuthenticationFinishRequest("tok", null)))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("assertion rawId invalid")
                .hasNoCause();
        verifyNoInteractions(credentials);
        assertThat(count("failure")).isEqualTo(1.0);
    }

    @Test
    void rawIdAbsent_rawIdInvalid() {
        stubChallenge(USER);
        JsonNode pkc = mapper.readTree("{\"id\":\"x\"}");

        assertThatThrownBy(() -> svc().finish(new AuthenticationFinishRequest("tok", pkc)))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("assertion rawId invalid");
        verifyNoInteractions(credentials);
    }

    @Test
    void rawIdNotBase64url_rawIdInvalid() {
        stubChallenge(USER);

        assertThatThrownBy(() -> svc().finish(req("\"***\"")))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("assertion rawId invalid");
        verifyNoInteractions(credentials);
    }

    @Test
    void rawIdObject_coercedToEmpty_lookedUpAsNotRegistered() {
        stubChallenge(USER);
        when(credentials.findByCredentialIdForUpdate(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc().finish(req("{\"a\":1}")))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("credential not registered");
        ArgumentCaptor<byte[]> cap = ArgumentCaptor.forClass(byte[].class);
        verify(credentials).findByCredentialIdForUpdate(cap.capture());
        assertThat(cap.getValue()).isEmpty();
    }

    @Test
    void credentialNotFound_illegalArgument() {
        stubChallenge(USER);
        when(credentials.findByCredentialIdForUpdate(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc().finish(validReq()))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("credential not registered");
        verifyNoInteractions(verifier);
    }

    @Test
    void userHandleMismatch_sameMessageAsNotRegistered_logsWarn() {
        stubChallenge(new byte[]{1, 1});

        assertThatThrownBy(() -> svc().finish(validReq()))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("credential not registered");
        assertThat(messages(Level.WARN)).containsExactly(
                "userHandle mismatch on authentication/finish for tenant " + TENANT);
        verifyNoInteractions(verifier);
    }

    @Test
    void noAllowedOrigins_illegalState_afterLock() {
        stubChallenge(USER);
        when(tenant.getAllowedOriginValues()).thenReturn(List.of());

        assertThatThrownBy(() -> svc().finish(validReq()))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("tenant " + TENANT + " has no allowed_origins configured");
        verify(credentials).findByCredentialIdForUpdate(any());
        verifyNoInteractions(verifier);
    }

    @Test
    void verifyFails_recordsAfterRollbackWithStoredCount() throws Exception {
        stubChallenge(USER);
        storedCount(41);
        when(verifier.verifyAuthentication(any())).thenThrow(new WebAuthnVerificationException(
                WebAuthnVerificationException.Reason.BAD_SIGNATURE, "nope"));

        assertThatThrownBy(() -> svc().finish(validReq()))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("assertion verify failed")
                .hasNoCause();
        verify(authEvents).recordAfterRollback(CRED_UUID, TENANT, CredentialAuthResult.FAILED,
                CredentialAuthFailureReason.SIGNATURE_INVALID, 41L);
        assertThat(messages(Level.WARN)).containsExactly(
                "assertion verify failed for tenant " + TENANT + ": nope (BAD_SIGNATURE)");
        verify(credentials, never()).saveAndFlush(any());
        assertThat(count("failure")).isEqualTo(1.0);
    }

    @Test
    void verifierInput_carriesStoredCredentialAndPolicy() throws Exception {
        stubChallenge(null); // usernameless: userHandle 바인딩 검사 생략
        storedCount(3);
        when(verifier.verifyAuthentication(any())).thenReturn(new AuthenticationResult(CRED_ID, 4, true, true, false));

        svc().finish(validReq());

        ArgumentCaptor<AuthenticationInput> cap = ArgumentCaptor.forClass(AuthenticationInput.class);
        verify(verifier).verifyAuthentication(cap.capture());
        AuthenticationInput in = cap.getValue();
        assertThat(in.credentialJson()).isEqualTo("{\"id\":\"x\",\"rawId\":\"" + CRED_ID_B64 + "\"}");
        assertThat(in.challenge()).containsExactly(7, 7);
        assertThat(in.allowedOrigins()).containsExactly("https://a.example");
        assertThat(in.rpId()).isEqualTo("example.com");
        assertThat(in.userVerificationRequired()).isFalse();
        assertThat(in.storedCredential().credentialId()).isEqualTo(CRED_ID);
        assertThat(in.storedCredential().cosePublicKey()).containsExactly(5, 6);
        assertThat(in.storedCredential().signCount()).isEqualTo(3L);
    }

    @Test
    void counterNotAdvanced_publishesAlert_recordsReplay() throws Exception {
        stubChallenge(USER);
        storedCount(10);
        when(verifier.verifyAuthentication(any())).thenReturn(new AuthenticationResult(CRED_ID, 10, true, true, false));

        assertThatThrownBy(() -> svc().finish(validReq()))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("signCount replay detected");

        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(ev.capture());
        SecurityAlertEvent alert = (SecurityAlertEvent) ev.getValue();
        assertThat(alert.type()).isEqualTo(SecurityAlertEvent.AlertType.COUNTER_REGRESSION);
        assertThat(alert.severity()).isEqualTo(SecurityAlertEvent.Severity.HIGH);
        assertThat(alert.summary()).isEqualTo("signCount did not advance (possible cloned authenticator)");
        assertThat(alert.context()).isEqualTo(Map.of(
                "credentialId", CRED_UUID.toString(), "tenantId", TENANT.toString()));
        verify(authEvents).recordAfterRollback(CRED_UUID, TENANT, CredentialAuthResult.FAILED,
                CredentialAuthFailureReason.SIGN_COUNT_REPLAY, 10L);
        assertThat(messages(Level.WARN)).containsExactly(
                "signCount did not advance for credential " + CRED_UUID + " on tenant " + TENANT
                        + " (was 10, got 10)");
        verify(credentials, never()).saveAndFlush(any());
        assertThat(cred.getSignCount()).isEqualTo(10L);
        assertThat(count("failure")).isEqualTo(1.0);
    }

    @Test
    void counterRegressedToZero_rejected() throws Exception {
        stubChallenge(USER);
        storedCount(5);
        when(verifier.verifyAuthentication(any())).thenReturn(new AuthenticationResult(CRED_ID, 0, true, true, false));

        assertThatThrownBy(() -> svc().finish(validReq()))
                .hasMessage("signCount replay detected");
        verify(authEvents).recordAfterRollback(CRED_UUID, TENANT, CredentialAuthResult.FAILED,
                CredentialAuthFailureReason.SIGN_COUNT_REPLAY, 0L);
    }

    @Test
    void counterless_zeroZero_accepted() throws Exception {
        stubChallenge(USER);
        when(verifier.verifyAuthentication(any())).thenReturn(new AuthenticationResult(CRED_ID, 0, true, true, false));

        AuthenticationFinishResponse resp = svc().finish(validReq());

        assertThat(resp.getIdToken()).isEqualTo("jwt.value");
        verify(authEvents).recordAfterCommit(CRED_UUID, TENANT, CredentialAuthResult.SUCCESS, null, 0L);
        verifyNoInteractions(events);
    }

    @Test
    void success_locksBeforeVerify_persistsCounter_issuesToken() throws Exception {
        stubChallenge(USER);
        storedCount(1);
        when(verifier.verifyAuthentication(any())).thenReturn(new AuthenticationResult(CRED_ID, 2, true, true, false));

        AuthenticationFinishResponse resp = svc().finish(validReq());

        assertThat(resp.getIdToken()).isEqualTo("jwt.value");
        assertThat(resp.getTokenType()).isEqualTo("Bearer");
        assertThat(resp.getExpiresIn()).isEqualTo(900L);
        assertThat(cred.getSignCount()).isEqualTo(2L);
        assertThat(cred.getLastUsedAt()).isEqualTo(OffsetDateTime.now(clock));

        ArgumentCaptor<byte[]> lockArg = ArgumentCaptor.forClass(byte[].class);
        InOrder order = inOrder(store, tenants, credentials, verifier, authEvents, idTokens, ceremonyEvents);
        order.verify(store).takeAuthentication("tok");
        order.verify(tenants).findById(TENANT);
        order.verify(credentials).findByCredentialIdForUpdate(lockArg.capture());
        order.verify(verifier).verifyAuthentication(any());
        order.verify(credentials).saveAndFlush(cred);
        order.verify(authEvents).recordAfterCommit(CRED_UUID, TENANT, CredentialAuthResult.SUCCESS, null, 2L);
        order.verify(idTokens).issue(TENANT.toString(), USER, CRED_UUID, AAGUID);
        order.verify(ceremonyEvents).recordAfterCommit(TENANT, CeremonyAction.AUTHENTICATION_SUCCESS);
        assertThat(lockArg.getValue()).isEqualTo(CRED_ID);
        verify(credentials, never()).findByCredentialId(any());
        verify(authEvents, never()).recordAfterRollback(any(), any(), any(), any(), anyLong());
        verifyNoInteractions(events);

        assertThat(messages(Level.INFO)).containsExactly(
                "authentication/finish ok: credentialIdTail=...MDQ4PEBESExQ counter=2",
                "id-token issued: subTail=*** aud=" + TENANT + " ttlSec=900");
        assertThat(count("success")).isEqualTo(1.0);
        assertThat(count("failure")).isEqualTo(0.0);
    }

    @Test
    void twelveCharIds_fullyMaskedInLogs() throws Exception {
        // credentialId 9 바이트 / userHandle 9 바이트 → base64url 12자 경계: 전체 마스킹
        byte[] id9 = new byte[9];
        byte[] user9 = new byte[9];
        Credential c9 = new Credential(TENANT, user9, id9, new byte[]{5}, null);
        ReflectionTestUtils.setField(c9, "id", CRED_UUID);
        when(credentials.findByCredentialIdForUpdate(any())).thenReturn(Optional.of(c9));
        stubChallenge(user9);
        when(verifier.verifyAuthentication(any())).thenReturn(new AuthenticationResult(id9, 1, true, true, false));

        svc().finish(req("\"AAAAAAAAAAAA\""));

        verify(idTokens).issue(TENANT.toString(), user9, CRED_UUID, null);
        assertThat(messages(Level.INFO)).containsExactly(
                "authentication/finish ok: credentialIdTail=*** counter=1",
                "id-token issued: subTail=*** aud=" + TENANT + " ttlSec=900");
    }

    @Test
    void idTokenIssueFailure_failureMetric_afterPersist() throws Exception {
        stubChallenge(USER);
        when(verifier.verifyAuthentication(any())).thenReturn(new AuthenticationResult(CRED_ID, 1, true, true, false));
        RuntimeException boom = new IllegalStateException("signer down");
        when(idTokens.issue(anyString(), any(), any(), any())).thenThrow(boom);

        assertThatThrownBy(() -> svc().finish(validReq())).isSameAs(boom);
        verify(credentials).saveAndFlush(cred);
        verify(ceremonyEvents, never()).recordAfterCommit(any(), eq(CeremonyAction.AUTHENTICATION_SUCCESS));
        assertThat(count("failure")).isEqualTo(1.0);
        assertThat(count("success")).isEqualTo(0.0);
    }
}
