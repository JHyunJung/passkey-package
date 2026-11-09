package com.crosscert.passkey.admin.auth

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
 * null 인자(core Java 엔티티의 플랫폼 타입 값 등): 원 Java 의 Objects.requireNonNull 과 같이 NullPointerException
 * (Kotlin non-null 파라미터 검사 — 메시지만 다르다). lockedUntil 만 nullable.
 */
class AdminUserDetails(
    val id: UUID,
    private val email: String,
    private val passwordHash: String,
    val role: String, // "PLATFORM_OPERATOR" | "RP_ADMIN"
    allowedTenantIds: Set<UUID>,
    private val enabled: Boolean,
    private val lockedUntil: OffsetDateTime?,
    private val clock: Clock,
) : UserDetails {

    /** PLATFORM_OPERATOR 는 빈 Set. Set.copyOf: 불변 사본 — null 원소·contains(null) NPE 도 원 Java 와 같다. */
    val allowedTenantIds: Set<UUID> = java.util.Set.copyOf(allowedTenantIds)

    fun isPlatformOperator(): Boolean = "PLATFORM_OPERATOR" == role

    fun isRpAdmin(): Boolean = "RP_ADMIN" == role

    override fun getAuthorities(): Collection<GrantedAuthority> =
        java.util.List.of(SimpleGrantedAuthority("ROLE_$role"))

    override fun getPassword(): String = passwordHash

    override fun getUsername(): String = email

    override fun isAccountNonExpired(): Boolean = true

    override fun isAccountNonLocked(): Boolean =
        lockedUntil == null || OffsetDateTime.now(clock).isAfter(lockedUntil)

    override fun isCredentialsNonExpired(): Boolean = true

    override fun isEnabled(): Boolean = enabled

    companion object {
        /** 원 Java 클래스의 기본 계산 serialVersionUID(`serialver`, 2026-11-08 base). */
        private const val serialVersionUID = -8462830666458161297L
    }
}
