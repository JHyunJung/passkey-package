package com.crosscert.passkey.admin.web;

import com.crosscert.passkey.admin.config.AdminSecurityConfig;
import com.crosscert.passkey.admin.config.MeController;
import com.crosscert.passkey.admin.config.ProfileController;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Metamodel;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 화면(Thymeleaf) 컨트롤러용 {@code @WebMvcTest} 슬라이스.
 *
 * <p>기존 {@code *ControllerSecurityTest} 들과 같은 JPA 스텁과 MockBean 묶음에
 * {@link AdminSecurityConfig} 를 올리고, 모든 화면 모델이 쓰는 {@link MeController}·
 * {@link ProfileController}(ViewModelAdvice 의 협력 빈)를 실제 빈으로 둔다.
 *
 * <p>사용법: {@code @AdminWebSliceTest(controllers = XxxPageController.class)}. 화면 컨트롤러가
 * 부르는 그 밖의 REST 컨트롤러 빈은 테스트 클래스에서 {@code @MockBean} 으로 둔다.
 * {@code controllers} 를 비우면 화면 컨트롤러 없이 보안 체인·정적 리소스만 올라간다.
 * 묶음의 mock(예: {@code AdminUserRepository}, {@code AuditLogService}) 은 테스트에서
 * {@code @Autowired} 로 받아 스텁·검증한다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@WebMvcTest(excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.web.SpringDataWebAutoConfiguration.class
})
@Import({
        AdminSecurityConfig.class,
        MeController.class,
        ProfileController.class,
        AdminWebSliceTest.JpaStubs.class
})
@MockBean({
        com.crosscert.passkey.core.repository.AdminUserRepository.class,
        com.crosscert.passkey.admin.audit.AuditLogService.class,
        com.crosscert.passkey.admin.auth.AdminUserDetailsService.class,
        java.time.Clock.class,
        org.springframework.security.crypto.password.PasswordEncoder.class,
        com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource.class,
        // AdminSecurityConfig 의 adminLoginSuccessHandler / adminFilterChain 협력 빈.
        com.crosscert.passkey.admin.policy.SecurityPolicyService.class,
        com.crosscert.passkey.admin.auth.TenantBoundary.class,
        // @EnableJpaRepositories 가 실제 리포지토리를 만들지 않도록 전부 mock.
        com.crosscert.passkey.core.repository.TenantRepository.class,
        com.crosscert.passkey.core.repository.AuditLogRepository.class,
        com.crosscert.passkey.core.repository.CeremonyEventRepository.class,
        com.crosscert.passkey.core.repository.ApiKeyRepository.class,
        com.crosscert.passkey.core.repository.ApiKeyScopeRepository.class,
        com.crosscert.passkey.core.repository.CredentialRepository.class,
        com.crosscert.passkey.core.repository.SigningKeyRepository.class,
        com.crosscert.passkey.core.repository.SchedulerLeaseRepository.class,
        com.crosscert.passkey.core.repository.ActivityRepository.class,
        com.crosscert.passkey.core.repository.AdminSignupRequestRepository.class,
        com.crosscert.passkey.core.repository.AdminPasswordResetTokenRepository.class,
        com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository.class,
        com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository.class,
        com.crosscert.passkey.core.repository.SecurityPolicyRepository.class,
        com.crosscert.passkey.core.repository.TenantWebauthnSnapshotRepository.class,
        com.crosscert.passkey.core.repository.CredentialAuthEventRepository.class,
        com.crosscert.passkey.core.repository.AdminUserTenantRepository.class,
        com.crosscert.passkey.core.repository.SecurityIncidentRepository.class
})
public @interface AdminWebSliceTest {

    /** 슬라이스에 올릴 화면 컨트롤러. 기본값은 컨트롤러 없음(마커 클래스). */
    @AliasFor(annotation = WebMvcTest.class, attribute = "controllers")
    Class<?>[] controllers() default NoControllers.class;

    /** {@code controllers} 기본값. @Controller 가 아니므로 어떤 컨트롤러도 스캔되지 않는다. */
    final class NoControllers {
        private NoControllers() {}
    }

    /**
     * AdminApplication 의 @EnableJpaRepositories 가 슬라이스에서도
     * jpaSharedEM_entityManagerFactory 를 등록하므로, 빈 Metamodel 을 가진 스텁 EMF 를 둔다.
     */
    @TestConfiguration
    class JpaStubs {
        @Bean
        EntityManagerFactory entityManagerFactory() {
            Metamodel metamodel = mock(Metamodel.class);
            when(metamodel.getEntities()).thenReturn(Set.of());
            when(metamodel.getManagedTypes()).thenReturn(Set.of());
            when(metamodel.getEmbeddables()).thenReturn(Set.of());

            EntityManagerFactory emf = mock(EntityManagerFactory.class);
            when(emf.getMetamodel()).thenReturn(metamodel);
            return emf;
        }
    }
}
