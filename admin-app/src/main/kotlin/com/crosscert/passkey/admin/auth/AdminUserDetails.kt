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
 * 정해지므로 원 Java 클래스와 똑같이 둔다. 원 Java 클래스에는 명시 serialVersionUID 가 없었다 — 그 기본
 * 계산값(JDK `serialver`)을 그대로 고정해 Java 빌드가 써 둔 세션도 이 클래스로 읽힌다.
 *
 * S16: [CredentialsContainer] 를 구현해, 인증 성공 직후 ProviderManager(eraseCredentialsAfterAuthentication 기본 true)가
 * [eraseCredentials] 로 bcrypt 해시를 지운다. BCrypt 비교·잠금/활성 판정은 그 전에 끝나므로 인증 결과는 같고,
 * 세션(Redis 직렬화 대상)에는 해시가 남지 않는다. 로그인 이후 이 객체의 [getPassword] 를 읽는 코드는 없다.
 * equals/hashCode 를 정의하지 않는다 — SessionRegistry(maximumSessions)의 principal 비교 방식이 바뀌지 않게 하기 위함이다.
 *
 * null 인자(core Java 엔티티의 플랫폼 타입 값 등): 원 Java 의 Objects.requireNonNull 과 같이 NullPointerException
 * (Kotlin non-null 파라미터 검사 — 메시지만 다르다). nullable 인 것은 lockedUntil 뿐이다
 * (passwordHash 는 생성자에서 non-null 이고, 소거 후에만 내부 필드가 null).
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
     * 생성자 인자는 non-null(Kotlin 검사 = 생성 시점 NPE, 원 Java requireNonNull 과 동일)이고, 소거 가능한 값은
     * 본문 필드로 둔다. 필드 이름·JVM 타입(String)이 그대로라 직렬화 형식이 변하지 않는다. eraseCredentials() 후 null.
     */
    private var passwordHash: String? = passwordHash

    /** PLATFORM_OPERATOR 는 빈 Set. Set.copyOf: 불변 사본 — null 원소·contains(null) NPE 도 원 Java 와 같다. */
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
        /** 원 Java 클래스의 기본 계산 serialVersionUID(`serialver`, 2026-11-08 base). */
        private const val serialVersionUID = -8462830666458161297L
    }
}
