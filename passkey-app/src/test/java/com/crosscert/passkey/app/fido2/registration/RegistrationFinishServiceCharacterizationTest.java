package com.crosscert.passkey.app.fido2.registration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationFinishRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationFinishResponse;
import com.crosscert.passkey.app.fido2.CeremonyMetrics;
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore;
import com.crosscert.passkey.app.fido2.challenge.RegistrationChallenge;
import com.crosscert.passkey.app.fido2.mds.MdsVerifier;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.ceremony.CeremonyAction;
import com.crosscert.passkey.core.ceremony.CeremonyEventRecorder;
import com.crosscert.passkey.core.config.KstTime;
import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.policy.AaguidPolicyChecker;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.crosscert.passkey.core.tenant.TenantContextHolder;
import com.crosscert.passkey.webauthn.verifier.AttestationTrustPolicy;
import com.crosscert.passkey.webauthn.verifier.COSEAlgorithm;
import com.crosscert.passkey.webauthn.verifier.RegistrationInput;
import com.crosscert.passkey.webauthn.verifier.RegistrationResult;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Kotlin 전환 전 RegistrationFinishService 의 외부 관찰 가능 동작(실패별 예외 타입·메시지,
 * 검증 순서, 메트릭 이름/태그/횟수, 로그 문구, 저장 엔티티 필드, 응답 필드, ceremony 이벤트)을
 * 현재 Java 구현 기준으로 고정하는 특성 테스트.
 */
class RegistrationFinishServiceCharacterizationTest {

    private static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");
    private static final UUID TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final byte[] AAGUID = {
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10};
    private static final byte[] CRED_ID = new byte[20];

    private final ChallengeStore store = mock(ChallengeStore.class);
    private final WebAuthnVerifier verifier = mock(WebAuthnVerifier.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final CredentialRepository credentials = mock(CredentialRepository.class);
    private final MdsVerifier mds = mock(MdsVerifier.class);
    private final AaguidPolicyChecker policy = mock(AaguidPolicyChecker.class);
    private final CeremonyEventRecorder ceremonyEvents = mock(CeremonyEventRecorder.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Clock clock = Clock.fixed(NOW, KstTime.ZONE);
    private final Tenant tenant = mock(Tenant.class);

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        for (int i = 0; i < CRED_ID.length; i++) CRED_ID[i] = (byte) (i + 1);
        logger = (Logger) LoggerFactory.getLogger(RegistrationFinishService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);

        when(tenant.getId()).thenReturn(TENANT);
        when(tenant.getAllowedOriginValues()).thenReturn(List.of("https://b.example", "https://a.example"));
        when(tenant.getRpId()).thenReturn("example.com");
        when(tenant.isRequireUserVerification()).thenReturn(true);
        when(tenant.getAcceptedFormatValues()).thenReturn(Set.of("packed", "none"));
        when(tenant.isMdsRequired()).thenReturn(true);
        when(tenants.findById(TENANT)).thenReturn(Optional.of(tenant));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        logger.detachAppender(appender);
        logger.setLevel(null);
    }

    private RegistrationFinishService svc() {
        return new RegistrationFinishService(store, verifier, tenants, credentials, mds, policy,
                new ObjectMapper(), clock, new CeremonyMetrics(meters), ceremonyEvents);
    }

    private RegistrationChallenge challenge(String tenantId) {
        return new RegistrationChallenge(tenantId, new byte[]{7, 7, 7}, new byte[]{9, 9},
                "Alice", "alice", NOW);
    }

    private void stubChallenge() {
        TenantContextHolder.set(TENANT);
        when(store.takeRegistration("tok")).thenReturn(Optional.of(challenge(TENANT.toString())));
    }

    private RegistrationResult result(byte[] aaguid, String fmt, Set<String> transports) {
        return new RegistrationResult(CRED_ID, new byte[]{5, 6}, 0L, aaguid, fmt,
                transports, true, true, false, false);
    }

    private double count(String result) {
        Counter c = meters.find("passkey_ceremony_total")
                .tags("type", "registration", "phase", "finish", "result", result).counter();
        return c == null ? 0.0 : c.count();
    }

    private List<String> messages(Level level) {
        return appender.list.stream().filter(e -> e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void nullRequest_npeInsideMeasuredBlock_failureMetricCounted() {
        // 원 Java: try 안 첫 문장(req.getRegistrationToken())에서 NPE → 실패 메트릭 1 → 재던짐.
        stubChallenge();

        assertThatThrownBy(() -> svc().finish(null))
                .isExactlyInstanceOf(NullPointerException.class);
        assertThat(count("failure")).isEqualTo(1.0);
        assertThat(count("success")).isEqualTo(0.0);
        verifyNoInteractions(store, tenants, ceremonyEvents);
    }

    @Test
    void nullToken_npeInsideMeasuredBlock_failureMetricCounted() {
        // 원 Java: null 토큰이 (실제) Kotlin ChallengeStore 파라미터 검사에서 NPE(try 안) → 실패 메트릭 1.
        // 목 ChallengeStore 는 Kotlin 파라미터 검사를 건너뛰므로 운영과 같은 실제 인스턴스를 쓴다.
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RegistrationFinishService real = new RegistrationFinishService(
                new ChallengeStore(redis, new ObjectMapper()), verifier, tenants, credentials, mds, policy,
                new ObjectMapper(), clock, new CeremonyMetrics(meters), ceremonyEvents);

        assertThatThrownBy(() -> real.finish(new RegistrationFinishRequest(null, null)))
                .isExactlyInstanceOf(NullPointerException.class);
        assertThat(count("failure")).isEqualTo(1.0);
        verifyNoInteractions(redis, tenants);
    }

    @Test
    void tokenMissing_illegalArgument_failureMetric() {
        when(store.takeRegistration("tok")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc().finish(new RegistrationFinishRequest("tok", null)))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("registration token missing or expired");
        assertThat(count("failure")).isEqualTo(1.0);
        assertThat(count("success")).isEqualTo(0.0);
        verifyNoInteractions(tenants);
    }

    @Test
    void staleChallenge_logsWarnWithIssuedAtAndTtl() {
        TenantContextHolder.set(TENANT);
        Instant stale = NOW.minus(ChallengeStore.TTL).minusSeconds(1);
        when(store.takeRegistration("tok")).thenReturn(Optional.of(new RegistrationChallenge(
                TENANT.toString(), new byte[]{1}, new byte[]{1}, null, null, stale)));

        assertThatThrownBy(() -> svc().finish(new RegistrationFinishRequest("tok", null)))
                .isExactlyInstanceOf(BusinessException.class)
                .hasMessage("challenge expired");
        assertThat(messages(Level.WARN)).containsExactly(
                "stale registration challenge rejected: issuedAt=" + stale + " ttl=PT5M");
        assertThat(count("failure")).isEqualTo(1.0);
    }

    @Test
    void challengeExactlyAtTtl_isStillFresh() {
        // 경계: now - TTL == issuedAt → isAfter false → 통과 (tenant mismatch 에서 멈춤)
        when(store.takeRegistration("tok")).thenReturn(Optional.of(new RegistrationChallenge(
                TENANT.toString(), new byte[]{1}, new byte[]{1}, null, null, NOW.minus(ChallengeStore.TTL))));

        assertThatThrownBy(() -> svc().finish(new RegistrationFinishRequest("tok", null)))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("tenant mismatch");
    }

    @Test
    void tenantMismatch_otherTenant_logsWarn() {
        UUID other = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        TenantContextHolder.set(other);
        when(store.takeRegistration("tok")).thenReturn(Optional.of(challenge(TENANT.toString())));

        assertThatThrownBy(() -> svc().finish(new RegistrationFinishRequest("tok", null)))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("tenant mismatch");
        assertThat(messages(Level.WARN)).containsExactly(
                "tenant mismatch on registration/finish: ctx=" + other + " ch=" + TENANT);
        verifyNoInteractions(tenants);
        assertThat(count("failure")).isEqualTo(1.0);
    }

    @Test
    void tenantMismatch_noContext_logsNullCtx() {
        when(store.takeRegistration("tok")).thenReturn(Optional.of(challenge(TENANT.toString())));

        assertThatThrownBy(() -> svc().finish(new RegistrationFinishRequest("tok", null)))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("tenant mismatch");
        assertThat(messages(Level.WARN)).containsExactly(
                "tenant mismatch on registration/finish: ctx=null ch=" + TENANT);
    }

    @Test
    void tenantMissing_illegalState() {
        stubChallenge();
        when(tenants.findById(TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc().finish(new RegistrationFinishRequest("tok", null)))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("tenant " + TENANT + " missing");
        assertThat(count("failure")).isEqualTo(1.0);
    }

    @Test
    void noAllowedOrigins_illegalState() {
        stubChallenge();
        when(tenant.getAllowedOriginValues()).thenReturn(List.of());

        assertThatThrownBy(() -> svc().finish(new RegistrationFinishRequest("tok", null)))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("tenant " + TENANT + " has no allowed_origins configured");
        verifyNoInteractions(verifier);
    }

    @Test
    void verifierInput_carriesTenantPolicy() throws Exception {
        stubChallenge();
        when(verifier.verifyRegistration(any())).thenReturn(result(AAGUID, "packed", Set.of()));
        when(mds.verify(true, AAGUID)).thenReturn(true);

        svc().finish(new RegistrationFinishRequest("tok", null));

        ArgumentCaptor<RegistrationInput> cap = ArgumentCaptor.forClass(RegistrationInput.class);
        verify(verifier).verifyRegistration(cap.capture());
        RegistrationInput in = cap.getValue();
        // 명시적 JSON null 요청 → "null" 로 직렬화돼 verifier 로 넘어간다
        assertThat(in.credentialJson()).isEqualTo("null");
        assertThat(in.challenge()).containsExactly(7, 7, 7);
        assertThat(in.allowedOrigins()).isInstanceOf(LinkedHashSet.class)
                .containsExactly("https://b.example", "https://a.example");
        assertThat(in.rpId()).isEqualTo("example.com");
        assertThat(in.userVerificationRequired()).isTrue();
        assertThat(in.allowedAlgorithms()).containsExactlyInAnyOrder(COSEAlgorithm.ES256, COSEAlgorithm.RS256);
        assertThat(in.acceptedAttestationFormats()).containsExactlyInAnyOrder("packed", "none");
        assertThat(in.trustPolicy()).isEqualTo(AttestationTrustPolicy.SELF_ALLOWED);
    }

    @Test
    void verifierThrows_illegalArgument_logsReason() throws Exception {
        stubChallenge();
        when(verifier.verifyRegistration(any())).thenThrow(new WebAuthnVerificationException(
                WebAuthnVerificationException.Reason.BAD_SIGNATURE, "sig bad"));

        assertThatThrownBy(() -> svc().finish(new RegistrationFinishRequest("tok", null)))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("attestation verify failed")
                .hasNoCause();
        assertThat(messages(Level.WARN)).containsExactly(
                "attestation verify failed for tenant " + TENANT + ": sig bad (BAD_SIGNATURE)");
        assertThat(count("failure")).isEqualTo(1.0);
        verifyNoInteractions(mds);
    }

    @Test
    void formatNotAccepted_illegalArgument() throws Exception {
        stubChallenge();
        when(verifier.verifyRegistration(any())).thenReturn(result(AAGUID, "tpm", Set.of()));

        assertThatThrownBy(() -> svc().finish(new RegistrationFinishRequest("tok", null)))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("attestation format not accepted by tenant policy");
        verifyNoInteractions(mds);
    }

    @Test
    void mdsRejects_illegalArgument_beforePolicyCheck() throws Exception {
        stubChallenge();
        when(verifier.verifyRegistration(any())).thenReturn(result(AAGUID, "packed", Set.of()));
        when(mds.verify(true, AAGUID)).thenReturn(false);

        assertThatThrownBy(() -> svc().finish(new RegistrationFinishRequest("tok", null)))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("authenticator metadata verification failed");
        verifyNoInteractions(policy);
        verify(credentials, never()).saveAndFlush(any());
    }

    @Test
    void policyViolation_propagatesUnchanged_noSave() throws Exception {
        stubChallenge();
        when(verifier.verifyRegistration(any())).thenReturn(result(AAGUID, "packed", Set.of()));
        when(mds.verify(true, AAGUID)).thenReturn(true);
        RuntimeException boom = new BusinessException(
                com.crosscert.passkey.core.api.ErrorCode.INVALID_INPUT, "policy");
        doThrow(boom).when(policy).check(any(), any());

        assertThatThrownBy(() -> svc().finish(new RegistrationFinishRequest("tok", null)))
                .isSameAs(boom);
        verify(policy).check(TENANT, UUID.fromString("01020304-0506-0708-090a-0b0c0d0e0f10"));
        verify(credentials, never()).saveAndFlush(any());
        assertThat(count("failure")).isEqualTo(1.0);
    }

    @Test
    void nonSixteenByteAaguid_policyCheckedWithNullUuid() throws Exception {
        stubChallenge();
        byte[] shortAaguid = {1, 2, 3};
        when(verifier.verifyRegistration(any())).thenReturn(result(shortAaguid, "none", null));
        when(mds.verify(true, shortAaguid)).thenReturn(true);

        RegistrationFinishResponse resp = svc().finish(new RegistrationFinishRequest("tok", null));

        verify(policy).check(TENANT, null);
        assertThat(resp.getAaguid()).isEqualTo("010203");
    }

    @Test
    void success_persistsCredentialAndRespondsAndRecords() throws Exception {
        stubChallenge();
        when(verifier.verifyRegistration(any()))
                .thenReturn(result(AAGUID, "packed", new LinkedHashSet<>(List.of("usb", "nfc"))));
        when(mds.verify(true, AAGUID)).thenReturn(true);

        RegistrationFinishResponse resp = svc().finish(new RegistrationFinishRequest("tok", null));

        ArgumentCaptor<Credential> cap = ArgumentCaptor.forClass(Credential.class);
        verify(credentials).saveAndFlush(cap.capture());
        Credential c = cap.getValue();
        assertThat(c.getTenantId()).isEqualTo(TENANT);
        assertThat(c.getUserHandle()).containsExactly(9, 9);
        assertThat(c.getCredentialId()).isEqualTo(CRED_ID);
        assertThat(c.getCosePublicKey()).containsExactly(5, 6);
        assertThat(c.getAaguid()).isEqualTo(AAGUID);
        assertThat(c.getAttestationFmt()).isEqualTo("packed");
        assertThat(c.getTransports()).isEqualTo("usb,nfc");

        assertThat(resp.getCredentialId()).isEqualTo("AQIDBAUGBwgJCgsMDQ4PEBESExQ");
        assertThat(resp.getAaguid()).isEqualTo("0102030405060708090a0b0c0d0e0f10");
        assertThat(resp.getAttestationFormat()).isEqualTo("packed");
        assertThat(resp.getCreatedAt()).isEqualTo(NOW);

        assertThat(messages(Level.INFO)).containsExactly(
                "registration/finish ok: credentialIdTail=...MDQ4PEBESExQ"
                        + " aaguid=01020304-0506-0708-090a-0b0c0d0e0f10 format=packed");
        verify(ceremonyEvents).recordAfterCommit(TENANT, CeremonyAction.REGISTRATION_SUCCESS);
        assertThat(count("success")).isEqualTo(1.0);
        assertThat(count("failure")).isEqualTo(0.0);

        InOrder order = inOrder(store, tenants, verifier, mds, policy, credentials, ceremonyEvents);
        order.verify(store).takeRegistration("tok");
        order.verify(tenants).findById(TENANT);
        order.verify(verifier).verifyRegistration(any());
        order.verify(mds).verify(true, AAGUID);
        order.verify(policy).check(any(), any());
        order.verify(credentials).saveAndFlush(any());
        order.verify(ceremonyEvents).recordAfterCommit(any(), any());
    }

    @Test
    void success_nullAaguidAndEmptyTransports_shortIdMasked() throws Exception {
        stubChallenge();
        byte[] shortId = {1, 2, 3, 4};
        when(verifier.verifyRegistration(any())).thenReturn(new RegistrationResult(
                shortId, new byte[]{5}, 0L, null, "none", Set.of(), false, true, false, false));
        when(mds.verify(true, null)).thenReturn(true);

        RegistrationFinishResponse resp = svc().finish(new RegistrationFinishRequest("tok", null));

        ArgumentCaptor<Credential> cap = ArgumentCaptor.forClass(Credential.class);
        verify(credentials).saveAndFlush(cap.capture());
        assertThat(cap.getValue().getAaguid()).isNull();
        assertThat(cap.getValue().getTransports()).isNull();
        verify(policy).check(TENANT, null);
        assertThat(resp.getAaguid()).isNull();
        assertThat(resp.getCredentialId()).isEqualTo("AQIDBA");
        assertThat(messages(Level.INFO)).containsExactly(
                "registration/finish ok: credentialIdTail=*** aaguid=null format=none");
    }

    @Test
    void success_twelveCharId_fullyMasked_thirteenCharId_tailed() throws Exception {
        stubChallenge();
        when(verifier.verifyRegistration(any())).thenReturn(new RegistrationResult(
                new byte[9], new byte[]{5}, 0L, null, "none", null, false, true, false, false));
        when(mds.verify(true, null)).thenReturn(true);
        svc().finish(new RegistrationFinishRequest("tok", null));

        when(store.takeRegistration("tok2")).thenReturn(Optional.of(challenge(TENANT.toString())));
        when(verifier.verifyRegistration(any())).thenReturn(new RegistrationResult(
                new byte[10], new byte[]{5}, 0L, null, "none", null, false, true, false, false));
        svc().finish(new RegistrationFinishRequest("tok2", null));

        assertThat(messages(Level.INFO)).containsExactly(
                "registration/finish ok: credentialIdTail=*** aaguid=null format=none",
                "registration/finish ok: credentialIdTail=...AAAAAAAAAAAA aaguid=null format=none");
    }

    @Test
    void duplicate_logsWarnAndFailureMetric() throws Exception {
        stubChallenge();
        when(verifier.verifyRegistration(any())).thenReturn(result(AAGUID, "packed", Set.of()));
        when(mds.verify(true, AAGUID)).thenReturn(true);
        when(credentials.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> svc().finish(new RegistrationFinishRequest("tok", null)))
                .isExactlyInstanceOf(BusinessException.class)
                .hasMessage("credential already registered")
                .hasNoCause();
        assertThat(messages(Level.WARN)).containsExactly(
                "registration/finish rejected: duplicate credential for tenant " + TENANT);
        assertThat(count("failure")).isEqualTo(1.0);
        verifyNoInteractions(ceremonyEvents);
    }
}
