package com.crosscert.passkey.app.fido2;

import com.crosscert.passkey.app.fido2.authentication.AuthenticationFinishService;
import com.crosscert.passkey.app.fido2.authentication.AuthenticationStartService;
import com.crosscert.passkey.app.fido2.challenge.ChallengeIssuer;
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore;
import com.crosscert.passkey.app.fido2.credential.CredentialSelfService;
import com.crosscert.passkey.app.fido2.mds.MdsVerifier;
import com.crosscert.passkey.app.fido2.registration.RegistrationFinishService;
import com.crosscert.passkey.app.fido2.registration.RegistrationStartService;
import com.crosscert.passkey.core.ceremony.CeremonyEventRecorder;
import com.crosscert.passkey.core.ceremony.CredentialAuthEventRecorder;
import com.crosscert.passkey.core.jwt.IdTokenIssuer;
import com.crosscert.passkey.core.mds.MdsAaguidCache;
import com.crosscert.passkey.core.policy.AaguidPolicyChecker;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationFinishRequest;
import com.crosscert.passkey.app.fido2.challenge.AuthenticationChallenge;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.tenant.TenantContextHolder;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * fido2 서비스가 Kotlin(final-by-default) 으로 바뀐 뒤에도 kotlin-spring(all-open) 덕분에
 * {@code @Transactional} 빈이 CGLIB 프록시로 감싸지고, 서비스 메서드가 실제 트랜잭션 안에서
 * 실행되는지 확인한다. DB 없이 최소 컨텍스트(서비스 6개 + 목 의존성 + 무자원 트랜잭션 매니저)로 띄운다.
 */
@SpringJUnitConfig(KotlinProxyWiringTest.Config.class)
class KotlinProxyWiringTest {

    @Configuration
    @EnableTransactionManagement
    @Import({RegistrationStartService.class, RegistrationFinishService.class,
            AuthenticationStartService.class, AuthenticationFinishService.class,
            CredentialSelfService.class, MdsVerifier.class})
    static class Config {
        @Bean PlatformTransactionManager transactionManager() { return new NoOpTxManager(); }
        @Bean TenantRepository tenantRepository() { return mock(TenantRepository.class); }
        @Bean CredentialRepository credentialRepository() { return mock(CredentialRepository.class); }
        @Bean ChallengeIssuer challengeIssuer() { return mock(ChallengeIssuer.class); }
        @Bean ChallengeStore challengeStore() { return mock(ChallengeStore.class); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean CeremonyMetrics ceremonyMetrics() { return new CeremonyMetrics(new SimpleMeterRegistry()); }
        @Bean CeremonyEventRecorder ceremonyEventRecorder() { return mock(CeremonyEventRecorder.class); }
        @Bean CredentialAuthEventRecorder credentialAuthEventRecorder() { return mock(CredentialAuthEventRecorder.class); }
        @Bean WebAuthnVerifier webAuthnVerifier() { return mock(WebAuthnVerifier.class); }
        @Bean MdsAaguidCache mdsAaguidCache() { return mock(MdsAaguidCache.class); }
        @Bean AaguidPolicyChecker aaguidPolicyChecker() { return mock(AaguidPolicyChecker.class); }
        @Bean IdTokenIssuer idTokenIssuer() { return mock(IdTokenIssuer.class); }
    }

    /** 자원 없이 트랜잭션 동기화만 활성화하는 테스트용 매니저. */
    static class NoOpTxManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }

    @Autowired RegistrationStartService registrationStart;
    @Autowired RegistrationFinishService registrationFinish;
    @Autowired AuthenticationStartService authenticationStart;
    @Autowired AuthenticationFinishService authenticationFinish;
    @Autowired CredentialSelfService credentialSelf;
    @Autowired MdsVerifier mdsVerifier;
    @Autowired CredentialRepository credentialRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired ChallengeStore challengeStore;

    @Test
    void transactionalServices_areCglibProxies() {
        for (Object bean : List.of(registrationStart, registrationFinish,
                authenticationStart, authenticationFinish, credentialSelf)) {
            assertThat(AopUtils.isAopProxy(bean)).as(bean.getClass().getName()).isTrue();
            assertThat(AopUtils.isCglibProxy(bean)).as(bean.getClass().getName()).isTrue();
        }
        // @Transactional 이 없는 @Component — 프록시 대상 아님(동작만 확인)
        assertThat(AopUtils.isAopProxy(mdsVerifier)).isFalse();
        assertThat(mdsVerifier.verify(false, null)).isTrue();
    }

    @Test
    void serviceMethod_runsInsideActualReadOnlyTransaction() {
        AtomicBoolean active = new AtomicBoolean();
        AtomicBoolean readOnly = new AtomicBoolean();
        when(credentialRepository.findByUserHandle(any())).thenAnswer(inv -> {
            active.set(TransactionSynchronizationManager.isActualTransactionActive());
            readOnly.set(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return List.of();
        });

        assertThat(credentialSelf.list(new byte[]{1})).isEmpty();

        assertThat(active).isTrue();
        assertThat(readOnly).isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void authenticationFinish_lockAcquiredInsideActualReadWriteTransaction() {
        UUID tenantId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        TenantContextHolder.set(tenantId);
        try {
            when(challengeStore.takeAuthentication("tok")).thenReturn(Optional.of(
                    new AuthenticationChallenge(tenantId.toString(), new byte[]{7}, null, Instant.now())));
            Tenant tenant = mock(Tenant.class);
            when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

            AtomicBoolean active = new AtomicBoolean();
            AtomicBoolean readOnly = new AtomicBoolean(true);
            when(credentialRepository.findByCredentialIdForUpdate(any())).thenAnswer(inv -> {
                active.set(TransactionSynchronizationManager.isActualTransactionActive());
                readOnly.set(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                return Optional.empty();
            });

            JsonNode pkc = new ObjectMapper().readTree("{\"rawId\":\"AQID\"}");
            assertThatThrownBy(() -> authenticationFinish.finish(new AuthenticationFinishRequest("tok", pkc)))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("credential not registered");

            verify(credentialRepository).findByCredentialIdForUpdate(any());
            assertThat(active).isTrue();
            assertThat(readOnly).isFalse();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        } finally {
            TenantContextHolder.clear();
        }
    }
}
