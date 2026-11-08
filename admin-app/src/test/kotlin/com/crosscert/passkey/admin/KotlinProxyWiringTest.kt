package com.crosscert.passkey.admin

import com.crosscert.passkey.admin.auth.RecoveryCodeService
import com.crosscert.passkey.admin.credential.CredentialAdminController
import com.crosscert.passkey.admin.credential.CredentialAdminService
import com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository
import com.crosscert.passkey.core.repository.AdminUserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * admin-app 본 코드가 Kotlin(final-by-default) 으로 바뀐 뒤에도 kotlin-spring(all-open) 덕분에
 * `@Transactional`·`@PreAuthorize` 빈이 CGLIB 프록시로 감싸지고, 메서드가 실제 트랜잭션(읽기/쓰기 구분) 안에서
 * 돌며, `@PreAuthorize` 의 `#param` SpEL 이 Kotlin 파라미터 이름으로 해석되는지(허용·거부 모두) 확인한다.
 * DB·웹 없이 최소 컨텍스트(대상 빈 + 목 의존성 + 무자원 트랜잭션 매니저 + 메서드 보안)로 띄운다.
 */
@SpringJUnitConfig(KotlinProxyWiringTest.Config::class)
class KotlinProxyWiringTest @Autowired constructor(
    private val recoveryCodes: RecoveryCodeService,
    private val credentialController: CredentialAdminController,
    private val paramGuarded: ParamGuardedBean,
    private val recoveryRepo: AdminUserRecoveryCodeRepository,
    private val credentialService: CredentialAdminService,
) {

    @Configuration
    @EnableTransactionManagement
    @EnableMethodSecurity
    @Import(RecoveryCodeService::class, CredentialAdminController::class, ParamGuardedBean::class)
    class Config {
        @Bean fun transactionManager(): PlatformTransactionManager = NoOpTxManager()
        @Bean fun recoveryCodeRepository(): AdminUserRecoveryCodeRepository = mock()
        @Bean fun credentialAdminService(): CredentialAdminService = mock()
        @Bean fun adminUserRepository(): AdminUserRepository = mock()
        @Bean fun clock(): Clock = Clock.systemUTC()
    }

    /**
     * `#tenantId` 를 참조하는 `@PreAuthorize` 를 가진 Kotlin 빈(테스트 전용). 본 코드에는 아직 `#param` SpEL 이 없어
     * 같은 컴파일 설정(`javaParameters = true`, kotlin-spring)으로 만든 빈으로 해석 경로를 고정한다.
     */
    @Component
    class ParamGuardedBean {
        @PreAuthorize("#tenantId != null and #tenantId.toString() == authentication.name")
        fun read(tenantId: UUID?, label: String): String = "ok:$label"
    }

    /** 자원 없이 트랜잭션 동기화만 활성화하는 테스트용 매니저. */
    class NoOpTxManager : AbstractPlatformTransactionManager() {
        override fun doGetTransaction(): Any = Any()
        override fun doBegin(transaction: Any, definition: TransactionDefinition) {}
        override fun doCommit(status: DefaultTransactionStatus) {}
        override fun doRollback(status: DefaultTransactionStatus) {}
    }

    @AfterEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
    }

    private fun authenticate(name: String, vararg roles: String) {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            name, "n/a", roles.map { SimpleGrantedAuthority("ROLE_$it") },
        )
    }

    @Test
    fun transactionalAndPreAuthorizeBeans_areCglibProxies() {
        for (bean in listOf(recoveryCodes, credentialController, paramGuarded)) {
            assertThat(AopUtils.isAopProxy(bean)).`as`(bean.javaClass.name).isTrue()
            assertThat(AopUtils.isCglibProxy(bean)).`as`(bean.javaClass.name).isTrue()
        }
    }

    @Test
    fun kotlinMainClasses_keepJavaParameterNames() {
        // javaParameters = true: 본 코드 바이트코드에 파라미터 이름이 남는다(Java -parameters 와 동일).
        val consume = RecoveryCodeService::class.java.getMethod("consume", UUID::class.java, String::class.java)
        assertThat(consume.parameters.map { it.isNamePresent }).containsOnly(true)
        assertThat(consume.parameters.map { it.name }).containsExactly("adminUserId", "code")
    }

    @Test
    fun readOnlyMethod_runsInsideActualReadOnlyTransaction() {
        val active = AtomicBoolean()
        val readOnly = AtomicBoolean()
        whenever(recoveryRepo.countByAdminUserIdAndUsedAtIsNull(anyOrNull())).thenAnswer {
            active.set(TransactionSynchronizationManager.isActualTransactionActive())
            readOnly.set(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            3L
        }

        assertThat(recoveryCodes.remaining(UUID.randomUUID())).isEqualTo(3L)

        assertThat(active).isTrue()
        assertThat(readOnly).isTrue()
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
    }

    @Test
    fun writeMethod_runsInsideActualReadWriteTransaction() {
        val active = AtomicBoolean()
        val readOnly = AtomicBoolean(true)
        whenever(recoveryRepo.markUsed(anyOrNull(), anyOrNull(), anyOrNull())).thenAnswer {
            active.set(TransactionSynchronizationManager.isActualTransactionActive())
            readOnly.set(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            1
        }

        assertThat(recoveryCodes.consume(UUID.randomUUID(), "abcd-efgh")).isTrue()

        assertThat(active).isTrue()
        assertThat(readOnly).isFalse()
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
    }

    @Test
    fun preAuthorizeParam_resolvesKotlinParameterName_allowsMatch() {
        val tenantId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        authenticate(tenantId.toString(), "RP_ADMIN")

        assertThat(paramGuarded.read(tenantId, "x")).isEqualTo("ok:x")
    }

    @Test
    fun preAuthorizeParam_resolvesKotlinParameterName_deniesMismatchAndNull() {
        authenticate("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "RP_ADMIN")

        // 이름이 해석되지 않으면 #tenantId 가 null 이 되어 허용 케이스도 거부된다 — 위 허용 테스트와 짝으로 판정.
        assertThatThrownBy { paramGuarded.read(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"), "x") }
            .isInstanceOf(AccessDeniedException::class.java)
        assertThatThrownBy { paramGuarded.read(null, "x") }
            .isInstanceOf(AccessDeniedException::class.java)
    }

    @Test
    fun mainKotlinController_preAuthorizeRole_allowsAndDenies() {
        val tenantId = UUID.randomUUID()

        authenticate("rp@example.com", "RP_ADMIN")
        credentialController.list(tenantId, 0, 50, null)
        verify(credentialService).list(tenantId, 0, 50, null)

        authenticate("nobody@example.com", "SOMETHING_ELSE")
        assertThatThrownBy { credentialController.list(tenantId, 1, 50, null) }
            .isInstanceOf(AccessDeniedException::class.java)
        verify(credentialService, never()).list(any(), org.mockito.kotlin.eq(1), any(), anyOrNull())
    }
}
