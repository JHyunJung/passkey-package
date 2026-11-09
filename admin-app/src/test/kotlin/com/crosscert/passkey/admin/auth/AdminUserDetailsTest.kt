package com.crosscert.passkey.admin.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.core.CredentialsContainer
import org.springframework.security.core.GrantedAuthority
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.Base64
import java.util.UUID

class AdminUserDetailsTest {

    companion object {
        /**
         * CredentialsContainer 도입 전 AdminUserDetails 의 JVM 기본 계산 serialVersionUID
         * (변경 전 소스를 javac 로 컴파일 후 `serialver` 로 산출). 이 값이 유지돼야
         * 배포 전 Redis 에 저장된 세션(JdkSerializationRedisSerializer)이 그대로 읽힌다.
         */
        private const val PRE_CHANGE_SERIAL_VERSION_UID = -8462830666458161297L

        /**
         * 변경 전 클래스로 직렬화한 AdminUserDetails — Redis 세션에 이미 들어 있는 형태.
         * id=11111111-2222-3333-4444-555555555555, email=legacy@example.com,
         * passwordHash=$2a$12$legacyhash, role=RP_ADMIN,
         * allowedTenantIds={aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee}, enabled=true,
         * lockedUntil=2026-01-01T00:00Z, clock=Clock.systemUTC() (JDK 17).
         */
        private val PRE_CHANGE_SERIALIZED = listOf(
            "rO0ABXNyADFjb20uY3Jvc3NjZXJ0LnBhc3NrZXkuYWRtaW4uYXV0aC5BZG1pblVzZXJEZXRhaWxz",
            "io38WMt6n28CAAhaAAdlbmFibGVkTAAQYWxsb3dlZFRlbmFudElkc3QAD0xqYXZhL3V0aWwvU2V0",
            "O0wABWNsb2NrdAARTGphdmEvdGltZS9DbG9jaztMAAVlbWFpbHQAEkxqYXZhL2xhbmcvU3RyaW5n",
            "O0wAAmlkdAAQTGphdmEvdXRpbC9VVUlEO0wAC2xvY2tlZFVudGlsdAAaTGphdmEvdGltZS9PZmZz",
            "ZXREYXRlVGltZTtMAAxwYXNzd29yZEhhc2hxAH4AA0wABHJvbGVxAH4AA3hwAXNyABFqYXZhLnV0",
            "aWwuQ29sbFNlcleOq7Y6G6gRAwABSQADdGFneHAAAAACdwQAAAABc3IADmphdmEudXRpbC5VVUlE",
            "vJkD95hthS8CAAJKAAxsZWFzdFNpZ0JpdHNKAAttb3N0U2lnQml0c3hw3d3u7u7u7u6qqqqqu7vM",
            "zHhzcgAbamF2YS50aW1lLkNsb2NrJFN5c3RlbUNsb2NrXYuIFFEHaesCAAFMAAR6b25ldAASTGph",
            "dmEvdGltZS9ab25lSWQ7eHBzcgANamF2YS50aW1lLlNlcpVdhLobIkiyDAAAeHB3AggAeHQAEmxl",
            "Z2FjeUBleGFtcGxlLmNvbXNxAH4ACUREVVVVVVVVERERESIiMzNzcQB+AA53CQoAAAfqAQH/AHh0",
            "ABEkMmEkMTIkbGVnYWN5aGFzaHQACFJQX0FETUlO",
        ).joinToString("")

        private fun jdkSerialize(o: Any): ByteArray {
            val bos = ByteArrayOutputStream()
            ObjectOutputStream(bos).use { it.writeObject(o) }
            return bos.toByteArray()
        }
    }

    private fun rpAdmin(tenants: Set<UUID>): AdminUserDetails =
        AdminUserDetails(
            UUID.randomUUID(), "rp@x.com", "hash",
            "RP_ADMIN", tenants, true, null, Clock.systemUTC(),
        )

    @Test
    fun rpAdminExposesAllowedTenants() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        val me = rpAdmin(setOf(t1, t2))
        assertThat(me.isRpAdmin()).isTrue()
        assertThat(me.allowedTenantIds).containsExactlyInAnyOrder(t1, t2)
    }

    @Test
    fun platformOperatorHasEmptyAllowedTenants() {
        val me = AdminUserDetails(
            UUID.randomUUID(), "ops@x.com", "hash",
            "PLATFORM_OPERATOR", setOf(), true, null, Clock.systemUTC(),
        )
        assertThat(me.isPlatformOperator()).isTrue()
        assertThat(me.allowedTenantIds).isEmpty()
    }

    // ---- S16 특성화: 변경 전후 모두 통과해야 한다 -------------------------------

    @Test
    fun getPassword_returnsConstructorHash() {
        // DaoAuthenticationProvider 가 BCrypt 비교에 쓰는 값 — 소거 전에는 그대로여야 한다.
        assertThat(rpAdmin(setOf(UUID.randomUUID())).password).isEqualTo("hash")
    }

    @Test
    fun serialVersionUID_isUnchangedFromPreChangeClass() {
        assertThat(ObjectStreamClass.lookup(AdminUserDetails::class.java).serialVersionUID)
            .isEqualTo(PRE_CHANGE_SERIAL_VERSION_UID)
    }

    @Test
    fun deserializesSessionPayloadWrittenByPreChangeClass() {
        val bytes = Base64.getDecoder().decode(PRE_CHANGE_SERIALIZED)
        val d = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as AdminUserDetails }
        assertThat(d.id).isEqualTo(UUID.fromString("11111111-2222-3333-4444-555555555555"))
        assertThat(d.username).isEqualTo("legacy@example.com")
        assertThat(d.password).isEqualTo("\$2a\$12\$legacyhash")
        assertThat(d.role).isEqualTo("RP_ADMIN")
        assertThat(d.isRpAdmin()).isTrue()
        assertThat(d.allowedTenantIds)
            .containsExactly(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
        assertThat(d.isEnabled).isTrue()
        // lockedUntil=2026-01-01T00:00Z 는 이미 지났으므로 잠금 아님.
        assertThat(d.isAccountNonLocked).isTrue()
        assertThat(d.authorities.map(GrantedAuthority::getAuthority))
            .containsExactly("ROLE_RP_ADMIN")
    }

    // ---- S16 개선: 로그인 후 해시 소거 ----------------------------------------

    @Test
    fun eraseCredentials_clearsOnlyPasswordHash() {
        val id = UUID.randomUUID()
        val t1 = UUID.randomUUID()
        val me = AdminUserDetails(
            id, "rp@x.com", "\$2a\$12\$secrethashvalue",
            "RP_ADMIN", setOf(t1), true, null, Clock.systemUTC(),
        )

        assertThat(me).isInstanceOf(CredentialsContainer::class.java)
        (me as CredentialsContainer).eraseCredentials()

        assertThat(me.password).isNull()
        assertThat(me.id).isEqualTo(id)
        assertThat(me.username).isEqualTo("rp@x.com")
        assertThat(me.role).isEqualTo("RP_ADMIN")
        assertThat(me.allowedTenantIds).containsExactly(t1)
        assertThat(me.authorities.map(GrantedAuthority::getAuthority))
            .containsExactly("ROLE_RP_ADMIN")
        assertThat(me.isEnabled).isTrue()
        assertThat(me.isAccountNonLocked).isTrue()
        assertThat(me.isAccountNonExpired).isTrue()
        assertThat(me.isCredentialsNonExpired).isTrue()
    }

    @Test
    fun erasedDetails_serializeWithoutPasswordHash() {
        val me = AdminUserDetails(
            UUID.randomUUID(), "rp@x.com", "\$2a\$12\$secrethashvalue",
            "RP_ADMIN", setOf(UUID.randomUUID()), true, null, Clock.systemUTC(),
        )
        assertThat(me).isInstanceOf(CredentialsContainer::class.java)
        (me as CredentialsContainer).eraseCredentials()

        val serialized = String(jdkSerialize(me), StandardCharsets.ISO_8859_1)
        assertThat(serialized).doesNotContain("\$2a\$12\$secrethashvalue")
    }

    @Test
    fun constructor_rejectsNullPasswordHash() {
        // admin_user.bcrypt_hash 는 NULL 가능 — 원 Java 의 requireNonNull 처럼 생성 시점에 NPE 여야 한다
        // (그래야 인증 실패 사유가 bad-password 가 아니라 other 로 남고 실패 횟수/잠금이 늘지 않는다).
        val ctor = AdminUserDetails::class.java.declaredConstructors.single { it.parameterCount == 8 }
        val ex = org.junit.jupiter.api.Assertions.assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            ctor.newInstance(
                UUID.randomUUID(), "rp@x.com", null, "RP_ADMIN",
                setOf<UUID>(), true, null, Clock.systemUTC(),
            )
        }
        assertThat(ex.cause).isInstanceOf(NullPointerException::class.java)
    }
}
