package com.crosscert.passkey.admin.operator

import com.crosscert.passkey.admin.audit.AuditAppendRequest
import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.AdminSignupRequest
import com.crosscert.passkey.core.entity.AdminUser
import com.crosscert.passkey.core.entity.AdminUserTenant
import com.crosscert.passkey.core.mail.MailSender
import com.crosscert.passkey.core.repository.AdminSignupRequestRepository
import com.crosscert.passkey.core.repository.AdminUserRepository
import com.crosscert.passkey.core.repository.AdminUserTenantRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.core.env.Environment
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Arrays
import java.util.Optional
import java.util.UUID

class SignupRequestServiceTest {

    private val requests: AdminSignupRequestRepository = mock()
    private val users: AdminUserRepository = mock()
    private val mappings: AdminUserTenantRepository = mock()
    private val audit: AuditLogService = mock()
    private val mail: MailSender = mock()
    private val encoder: PasswordEncoder = mock()
    private val env: Environment = mock()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-03T01:00:00Z"), KstTime.ZONE)

    private val service = SignupRequestService(
        requests, users, mappings, audit, mail, encoder, clock, env,
    )

    private companion object {
        val ACTOR_ID: UUID = UUID.randomUUID()
        const val ACTOR = "alice@crosscert.com"
    }

    private fun pending(id: UUID, email: String): AdminSignupRequest {
        val r = mock<AdminSignupRequest>()
        whenever(r.id).thenReturn(id)
        whenever(r.email).thenReturn(email)
        whenever(r.bcryptHash).thenReturn("\$2a\$12\$stored")
        whenever(r.reason).thenReturn("reason")
        whenever(r.requestedAt).thenReturn(OffsetDateTime.now(clock))
        return r
    }

    /** userRepo.save() 가 돌려줄 "저장된" 계정 — id 는 persist 시 채워지므로 mock. */
    private fun savedUser(id: UUID, email: String, role: String): AdminUser {
        val u = mock<AdminUser>()
        whenever(u.id).thenReturn(id)
        whenever(u.email).thenReturn(email)
        whenever(u.role).thenReturn(role)
        whenever(u.status).thenReturn("ACTIVE")
        whenever(u.isMfaEnabled).thenReturn(false)
        return u
    }

    // ── request ────────────────────────────────────────────────────────────

    @Test
    fun request_newEmail_savesHashedRequest() {
        whenever(users.findByEmail("new@x.com")).thenReturn(Optional.empty())
        whenever(requests.existsByEmail("new@x.com")).thenReturn(false)
        whenever(requests.count()).thenReturn(0L)
        whenever(encoder.encode("password-12chars")).thenReturn("\$2a\$12\$hashed")

        service.request(SignupRequestDto.Create("New@X.com ", "password-12chars", "RP 담당"))

        val cap = argumentCaptor<AdminSignupRequest>()
        verify(requests).save(cap.capture())
        assertThat(cap.lastValue.email).`as`("소문자·trim 정규화").isEqualTo("new@x.com")
        assertThat(cap.lastValue.bcryptHash).isEqualTo("\$2a\$12\$hashed")
        assertThat(cap.lastValue.reason).isEqualTo("RP 담당")
        assertThat(cap.lastValue.requestedAt).isEqualTo(OffsetDateTime.now(clock))
    }

    @Test
    fun request_passwordOver72Bytes_rejectedBeforeBcryptWithKoreanMessage() {
        // REST(/admin/api/signup-requests)는 @Size(글자 수)만 거치므로 다바이트 문자는 서비스가 최종 방어선이다.
        // 놓치면 BCrypt 가 "password cannot be more than 72 bytes"(영어 원문)를 400 으로 그대로 내보낸다.
        assertThatThrownBy { service.request(SignupRequestDto.Create("new@x.com", "가".repeat(25), null)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("한글 24자")
        verify(encoder, never()).encode(anyString())
        verify(requests, never()).save(any<AdminSignupRequest>())
    }

    @Test
    fun request_emailAlreadyAnAdminUser_silentlySkips() {
        whenever(users.findByEmail("alice@crosscert.com")).thenReturn(Optional.of(AdminUser.create()))

        assertThatCode {
            service.request(
                SignupRequestDto.Create("alice@crosscert.com", "password-12chars", null),
            )
        }
            .doesNotThrowAnyException()

        verify(requests, never()).save(anyOrNull<AdminSignupRequest>())
    }

    /** 타이밍 이퀄라이제이션 — 스킵 경로도 bcrypt 를 실제로 돌려야 응답시간으로 이메일 존재를 추정할 수 없다. */
    @Test
    fun request_skipPathsStillRunBcrypt() {
        whenever(users.findByEmail("alice@crosscert.com")).thenReturn(Optional.of(AdminUser.create()))

        service.request(SignupRequestDto.Create("alice@crosscert.com", "password-12chars", null))

        verify(encoder).encode("password-12chars")
        verify(requests, never()).save(anyOrNull<AdminSignupRequest>())
    }

    @Test
    fun request_emailAlreadyPending_silentlySkips() {
        whenever(users.findByEmail("dup@x.com")).thenReturn(Optional.empty())
        whenever(requests.existsByEmail("dup@x.com")).thenReturn(true)

        service.request(SignupRequestDto.Create("dup@x.com", "password-12chars", null))

        verify(requests, never()).save(anyOrNull<AdminSignupRequest>())
    }

    @Test
    fun request_pendingCapReached_silentlySkips() {
        whenever(users.findByEmail("cap@x.com")).thenReturn(Optional.empty())
        whenever(requests.existsByEmail("cap@x.com")).thenReturn(false)
        whenever(requests.count()).thenReturn(SignupRequestService.MAX_PENDING.toLong())

        service.request(SignupRequestDto.Create("cap@x.com", "password-12chars", null))

        verify(requests, never()).save(anyOrNull<AdminSignupRequest>())
        verify(encoder).encode("password-12chars")
    }

    @Test
    fun request_uniqueViolationOnConcurrentInsert_isSwallowed() {
        whenever(users.findByEmail("race@x.com")).thenReturn(Optional.empty())
        whenever(requests.existsByEmail("race@x.com")).thenReturn(false)
        whenever(requests.count()).thenReturn(0L)
        whenever(encoder.encode(anyString())).thenReturn("h")
        whenever(requests.save(anyOrNull<AdminSignupRequest>())).thenThrow(DataIntegrityViolationException("uq_admin_signup_request_email"))

        assertThatCode {
            service.request(
                SignupRequestDto.Create("race@x.com", "password-12chars", null),
            )
        }
            .doesNotThrowAnyException()
    }

    // ── approve ────────────────────────────────────────────────────────────

    @Test
    fun approve_rpAdminWithTenant_createsActiveUserMappingAuditAndMail() {
        val reqId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val newUserId = UUID.randomUUID()
        val pendingReq = pending(reqId, "new@x.com")
        val savedUser = savedUser(newUserId, "new@x.com", "RP_ADMIN")
        whenever(requests.findById(reqId)).thenReturn(Optional.of(pendingReq))
        whenever(users.findByEmail("new@x.com")).thenReturn(Optional.empty())
        whenever(requests.deleteIfPresent(reqId)).thenReturn(1)
        whenever(users.saveAndFlush(anyOrNull<AdminUser>())).thenReturn(savedUser)

        val view = service.approve(
            reqId,
            SignupRequestDto.Approve("RP_ADMIN", listOf(tenantId, tenantId)), ACTOR_ID, ACTOR,
        )

        val userCap = argumentCaptor<AdminUser>()
        verify(users).saveAndFlush(userCap.capture())
        val created = userCap.lastValue
        assertThat(created.email).isEqualTo("new@x.com")
        assertThat(created.bcryptHash).`as`("요청의 해시를 그대로 복사").isEqualTo("\$2a\$12\$stored")
        assertThat(created.role).isEqualTo("RP_ADMIN")
        assertThat(created.status).isEqualTo("ACTIVE")
        assertThat(created.isEnabled).isTrue()
        assertThat(created.createdBy).isEqualTo(ACTOR)

        val mapCap = argumentCaptor<AdminUserTenant>()
        verify(mappings).save(mapCap.capture()) // 중복 tenantId 는 1건으로 접힘
        assertThat(mapCap.lastValue.adminUserId).isEqualTo(newUserId)
        assertThat(mapCap.lastValue.tenantId).isEqualTo(tenantId)

        val auditCap = argumentCaptor<AuditAppendRequest>()
        verify(audit).append(auditCap.capture())
        assertThat(auditCap.lastValue.action).isEqualTo("ADMIN_SIGNUP_APPROVE")
        assertThat(auditCap.lastValue.targetType).isEqualTo("ADMIN_USER")
        assertThat(auditCap.lastValue.targetId).isEqualTo(newUserId.toString())
        assertThat(auditCap.lastValue.payload).containsEntry("role", "RP_ADMIN").containsEntry("tenantCount", 1)

        verify(mail).send(eq("new@x.com"), anyString(), anyString())
        assertThat(view.tenantIds).containsExactly(tenantId)

        // 경합 판정(deleteIfPresent) 은 반드시 계정 INSERT 보다 먼저 실행돼야
        // 한다 — clearAutomatically 가 영속성 컨텍스트를 비우는 지점이 이 순서에
        // 의존한다(SignupRequestService.approve 4번 주석 참고).
        val inOrder = inOrder(requests, users)
        inOrder.verify(requests).deleteIfPresent(reqId)
        inOrder.verify(users).saveAndFlush(anyOrNull<AdminUser>())
    }

    @Test
    fun approve_rpAdminWithoutTenant_rejected400() {
        val reqId = UUID.randomUUID()
        assertThatThrownBy {
            service.approve(
                reqId,
                SignupRequestDto.Approve("RP_ADMIN", listOf()), ACTOR_ID, ACTOR,
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("RP_ADMIN requires at least one tenant")
        verify(requests, never()).deleteIfPresent(anyOrNull())
    }

    @Test
    fun approve_platformOperatorWithTenant_rejected400() {
        val reqId = UUID.randomUUID()
        assertThatThrownBy {
            service.approve(
                reqId,
                SignupRequestDto.Approve("PLATFORM_OPERATOR", listOf(UUID.randomUUID())), ACTOR_ID, ACTOR,
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("PLATFORM_OPERATOR must not have tenant")
    }

    @Test
    fun approve_unknownRole_rejected400() {
        assertThatThrownBy {
            service.approve(
                UUID.randomUUID(),
                SignupRequestDto.Approve("ROOT", null), ACTOR_ID, ACTOR,
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Invalid role")
    }

    @Test
    fun approve_missingRequest_404() {
        val reqId = UUID.randomUUID()
        whenever(requests.findById(reqId)).thenReturn(Optional.empty())

        assertThatThrownBy {
            service.approve(
                reqId,
                SignupRequestDto.Approve("PLATFORM_OPERATOR", listOf()), ACTOR_ID, ACTOR,
            )
        }
            .isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
                assertThat(e.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            }
    }

    @Test
    fun approve_emailNowExistsInAdminUser_409() {
        val reqId = UUID.randomUUID()
        val pendingReq = pending(reqId, "dup@x.com")
        whenever(requests.findById(reqId)).thenReturn(Optional.of(pendingReq))
        whenever(users.findByEmail("dup@x.com")).thenReturn(Optional.of(AdminUser.create()))

        assertThatThrownBy {
            service.approve(
                reqId,
                SignupRequestDto.Approve("PLATFORM_OPERATOR", listOf()), ACTOR_ID, ACTOR,
            )
        }
            .isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
                assertThat(e.statusCode).isEqualTo(HttpStatus.CONFLICT)
            }
        verify(users, never()).saveAndFlush(anyOrNull<AdminUser>())
    }

    @Test
    fun approve_alreadyHandledByConcurrentAdmin_409_andNoUserCreated() {
        val reqId = UUID.randomUUID()
        val pendingReq = pending(reqId, "race@x.com")
        whenever(requests.findById(reqId)).thenReturn(Optional.of(pendingReq))
        whenever(users.findByEmail("race@x.com")).thenReturn(Optional.empty())
        whenever(requests.deleteIfPresent(reqId)).thenReturn(0)

        assertThatThrownBy {
            service.approve(
                reqId,
                SignupRequestDto.Approve("PLATFORM_OPERATOR", listOf()), ACTOR_ID, ACTOR,
            )
        }
            .isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
                assertThat(e.statusCode).isEqualTo(HttpStatus.CONFLICT)
            }
        verify(users, never()).saveAndFlush(anyOrNull<AdminUser>())
        verify(audit, never()).append(anyOrNull())
    }

    @Test
    fun approve_uniqueViolationOnFlush_409() {
        val reqId = UUID.randomUUID()
        val pendingReq = pending(reqId, "flush@x.com")
        whenever(requests.findById(reqId)).thenReturn(Optional.of(pendingReq))
        whenever(users.findByEmail("flush@x.com")).thenReturn(Optional.empty())
        whenever(requests.deleteIfPresent(reqId)).thenReturn(1)
        whenever(users.saveAndFlush(anyOrNull<AdminUser>()))
            .thenThrow(DataIntegrityViolationException("uq_admin_user_email"))

        assertThatThrownBy {
            service.approve(
                reqId,
                SignupRequestDto.Approve("PLATFORM_OPERATOR", listOf()), ACTOR_ID, ACTOR,
            )
        }
            .isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
                assertThat(e.statusCode).isEqualTo(HttpStatus.CONFLICT)
            }
        verify(audit, never()).append(anyOrNull())
    }

    @Test
    fun approve_nullTenantId_rejected400() {
        val reqId = UUID.randomUUID()
        // 원 Java Arrays.asList((UUID) null) — Kotlin List<UUID> 에 null 원소를 넣으려면 unchecked cast 가 필요하다.
        @Suppress("UNCHECKED_CAST")
        val withNull = Arrays.asList(null as UUID?) as List<UUID>
        assertThatThrownBy {
            service.approve(
                reqId,
                SignupRequestDto.Approve("RP_ADMIN", withNull), ACTOR_ID, ACTOR,
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must not contain null")
        verify(requests, never()).deleteIfPresent(anyOrNull())
    }

    @Test
    fun approve_mailFailure_doesNotFailApproval() {
        val reqId = UUID.randomUUID()
        val pendingReq = pending(reqId, "m@x.com")
        val savedUser = savedUser(UUID.randomUUID(), "m@x.com", "PLATFORM_OPERATOR")
        whenever(requests.findById(reqId)).thenReturn(Optional.of(pendingReq))
        whenever(users.findByEmail("m@x.com")).thenReturn(Optional.empty())
        whenever(requests.deleteIfPresent(reqId)).thenReturn(1)
        whenever(users.saveAndFlush(anyOrNull<AdminUser>())).thenReturn(savedUser)
        doThrow(RuntimeException("smtp down")).whenever(mail).send(anyString(), anyString(), anyString())

        assertThatCode {
            service.approve(
                reqId,
                SignupRequestDto.Approve("PLATFORM_OPERATOR", listOf()), ACTOR_ID, ACTOR,
            )
        }
            .doesNotThrowAnyException()
        verify(audit).append(anyOrNull())
    }

    // ── reject ─────────────────────────────────────────────────────────────

    @Test
    fun reject_deletesAuditsAndMails() {
        val reqId = UUID.randomUUID()
        val pendingReq = pending(reqId, "no@x.com")
        whenever(requests.findById(reqId)).thenReturn(Optional.of(pendingReq))
        whenever(requests.deleteIfPresent(reqId)).thenReturn(1)

        service.reject(reqId, ACTOR_ID, ACTOR)

        val cap = argumentCaptor<AuditAppendRequest>()
        verify(audit).append(cap.capture())
        assertThat(cap.lastValue.action).isEqualTo("ADMIN_SIGNUP_REJECT")
        assertThat(cap.lastValue.targetType).isEqualTo("ADMIN_SIGNUP_REQUEST")
        assertThat(cap.lastValue.targetId).isEqualTo(reqId.toString())
        verify(mail).send(eq("no@x.com"), anyString(), anyString())
    }

    @Test
    fun reject_alreadyHandled_409() {
        val reqId = UUID.randomUUID()
        val pendingReq = pending(reqId, "no@x.com")
        whenever(requests.findById(reqId)).thenReturn(Optional.of(pendingReq))
        whenever(requests.deleteIfPresent(reqId)).thenReturn(0)

        assertThatThrownBy { service.reject(reqId, ACTOR_ID, ACTOR) }
            .isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
                assertThat(e.statusCode).isEqualTo(HttpStatus.CONFLICT)
            }
        verify(audit, never()).append(anyOrNull())
    }

    @Test
    fun reject_missingRequest_404() {
        val reqId = UUID.randomUUID()
        whenever(requests.findById(reqId)).thenReturn(Optional.empty())
        assertThatThrownBy { service.reject(reqId, ACTOR_ID, ACTOR) }
            .isInstanceOfSatisfying(ResponseStatusException::class.java) { e ->
                assertThat(e.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            }
    }

    // ── list ───────────────────────────────────────────────────────────────

    @Test
    fun list_mapsToViewWithoutHash() {
        val id = UUID.randomUUID()
        val pendingReq = pending(id, "l@x.com")
        whenever(requests.findAllByOrderByRequestedAtAsc()).thenReturn(listOf(pendingReq))

        val views = service.list()

        assertThat(views).hasSize(1)
        assertThat(views[0].id).isEqualTo(id)
        assertThat(views[0].email).isEqualTo("l@x.com")
        assertThat(views[0].reason).isEqualTo("reason")
    }

    // ── request: PLATFORM_OPERATOR 자동 승인(QA 전용 설정) ─────────────────

    private fun autoApprove(on: Boolean) {
        ReflectionTestUtils.setField(service, "autoApprovePlatformOperator", on)
    }

    @Test
    fun request_platformOperator_autoApproveOn_createsActiveAccountWithoutPendingRow() {
        autoApprove(true)
        val newId = UUID.randomUUID()
        whenever(users.findByEmail("op@x.com")).thenReturn(Optional.empty())
        whenever(encoder.encode("password-12chars")).thenReturn("\$2a\$12\$hashed")
        val saved = savedUser(newId, "op@x.com", "PLATFORM_OPERATOR")
        whenever(users.saveAndFlush(any<AdminUser>())).thenReturn(saved)

        val activated = service.request(
            SignupRequestDto.Create("Op@X.com", "password-12chars", null, "PLATFORM_OPERATOR"),
        )

        assertThat(activated).isTrue()
        val cap = argumentCaptor<AdminUser>()
        verify(users).saveAndFlush(cap.capture())
        val created = cap.lastValue
        assertThat(created.email).isEqualTo("op@x.com")
        assertThat(created.bcryptHash).isEqualTo("\$2a\$12\$hashed")
        assertThat(created.role).isEqualTo("PLATFORM_OPERATOR")
        assertThat(created.status).isEqualTo("ACTIVE")
        assertThat(created.isEnabled).isTrue()
        verify(requests, never()).save(anyOrNull<AdminSignupRequest>())
        val auditCap = argumentCaptor<AuditAppendRequest>()
        verify(audit).append(auditCap.capture())
        assertThat(auditCap.lastValue.action).isEqualTo("ADMIN_SIGNUP_SELF_APPROVE")
    }

    @Test
    fun request_platformOperator_autoApproveOff_staysPending() {
        autoApprove(false)
        whenever(users.findByEmail("op@x.com")).thenReturn(Optional.empty())
        whenever(requests.existsByEmail("op@x.com")).thenReturn(false)
        whenever(requests.count()).thenReturn(0L)
        whenever(encoder.encode("password-12chars")).thenReturn("\$2a\$12\$hashed")

        val activated = service.request(
            SignupRequestDto.Create("op@x.com", "password-12chars", null, "PLATFORM_OPERATOR"),
        )

        assertThat(activated).isFalse()
        verify(requests).save(any<AdminSignupRequest>())
        verify(users, never()).saveAndFlush(anyOrNull<AdminUser>())
    }

    @Test
    fun request_rpAdmin_autoApproveOn_staysPending() {
        autoApprove(true)
        whenever(users.findByEmail("rp@x.com")).thenReturn(Optional.empty())
        whenever(requests.existsByEmail("rp@x.com")).thenReturn(false)
        whenever(requests.count()).thenReturn(0L)
        whenever(encoder.encode("password-12chars")).thenReturn("\$2a\$12\$hashed")

        val activated = service.request(
            SignupRequestDto.Create("rp@x.com", "password-12chars", null, "RP_ADMIN"),
        )

        assertThat(activated).isFalse()
        verify(requests).save(any<AdminSignupRequest>())
        verify(users, never()).saveAndFlush(anyOrNull<AdminUser>())
    }

    @Test
    fun request_platformOperator_autoApproveOn_existingAccount_skipsSilently() {
        autoApprove(true)
        whenever(users.findByEmail("op@x.com")).thenReturn(Optional.of(mock<AdminUser>()))
        whenever(encoder.encode("password-12chars")).thenReturn("\$2a\$12\$hashed")

        val activated = service.request(
            SignupRequestDto.Create("op@x.com", "password-12chars", null, "PLATFORM_OPERATOR"),
        )

        assertThat(activated).isFalse()
        verify(users, never()).saveAndFlush(anyOrNull<AdminUser>())
        verify(requests, never()).save(anyOrNull<AdminSignupRequest>())
    }

    @Test
    fun request_platformOperator_autoApproveOn_concurrentDuplicate_skipsSilently() {
        autoApprove(true)
        whenever(users.findByEmail("op@x.com")).thenReturn(Optional.empty())
        whenever(encoder.encode("password-12chars")).thenReturn("\$2a\$12\$hashed")
        whenever(users.saveAndFlush(any<AdminUser>())).thenThrow(DataIntegrityViolationException("dup"))

        val activated = service.request(
            SignupRequestDto.Create("op@x.com", "password-12chars", null, "PLATFORM_OPERATOR"),
        )

        assertThat(activated).isFalse()
        verify(audit, never()).append(anyOrNull())
    }
}
