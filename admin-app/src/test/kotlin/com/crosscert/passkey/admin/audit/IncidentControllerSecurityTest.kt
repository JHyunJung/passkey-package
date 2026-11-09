package com.crosscert.passkey.admin.audit

import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.core.api.GlobalExceptionHandler
import com.crosscert.passkey.core.entity.AdminUser
import com.crosscert.passkey.core.entity.SecurityIncident
import com.crosscert.passkey.core.repository.AdminUserRepository
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/**
 * AuditChainMonitorController 의 incident 엔드포인트 권한 게이팅 + 409/422 예외 매핑 검증.
 *
 * 슬라이스 부트 구성(@WebMvcTest + JpaStubs + MockBean 세트)은 같은 패키지의
 * AuditLogControllerSecurityTest / TenantAdminControllerSecurityTest 와 동일하게 복제한다
 * (AdminSecurityConfig 새 빈 누락 시 컨텍스트 로드 실패 회귀를 피하기 위해).
 */
@AdminWebSliceTest(controllers = [AuditChainMonitorController::class])
@Import(GlobalExceptionHandler::class)
class IncidentControllerSecurityTest {

    @Autowired
    lateinit var mvc: MockMvc

    // Controller collaborators
    @MockitoBean
    lateinit var verifier: AuditChainVerifier

    @MockitoBean
    lateinit var backfillService: AuditChainBackfillService

    @MockitoBean
    lateinit var incidents: SecurityIncidentService

    // Security config + JPA wiring stubs (mirrors AuditLogControllerSecurityTest)
    @Autowired
    lateinit var audit: AuditLogService

    @Autowired
    lateinit var admins: AdminUserRepository

    /** OPEN incident with a deterministic id for toView() stubbing. */
    private fun openIncident(): SecurityIncident =
        SecurityIncident.open(TENANT, ENTRY, "{}", ACTOR, OffsetDateTime.now(ZoneOffset.ofHours(9)))

    // ---- 권한 게이팅 -------------------------------------------------------

    @Test
    fun anonymousCreateIsUnauthorized() {
        mvc.perform(
            post("/admin/api/audit/chain/incidents")
                .with(csrf())
                .contentType("application/json")
                .content(CREATE_BODY),
        )
            .andExpect(status().isUnauthorized())
    }

    @Test
    @WithMockUser(roles = ["RP_ADMIN"])
    fun rpAdminCannotCreate() {
        mvc.perform(
            post("/admin/api/audit/chain/incidents")
                .with(csrf())
                .contentType("application/json")
                .content(CREATE_BODY),
        )
            .andExpect(status().isForbidden())
    }

    @Test
    @WithMockUser(roles = ["RP_ADMIN"])
    fun rpAdminCannotList() {
        mvc.perform(get("/admin/api/audit/chain/incidents"))
            .andExpect(status().isForbidden())
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun operatorCanCreate() {
        whenever(admins.findByEmail(anyString())).thenReturn(Optional.of(adminUserWithUuid()))
        // create 는 3-arg(tenantId, actorId, actorEmail) — tamperedEntryId 는 서버가 도출.
        whenever(incidents.create(anyOrNull(), anyOrNull(), anyString())).thenReturn(openIncident())

        mvc.perform(
            post("/admin/api/audit/chain/incidents")
                .with(csrf())
                .contentType("application/json")
                .content(CREATE_BODY),
        )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.tenantId").value(TENANT.toString()))
            .andExpect(jsonPath("$.tamperedEntryId").value(ENTRY.toString()))
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andExpect(jsonPath("$.severity").value("CRITICAL"))
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun operatorCanList() {
        whenever(incidents.list()).thenReturn(listOf(openIncident()))

        mvc.perform(get("/admin/api/audit/chain/incidents"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$[0].status").value("OPEN"))
    }

    // ---- 예외 매핑 (409 / 422 / 404 / 400) --------------------------------

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun conflictMapsTo409() {
        whenever(admins.findByEmail(anyString())).thenReturn(Optional.of(adminUserWithUuid()))
        whenever(incidents.create(anyOrNull(), anyOrNull(), anyString()))
            .thenThrow(IncidentConflictException("open incident already exists"))

        mvc.perform(
            post("/admin/api/audit/chain/incidents")
                .with(csrf())
                .contentType("application/json")
                .content(CREATE_BODY),
        )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("conflict"))
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun notTamperedMapsTo422() {
        whenever(admins.findByEmail(anyString())).thenReturn(Optional.of(adminUserWithUuid()))
        whenever(incidents.create(anyOrNull(), anyOrNull(), anyString()))
            .thenThrow(IncidentNotTamperedException("tenant chain is intact"))

        mvc.perform(
            post("/admin/api/audit/chain/incidents")
                .with(csrf())
                .contentType("application/json")
                .content(CREATE_BODY),
        )
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error").value("not_tampered"))
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun resolveNotFoundMapsTo404() {
        val id = UUID.randomUUID()
        whenever(admins.findByEmail(anyString())).thenReturn(Optional.of(adminUserWithUuid()))
        whenever(incidents.resolve(anyOrNull(), anyString(), anyOrNull(), anyString()))
            .thenThrow(IncidentNotFoundException("no incident with id: $id"))

        mvc.perform(
            post("/admin/api/audit/chain/incidents/$id/resolve")
                .with(csrf())
                .contentType("application/json")
                .content("{\"note\":\"x\"}"),
        )
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not_found"))
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun invalidTenantIdMapsTo400() {
        // UUID.fromString("not-a-uuid") → IllegalArgumentException → GlobalExceptionHandler 400.
        whenever(admins.findByEmail(anyString())).thenReturn(Optional.of(adminUserWithUuid()))

        mvc.perform(
            post("/admin/api/audit/chain/incidents")
                .with(csrf())
                .contentType("application/json")
                .content("{\"tenantId\":\"not-a-uuid\"}"),
        )
            .andExpect(status().isBadRequest())
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun blankResolveNoteMapsTo400() {
        // @NotBlank note → MethodArgumentNotValidException → GlobalExceptionHandler 400.
        val id = UUID.randomUUID()
        whenever(admins.findByEmail(anyString())).thenReturn(Optional.of(adminUserWithUuid()))

        mvc.perform(
            post("/admin/api/audit/chain/incidents/$id/resolve")
                .with(csrf())
                .contentType("application/json")
                .content("{\"note\":\"\"}"),
        )
            .andExpect(status().isBadRequest())
    }

    // ---- resolve happy path -----------------------------------------------

    @Test
    @WithMockUser(username = "alice@example.com", roles = ["PLATFORM_OPERATOR"])
    fun operatorCanResolve() {
        val incident = openIncident()
        incident.resolve(ACTOR, "DBA 복구 완료", OffsetDateTime.now(ZoneOffset.ofHours(9)))
        whenever(admins.findByEmail(anyString())).thenReturn(Optional.of(adminUserWithUuid()))
        whenever(incidents.resolve(anyOrNull(), anyString(), anyOrNull(), anyString())).thenReturn(incident)

        mvc.perform(
            post("/admin/api/audit/chain/incidents/" + incident.id + "/resolve")
                .with(csrf())
                .contentType("application/json")
                .content("{\"note\":\"DBA 복구 완료\"}"),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("RESOLVED"))
            .andExpect(jsonPath("$.resolutionNote").value("DBA 복구 완료"))
    }

    companion object {
        private val TENANT: UUID = UUID.randomUUID()
        private val ENTRY: UUID = UUID.randomUUID()
        private val ACTOR: UUID = UUID.randomUUID()

        // tamperedEntryId 는 요청에 없다 — 서버가 도출한다. body 는 tenantId 만.
        private val CREATE_BODY = "{\"tenantId\":\"$TENANT\"}"

        private fun adminUserWithUuid(): AdminUser {
            val u = AdminUser("alice@example.com", "x", "ADMIN")
            try {
                val f = AdminUser::class.java.superclass.getDeclaredField("id")
                f.isAccessible = true
                f.set(u, ACTOR)
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
            return u
        }
    }
}
