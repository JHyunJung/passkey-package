package com.crosscert.passkey.admin.auth

import org.springframework.security.core.CredentialsContainer
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Spring Security principal — login 시 AdminUserDetailsService 가 채워서
 * SecurityContext 에 박는다. PLATFORM_OPERATOR 는 allowedTenantIds 가 빈 Set.
 *
 * Service 계층은 SecurityContextHolder 에서 이 객체를 꺼내 tenant boundary 검사.
 *
 * 세션(Spring Session Redis, JDK 직렬화)에 SecurityContext 째로 저장된다. 직렬화 형식은 필드 이름·타입
 * (id, email, passwordHash, role, allowedTenantIds, enabled, lockedUntil, clock)과 serialVersionUID 로
 * 정해진다 — serialVersionUID 나 필드 타입이 바뀌면 배포 전에 Redis 에 저장된 세션을 역직렬화하지 못하고,
 * 필드 이름이 바뀌면 그 값이 기본값(참조형은 null, `enabled` 는 false — 계정이 비활성으로 보인다)으로 복원된다.
 * serialVersionUID 는 이 클래스가 Java 로 작성돼 있던 시절의 기본 계산값(JDK `serialver`)을 고정한 것이다 —
 * 그 빌드가 써 둔 세션도 이 클래스로 읽힌다.
 *
 * [CredentialsContainer] 를 구현해, 인증 성공 직후 ProviderManager(eraseCredentialsAfterAuthentication 기본 true)가
 * [eraseCredentials] 로 bcrypt 해시를 지운다. BCrypt 비교·잠금/활성 판정은 그 전에 끝나므로 인증 결과는 같고,
 * 세션(Redis 직렬화 대상)에는 해시가 남지 않는다. 로그인 이후 이 객체의 [getPassword] 를 읽는 코드는 없다.
 * equals/hashCode 를 정의하지 않는다 — SessionRegistry(maximumSessions)의 principal 비교 방식이 바뀌지 않게 하기 위함이다.
 *
 * null 인자(core Java 엔티티의 플랫폼 타입 값 등)는 Kotlin non-null 파라미터 검사로 생성 시점에 NullPointerException.
 * nullable 인 것은 lockedUntil 뿐이다 (passwordHash 는 생성자에서 non-null 이고, 소거 후에만 내부 필드가 null).
 * bcrypt_hash 가 NULL 인 계정이 여기서 NPE 로 막히는 것은 AdminUserDetailsTest.constructor_rejectsNullPasswordHash 가 고정한다 —
 * DaoAuthenticationProvider 가 이를 InternalAuthenticationServiceException 으로 감싸므로 로그인 실패 사유는 "other" 이고
 * 실패 횟수·잠금은 늘지 않는다(AdminSecurityConfig.adminLoginFailureHandler 는 BadCredentialsException 만 센다).
 */
class AdminUserDetails(
    val id: UUID,
    private val email: String,
    passwordHash: String,
    val role: String, // "PLATFORM_OPERATOR" | "RP_ADMIN"
    allowedTenantIds: Set<UUID>,
    private val enabled: Boolean,
    private val lockedUntil: OffsetDateTime?,
    private val clock: Clock,
) : UserDetails, CredentialsContainer {

    /**
     * 생성자 인자는 non-null(Kotlin 검사 = 생성 시점 NPE)이고, 소거 가능한 값은
     * 본문 필드로 둔다. 필드 이름·JVM 타입(String)이 그대로라 직렬화 형식이 변하지 않는다. eraseCredentials() 후 null.
     */
    private var passwordHash: String? = passwordHash

    /** PLATFORM_OPERATOR 는 빈 Set. Set.copyOf: 불변 사본 — null 원소가 있으면 NPE, contains(null) 도 NPE(TenantBoundary·ActiveTenantResolver 에 null tenantId 가 오면 이 NPE 가 난다). */
    val allowedTenantIds: Set<UUID> = java.util.Set.copyOf(allowedTenantIds)

    fun isPlatformOperator(): Boolean = "PLATFORM_OPERATOR" == role

    fun isRpAdmin(): Boolean = "RP_ADMIN" == role

    override fun getAuthorities(): Collection<GrantedAuthority> =
        java.util.List.of(SimpleGrantedAuthority("ROLE_$role"))

    override fun getPassword(): String? = passwordHash

    override fun getUsername(): String = email

    override fun isAccountNonExpired(): Boolean = true

    override fun isAccountNonLocked(): Boolean =
        lockedUntil == null || OffsetDateTime.now(clock).isAfter(lockedUntil)

    override fun isCredentialsNonExpired(): Boolean = true

    override fun isEnabled(): Boolean = enabled

    /** 인증 성공 후 ProviderManager 가 호출 — bcrypt 해시만 지운다. */
    override fun eraseCredentials() {
        passwordHash = null
    }

    companion object {
        /** Java 시절 클래스의 기본 계산값(`serialver`, 2026-11-08 base). 바꾸면 저장된 세션을 역직렬화하지 못한다(클래스 KDoc). */
        private const val serialVersionUID = -8462830666458161297L
    }
}
