package com.crosscert.passkey.admin.web

import com.crosscert.passkey.admin.config.AdminSecurityConfig
import com.crosscert.passkey.admin.config.MeController
import com.crosscert.passkey.admin.config.ProfileController
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.metamodel.Metamodel
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.annotation.AliasFor
import org.springframework.test.context.bean.override.mockito.MockitoBean
import kotlin.reflect.KClass

/**
 * 화면(Thymeleaf) 컨트롤러용 `@WebMvcTest` 슬라이스.
 *
 * 기존 `*ControllerSecurityTest` 들과 같은 JPA 스텁과 MockBean 묶음에
 * [AdminSecurityConfig] 를 올리고, 모든 화면 모델이 쓰는 [MeController]·
 * [ProfileController](ViewModelAdvice 의 협력 빈)를 실제 빈으로 둔다.
 *
 * 사용법: `@AdminWebSliceTest(controllers = [XxxPageController::class])`. 화면 컨트롤러가
 * 부르는 그 밖의 REST 컨트롤러 빈은 테스트 클래스에서 `@MockitoBean` 으로 둔다.
 * `controllers` 를 비우면 화면 컨트롤러 없이 보안 체인·정적 리소스만 올라간다.
 * 묶음의 mock(예: `AdminUserRepository`, `AuditLogService`) 은 테스트에서
 * `@Autowired` 로 받아 스텁·검증한다.
 *
 * (Kotlin 전환) 아직 Java 인 contract 패키지 테스트가 `@AdminWebSliceTest(controllers = ...)` 와
 * `getAnnotation(AdminWebSliceTest.class).controllers()` 로 쓴다 — 애너테이션 속성 이름·타입(`Class<?>[]`) 동일.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@WebMvcTest(
    excludeAutoConfiguration = [
        org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration::class,
        org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration::class,
        org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration::class,
        org.springframework.boot.data.autoconfigure.web.DataWebAutoConfiguration::class,
    ],
)
@Import(
    AdminSecurityConfig::class,
    MeController::class,
    ProfileController::class,
    // 템플릿이 쓰는 표시 도우미(@fmt)와 ui:attrs 방언 — @Component 라 슬라이스에 자동으로 올라오지 않는다.
    com.crosscert.passkey.admin.web.format.ViewHelpers::class,
    com.crosscert.passkey.admin.web.support.UiDialect::class,
    AdminWebSliceTest.JpaStubs::class,
)
@MockitoBean(
    types = [
        com.crosscert.passkey.core.repository.AdminUserRepository::class,
        com.crosscert.passkey.admin.audit.AuditLogService::class,
        com.crosscert.passkey.admin.auth.AdminUserDetailsService::class,
        java.time.Clock::class,
        org.springframework.security.crypto.password.PasswordEncoder::class,
        com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource::class,
        // AdminSecurityConfig 의 adminLoginSuccessHandler / adminFilterChain 협력 빈.
        com.crosscert.passkey.admin.policy.SecurityPolicyService::class,
        com.crosscert.passkey.admin.auth.TenantBoundary::class,
        // @EnableJpaRepositories 가 실제 리포지토리를 만들지 않도록 전부 mock.
        com.crosscert.passkey.core.repository.TenantRepository::class,
        com.crosscert.passkey.core.repository.AuditLogRepository::class,
        com.crosscert.passkey.core.repository.CeremonyEventRepository::class,
        com.crosscert.passkey.core.repository.ApiKeyRepository::class,
        com.crosscert.passkey.core.repository.ApiKeyScopeRepository::class,
        com.crosscert.passkey.core.repository.CredentialRepository::class,
        com.crosscert.passkey.core.repository.SigningKeyRepository::class,
        com.crosscert.passkey.core.repository.SchedulerLeaseRepository::class,
        com.crosscert.passkey.core.repository.ActivityRepository::class,
        com.crosscert.passkey.core.repository.AdminSignupRequestRepository::class,
        com.crosscert.passkey.core.repository.AdminPasswordResetTokenRepository::class,
        com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository::class,
        com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository::class,
        com.crosscert.passkey.core.repository.SecurityPolicyRepository::class,
        com.crosscert.passkey.core.repository.TenantWebauthnSnapshotRepository::class,
        com.crosscert.passkey.core.repository.CredentialAuthEventRepository::class,
        com.crosscert.passkey.core.repository.AdminUserTenantRepository::class,
        com.crosscert.passkey.core.repository.SecurityIncidentRepository::class,
    ],
)
annotation class AdminWebSliceTest(
    /** 슬라이스에 올릴 화면 컨트롤러. 기본값은 컨트롤러 없음(마커 클래스). */
    @get:AliasFor(annotation = WebMvcTest::class, attribute = "controllers")
    val controllers: Array<KClass<*>> = [NoControllers::class],
) {

    /** `controllers` 기본값. @Controller 가 아니므로 어떤 컨트롤러도 스캔되지 않는다. */
    class NoControllers private constructor()

    /**
     * AdminApplication 의 @EnableJpaRepositories 가 슬라이스에서도
     * jpaSharedEM_entityManagerFactory 를 등록하므로, 빈 Metamodel 을 가진 스텁 EMF 를 둔다.
     */
    @TestConfiguration
    class JpaStubs {
        @Bean
        fun entityManagerFactory(): EntityManagerFactory {
            val metamodel = mock<Metamodel>()
            whenever(metamodel.entities).thenReturn(setOf())
            whenever(metamodel.managedTypes).thenReturn(setOf())
            whenever(metamodel.embeddables).thenReturn(setOf())

            val emf = mock<EntityManagerFactory>()
            whenever(emf.metamodel).thenReturn(metamodel)
            return emf
        }
    }
}
