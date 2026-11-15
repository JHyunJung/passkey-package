package com.crosscert.passkey.app.fido2.authentication

import com.crosscert.passkey.app.support.PasskeyAppItSupport
import com.crosscert.passkey.app.support.PasskeyAppItSupport.TenantSeeder
import com.crosscert.passkey.core.jwt.IdTokenIssuer
import com.crosscert.passkey.core.jwt.KeyEnvelope
import com.crosscert.passkey.core.jwt.SigningKeyFactory
import com.crosscert.passkey.core.jwt.SigningKeyProvider
import com.crosscert.passkey.core.repository.SigningKeyRepository
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.OracleContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.OffsetDateTime
import java.util.UUID

/**
 * admin-app 에서 서명 키를 회전한 뒤 passkey-app 이 [SigningKeyRefreshJob] 한 번으로 새 ACTIVE 키에
 * 서명하고, 옛(ROTATED) 키와 새 키가 함께 JWKS 에 실리는지 실제 Oracle 로 확인한다.
 *
 * 회전은 admin-app `KeyRotationService.rotate` 와 같은 순서로 흉내 낸다: 기존 ACTIVE 행에
 * [com.crosscert.passkey.core.entity.SigningKey.rotate] 를 적용해 ROTATED 로 바꾸고, 그다음
 * [SigningKeyFactory.newRsaSigningKey] 로 만든 행을 ACTIVE 로 넣는다(ACTIVE 유니크 인덱스).
 * 런타임 DataSource(PSK_APP_RUNTIME_USER)는 signing_key 에 SELECT 만 있어 쓰기는 PSK_APP_ADMIN_USER
 * 풀로 한다. 스케줄된 tick 이 끼어들지 않도록 initial-delay 를 길게 잡는다.
 *
 * 컨테이너는 이 클래스 전용이고, 바꾼 키 상태가 캐시된 컨텍스트로 새지 않도록 클래스가 끝나면 컨텍스트를 버린다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["passkey.signing-key.refresh.initial-delay=PT1H"],
)
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SigningKeyRefreshIT @Autowired constructor(
    @LocalServerPort private val port: Int,
    private val keys: SigningKeyProvider,
    private val job: SigningKeyRefreshJob,
    private val idTokens: IdTokenIssuer,
    private val repo: SigningKeyRepository,
    private val envelope: KeyEnvelope,
) {

    companion object {
        @Container
        @JvmField
        val ORACLE: OracleContainer = PasskeyAppItSupport.oracleContainer()

        @Container
        @JvmField
        val REDIS: GenericContainer<*> = PasskeyAppItSupport.redisContainer()

        @JvmStatic
        @DynamicPropertySource
        fun registerProps(reg: DynamicPropertyRegistry) {
            PasskeyAppItSupport.bootstrapAndRegister(reg, ORACLE, REDIS)
        }

        private var seeder: TenantSeeder? = null

        @JvmStatic
        @AfterAll
        fun closeAdminPool() {
            seeder?.close()
            seeder = null
        }
    }

    @Test
    fun rotationIsPickedUpOnlyAfterRefreshJobRuns() {
        val adminJdbc = (seeder ?: TenantSeeder(ORACLE, "signing-key-refresh-it-admin-pool").also { seeder = it }).jdbc
        val oldKid = keys.signingKey().keyID

        // 회전: 옛 ACTIVE → ROTATED 를 먼저, 새 ACTIVE INSERT 를 나중에.
        val old = repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE").orElseThrow()
        assertThat(old.kid).isEqualTo(oldKid)
        old.rotate(OffsetDateTime.now())
        adminJdbc.update(
            "UPDATE PSK_APP_OWNER.signing_key SET status = ?, rotated_at = ? WHERE kid = ?",
            old.status, old.rotatedAt, old.kid,
        )
        val fresh = SigningKeyFactory.newRsaSigningKey(envelope)
        adminJdbc.update(
            "INSERT INTO PSK_APP_OWNER.signing_key (id, kid, alg, status, public_jwk, private_pkcs8) " +
                "VALUES (SYS_GUID(), ?, ?, ?, ?, ?)",
            fresh.kid, fresh.alg, fresh.status, fresh.publicJwk, fresh.privatePkcs8,
        )
        val newKid = fresh.kid
        assertThat(newKid).isNotEqualTo(oldKid)

        // job 전: 아직 캐시된 옛 키로 서명한다.
        assertThat(keys.signingKey().keyID).isEqualTo(oldKid)
        assertThat(SignedJWT.parse(issueToken()).header.keyID).isEqualTo(oldKid)

        job.runOnce()

        // job 후: 새 키로 서명하고, 그 서명이 JWKS 의 새 kid 공개키로 검증된다.
        assertThat(keys.signingKey().keyID).isEqualTo(newKid)
        val token = SignedJWT.parse(issueToken())
        assertThat(token.header.keyID).isEqualTo(newKid)

        val jwks = JWKSet.parse(
            RestTemplate().getForObject("http://localhost:$port/.well-known/jwks.json", String::class.java),
        )
        assertThat(jwks.keys.map { it.keyID }).contains(newKid, oldKid)
        val published = jwks.getKeyByKeyId(newKid) as RSAKey
        assertThat(token.verify(RSASSAVerifier(published))).isTrue()

        assertThat(keys.publicJwkSet().keys.map { it.keyID }).contains(newKid, oldKid)
    }

    private fun issueToken(): String =
        idTokens.issue("signing-key-refresh-it", byteArrayOf(1, 2, 3, 4), UUID.randomUUID(), null)
}
