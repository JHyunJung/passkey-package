package com.crosscert.passkey.app.fido2

import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationFinishRequest
import com.crosscert.passkey.app.fido2.authentication.AuthenticationFinishService
import com.crosscert.passkey.app.fido2.authentication.AuthenticationStartService
import com.crosscert.passkey.app.fido2.challenge.AuthenticationChallenge
import com.crosscert.passkey.app.fido2.challenge.ChallengeIssuer
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore
import com.crosscert.passkey.app.fido2.credential.CredentialSelfService
import com.crosscert.passkey.app.fido2.mds.MdsVerifier
import com.crosscert.passkey.app.fido2.registration.RegistrationFinishService
import com.crosscert.passkey.app.fido2.registration.RegistrationStartService
import com.crosscert.passkey.core.ceremony.CeremonyEventRecorder
import com.crosscert.passkey.core.ceremony.CredentialAuthEventRecorder
import com.crosscert.passkey.core.entity.Credential
import com.crosscert.passkey.core.entity.Tenant
import com.crosscert.passkey.core.jwt.IdTokenIssuer
import com.crosscert.passkey.core.mds.MdsAaguidCache
import com.crosscert.passkey.core.policy.AaguidPolicyChecker
import com.crosscert.passkey.core.repository.CredentialRepository
import com.crosscert.passkey.core.repository.TenantRepository
import com.crosscert.passkey.core.tenant.TenantContextHolder
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerifier
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * fido2 서비스가 Kotlin(final-by-default) 으로 바뀐 뒤에도 kotlin-spring(all-open) 덕분에
 * `@Transactional` 빈이 CGLIB 프록시로 감싸지고, 서비스 메서드가 실제 트랜잭션 안에서
 * 실행되는지 확인한다. DB 없이 최소 컨텍스트(서비스 6개 + 목 의존성 + 무자원 트랜잭션 매니저)로 띄운다.
 */
@SpringJUnitConfig(KotlinProxyWiringTest.Config::class)
class KotlinProxyWiringTest @Autowired constructor(
    private val registrationStart: RegistrationStartService,
    private val registrationFinish: RegistrationFinishService,
    private val authenticationStart: AuthenticationStartService,
    private val authenticationFinish: AuthenticationFinishService,
    private val credentialSelf: CredentialSelfService,
    private val mdsVerifier: MdsVerifier,
    private val credentialRepository: CredentialRepository,
    private val tenantRepository: TenantRepository,
    private val challengeStore: ChallengeStore,
) {

    @Configuration
    @EnableTransactionManagement
    @Import(
        RegistrationStartService::class, RegistrationFinishService::class,
        AuthenticationStartService::class, AuthenticationFinishService::class,
        CredentialSelfService::class, MdsVerifier::class,
    )
    class Config {
        @Bean fun transactionManager(): PlatformTransactionManager = NoOpTxManager()
        @Bean fun tenantRepository(): TenantRepository = mock()
        @Bean fun credentialRepository(): CredentialRepository = mock()
        @Bean fun challengeIssuer(): ChallengeIssuer = mock()
        @Bean fun challengeStore(): ChallengeStore = mock()
        @Bean fun objectMapper(): ObjectMapper = ObjectMapper()
        @Bean fun clock(): Clock = Clock.systemUTC()
        @Bean fun ceremonyMetrics(): CeremonyMetrics = CeremonyMetrics(SimpleMeterRegistry())
        @Bean fun ceremonyEventRecorder(): CeremonyEventRecorder = mock()
        @Bean fun credentialAuthEventRecorder(): CredentialAuthEventRecorder = mock()
        @Bean fun webAuthnVerifier(): WebAuthnVerifier = mock()
        @Bean fun mdsAaguidCache(): MdsAaguidCache = mock()
        @Bean fun aaguidPolicyChecker(): AaguidPolicyChecker = mock()
        @Bean fun idTokenIssuer(): IdTokenIssuer = mock()
    }

    /** 자원 없이 트랜잭션 동기화만 활성화하는 테스트용 매니저. */
    class NoOpTxManager : AbstractPlatformTransactionManager() {
        override fun doGetTransaction(): Any = Any()
        override fun doBegin(transaction: Any, definition: TransactionDefinition) {}
        override fun doCommit(status: DefaultTransactionStatus) {}
        override fun doRollback(status: DefaultTransactionStatus) {}
    }

    @Test
    fun transactionalServices_areCglibProxies() {
        for (bean in listOf(registrationStart, registrationFinish, authenticationStart, authenticationFinish, credentialSelf)) {
            assertThat(AopUtils.isAopProxy(bean)).`as`(bean.javaClass.name).isTrue()
            assertThat(AopUtils.isCglibProxy(bean)).`as`(bean.javaClass.name).isTrue()
        }
        // @Transactional 이 없는 @Component — 프록시 대상 아님(동작만 확인)
        assertThat(AopUtils.isAopProxy(mdsVerifier)).isFalse()
        assertThat(mdsVerifier.verify(false, null)).isTrue()
    }

    @Test
    fun serviceMethod_runsInsideActualReadOnlyTransaction() {
        val active = AtomicBoolean()
        val readOnly = AtomicBoolean()
        whenever(credentialRepository.findByUserHandle(anyOrNull())).thenAnswer {
            active.set(TransactionSynchronizationManager.isActualTransactionActive())
            readOnly.set(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            listOf<Credential>()
        }

        assertThat(credentialSelf.list(byteArrayOf(1))).isEmpty()

        assertThat(active).isTrue()
        assertThat(readOnly).isTrue()
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
    }

    @Test
    fun authenticationFinish_lockAcquiredInsideActualReadWriteTransaction() {
        val tenantId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        TenantContextHolder.set(tenantId)
        try {
            whenever(challengeStore.takeAuthentication("tok")).thenReturn(
                Optional.of(AuthenticationChallenge(tenantId.toString(), byteArrayOf(7), null, Instant.now())),
            )
            val tenant: Tenant = mock()
            whenever(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant))

            val active = AtomicBoolean()
            val readOnly = AtomicBoolean(true)
            whenever(credentialRepository.findByCredentialIdForUpdate(anyOrNull())).thenAnswer {
                active.set(TransactionSynchronizationManager.isActualTransactionActive())
                readOnly.set(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                Optional.empty<Credential>()
            }

            val pkc = ObjectMapper().readTree("""{"rawId":"AQID"}""")
            assertThatThrownBy { authenticationFinish.finish(AuthenticationFinishRequest("tok", pkc)) }
                .isExactlyInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("credential not registered")

            verify(credentialRepository).findByCredentialIdForUpdate(anyOrNull())
            assertThat(active).isTrue()
            assertThat(readOnly).isFalse()
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
        } finally {
            TenantContextHolder.clear()
        }
    }
}
