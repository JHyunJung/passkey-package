package com.crosscert.passkey.admin.contract

import com.crosscert.passkey.admin.activity.ActivityController
import com.crosscert.passkey.admin.activity.ActivityService
import com.crosscert.passkey.admin.activity.ActivityView
import com.crosscert.passkey.admin.apikey.ApiKeyAdminController
import com.crosscert.passkey.admin.apikey.ApiKeyAdminService
import com.crosscert.passkey.admin.audit.AuditChainBackfillService
import com.crosscert.passkey.admin.audit.AuditChainMonitorController
import com.crosscert.passkey.admin.audit.AuditChainVerifier
import com.crosscert.passkey.admin.audit.AuditLogController
import com.crosscert.passkey.admin.audit.IncidentConflictException
import com.crosscert.passkey.admin.audit.IncidentNotFoundException
import com.crosscert.passkey.admin.audit.IncidentNotTamperedException
import com.crosscert.passkey.admin.audit.MonthlyReportController
import com.crosscert.passkey.admin.audit.MonthlyReportService
import com.crosscert.passkey.admin.audit.SecurityIncidentService
import com.crosscert.passkey.admin.auth.ActiveTenantController
import com.crosscert.passkey.admin.auth.ActiveTenantResolver
import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.auth.AdminUserDetailsService
import com.crosscert.passkey.admin.auth.MfaController
import com.crosscert.passkey.admin.auth.MfaPendingFilter
import com.crosscert.passkey.admin.auth.MfaSecretCipher
import com.crosscert.passkey.admin.auth.RecoveryCodeService
import com.crosscert.passkey.admin.auth.TenantBoundary
import com.crosscert.passkey.admin.auth.TotpService
import com.crosscert.passkey.admin.credential.CredentialAdminController
import com.crosscert.passkey.admin.credential.CredentialAdminDto
import com.crosscert.passkey.admin.credential.CredentialAdminService
import com.crosscert.passkey.admin.funnel.AdoptionController
import com.crosscert.passkey.admin.funnel.AdoptionService
import com.crosscert.passkey.admin.funnel.FunnelController
import com.crosscert.passkey.admin.funnel.FunnelService
import com.crosscert.passkey.admin.insights.PlatformInsightsController
import com.crosscert.passkey.admin.insights.PlatformInsightsService
import com.crosscert.passkey.admin.keymgmt.KeyMgmtController
import com.crosscert.passkey.admin.keymgmt.KeyRotationService
import com.crosscert.passkey.admin.license.LicenseController
import com.crosscert.passkey.admin.mds.MdsAdminController
import com.crosscert.passkey.admin.mds.MdsHistoryService
import com.crosscert.passkey.admin.mds.MdsSchedulerService
import com.crosscert.passkey.admin.operator.AdminUserController
import com.crosscert.passkey.admin.operator.AdminUserService
import com.crosscert.passkey.admin.operator.PasswordResetController
import com.crosscert.passkey.admin.operator.PasswordResetService
import com.crosscert.passkey.admin.operator.SignupRequestController
import com.crosscert.passkey.admin.operator.SignupRequestService
import com.crosscert.passkey.admin.policy.AaguidPolicyController
import com.crosscert.passkey.admin.policy.AaguidPolicyService
import com.crosscert.passkey.admin.policy.SecurityPolicyController
import com.crosscert.passkey.admin.policy.SecurityPolicyService
import com.crosscert.passkey.admin.system.SystemInfoController
import com.crosscert.passkey.admin.system.SystemInfoService
import com.crosscert.passkey.admin.tenant.TenantAdminController
import com.crosscert.passkey.admin.tenant.TenantAdminService
import com.crosscert.passkey.admin.tenant.TenantLifecycleService
import com.crosscert.passkey.admin.tenant.WebauthnDiffService
import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.admin.web.tenant.FunnelTabController
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.api.PageView
import com.crosscert.passkey.core.config.CoreJacksonConfig
import com.crosscert.passkey.core.config.DbSchemaProperties
import com.crosscert.passkey.core.entity.AdminUser
import com.crosscert.passkey.core.entity.AuditLog
import com.crosscert.passkey.core.entity.SigningKey
import com.crosscert.passkey.core.entity.Tenant
import com.crosscert.passkey.core.license.LicenseProperties
import com.crosscert.passkey.core.license.LicenseState
import com.crosscert.passkey.core.license.LicenseStateMachine
import com.crosscert.passkey.core.license.LicenseToken
import com.crosscert.passkey.core.repository.AdminUserRepository
import com.crosscert.passkey.core.repository.AuditLogRepository
import com.crosscert.passkey.core.repository.SigningKeyRepository
import com.crosscert.passkey.core.repository.TenantRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.data.domain.PageImpl
import org.springframework.data.redis.core.Cursor
import org.springframework.data.redis.core.ScanOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.Optional
import java.util.OptionalLong
import java.util.TreeMap
import java.util.UUID

/**
 * admin 의 모든 `@RestController`/`@ResponseBody` 엔드포인트 JSON 형태 스냅샷 (Kotlin 전환 안전망, 동결).
 *
 * 대상(전환 시점 grep `@RestController|@ResponseBody` 전수 24파일): TenantAdmin, CredentialAdmin, Profile, License,
 * PlatformInsights, Me, ActiveTenant, Mfa, Activity, AuditLog, MonthlyReport(JSON 오류 — 성공은 PDF 로
 * [ExportCharacterizationTest]), KeyMgmt, SystemInfo, Adoption, ApiKeyAdmin, AuditChainMonitor(+@ExceptionHandler 3종),
 * MdsAdmin, Funnel, SignupRequest, AdminUser, PasswordReset, AaguidPolicy, SecurityPolicy, 그리고 화면 오류 봉투
 * `WebExceptionAdvice`(htmx 오류의 `HX-Trigger` JSON). 보안 체인의 JSON 응답(로그인 성공·실패, 401 진입점,
 * 403 거부 처리기)도 함께 고정한다.
 *
 * 각 GET 의 성공 응답 1개, 쓰기 엔드포인트는 빈 본문 `{}` 응답 1개(검증이 없으면 그 성공 응답), 대표 오류
 * (검증 실패 400·404·403·401·405·형식 오류)를 슬라이스(MockMvc)로 부른다. 서비스는 [Fill] 로 모든 필드가 채워진
 * 결정적 값을 돌려주므로 응답의 키 집합 전체가 드러난다. 스냅샷은 [AdminJsonShape](키 이름·직렬화 순서·타입 토큰·
 * 상태 코드·Content-Type) — `contract/json/` 아래 `.shape.json`.
 *
 * ObjectMapper 는 운영과 같은 경로(Boot Jackson 자동구성 + [CoreJacksonConfig] + application.yml)다.
 * jackson-module-kotlin 이 등록되면 같은 자동구성으로 이 슬라이스에도 올라오므로 그 영향이 여기서 판정된다.
 *
 * 기록: `./gradlew :admin-app:test --tests '*AdminJsonShapeContractTest' -Pcontract.record=true`
 */
@AdminWebSliceTest(
    controllers = [
        TenantAdminController::class, CredentialAdminController::class, LicenseController::class,
        PlatformInsightsController::class, ActiveTenantController::class, MfaController::class,
        ActivityController::class, AuditLogController::class, MonthlyReportController::class,
        KeyMgmtController::class, SystemInfoController::class, AdoptionController::class,
        ApiKeyAdminController::class, AuditChainMonitorController::class, MdsAdminController::class,
        FunnelController::class, SignupRequestController::class, AdminUserController::class,
        PasswordResetController::class, AaguidPolicyController::class, SecurityPolicyController::class,
        // WebExceptionAdvice(admin.web 패키지 한정) 봉투를 내는 화면 컨트롤러 — 서비스 예외를 그대로 던진다.
        FunnelTabController::class,
        // MeController·ProfileController 는 AdminWebSliceTest 가 이미 @Import 한다.
    ],
)
@Import(CoreJacksonConfig::class, AdminJsonShapeContractTest.FilledMocks::class)
class AdminJsonShapeContractTest {

    /** 서비스 기본 응답을 [Fill] 로 채우는 mock — 테스트마다 초기화(기본 Answer 는 유지)한다. */
    @TestConfiguration
    class FilledMocks {
        @Bean fun tenantAdminService(): TenantAdminService = filled(TenantAdminService::class.java)
        @Bean fun webauthnDiffService(): WebauthnDiffService = filled(WebauthnDiffService::class.java)
        @Bean fun tenantLifecycleService(): TenantLifecycleService = filled(TenantLifecycleService::class.java)
        @Bean fun credentialAdminService(): CredentialAdminService = filled(CredentialAdminService::class.java)
        @Bean fun platformInsightsService(): PlatformInsightsService = filled(PlatformInsightsService::class.java)
        @Bean fun activeTenantResolver(): ActiveTenantResolver = filled(ActiveTenantResolver::class.java)
        @Bean fun totpService(): TotpService = filled(TotpService::class.java)
        @Bean fun recoveryCodeService(): RecoveryCodeService = filled(RecoveryCodeService::class.java)
        @Bean fun mfaSecretCipher(): MfaSecretCipher = filled(MfaSecretCipher::class.java)
        @Bean fun activityService(): ActivityService = filled(ActivityService::class.java)
        @Bean fun auditChainVerifier(): AuditChainVerifier = filled(AuditChainVerifier::class.java)
        @Bean fun monthlyReportService(): MonthlyReportService = filled(MonthlyReportService::class.java)
        @Bean fun keyRotationService(): KeyRotationService = filled(KeyRotationService::class.java)
        @Bean fun systemInfoService(): SystemInfoService = filled(SystemInfoService::class.java)
        @Bean fun adoptionService(): AdoptionService = filled(AdoptionService::class.java)
        @Bean fun funnelService(): FunnelService = filled(FunnelService::class.java)
        @Bean fun apiKeyAdminService(): ApiKeyAdminService = filled(ApiKeyAdminService::class.java)
        @Bean fun auditChainBackfillService(): AuditChainBackfillService = filled(AuditChainBackfillService::class.java)
        @Bean fun securityIncidentService(): SecurityIncidentService = filled(SecurityIncidentService::class.java)
        @Bean fun mdsSchedulerService(): MdsSchedulerService = filled(MdsSchedulerService::class.java)
        @Bean fun mdsHistoryService(): MdsHistoryService = filled(MdsHistoryService::class.java)
        @Bean fun signupRequestService(): SignupRequestService = filled(SignupRequestService::class.java)
        @Bean fun adminUserService(): AdminUserService = filled(AdminUserService::class.java)
        @Bean fun passwordResetService(): PasswordResetService = filled(PasswordResetService::class.java)
        @Bean fun aaguidPolicyService(): AaguidPolicyService = filled(AaguidPolicyService::class.java)

        companion object {
            val ALL: MutableList<Any> = ArrayList()

            private fun <T : Any> filled(type: Class<T>): T {
                val m: T = Mockito.mock(type, Fill.ANSWER)
                ALL.add(m)
                return m
            }
        }
    }

    companion object {
        val OP_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val OTHER_TENANT: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
        val TENANT_PATH = "/admin/api/tenants/" + Fill.ID

        // Java 텍스트 블록(공통 들여쓰기 제거, 마지막 줄바꿈 없음)과 같은 문자열.
        private const val TENANT_CREATE_BODY =
            "{\"slug\":\"acme\",\"displayName\":\"Acme\",\"rpId\":\"acme.example\",\"rpName\":\"Acme\",\n" +
                " \"allowedOrigins\":[\"https://acme.example\"],\"acceptedFormats\":[\"none\"],\n" +
                " \"requireUserVerification\":true,\"mdsRequired\":false,\"attestationConveyance\":\"NONE\",\"webauthnTimeoutMs\":60000}"

        // ── principals ─────────────────────────────────────────

        fun operator(): Authentication = auth("PLATFORM_OPERATOR", emptySet())

        fun rpAdmin(): Authentication = auth("RP_ADMIN", setOf(Fill.ID))

        private fun auth(role: String, tenants: Set<UUID>): Authentication {
            val p = AdminUserDetails(OP_ID, "op@example.com", "x", role, tenants, true, null, Clock.systemUTC())
            return UsernamePasswordAuthenticationToken(p, "x", p.authorities)
        }

        // ── 요청·스냅샷 도우미 ──────────────────────────────────

        private fun asOp(b: MockHttpServletRequestBuilder): MockHttpServletRequestBuilder =
            b.with(authentication(operator())).with(csrf())

        private fun json(b: MockHttpServletRequestBuilder, body: String): MockHttpServletRequestBuilder =
            b.contentType(MediaType.APPLICATION_JSON).content(body)
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var env: Environment

    // AdminWebSliceTest 묶음의 mock
    @Autowired lateinit var clock: Clock
    @Autowired lateinit var admins: AdminUserRepository
    @Autowired lateinit var tenantRepo: TenantRepository
    @Autowired lateinit var auditRepo: AuditLogRepository
    @Autowired lateinit var signingKeys: SigningKeyRepository
    @Autowired lateinit var tenantBoundary: TenantBoundary
    @Autowired lateinit var securityPolicy: SecurityPolicyService
    @Autowired lateinit var uds: AdminUserDetailsService
    @Autowired lateinit var encoder: PasswordEncoder

    // Fill mock
    @Autowired lateinit var tenantService: TenantAdminService
    @Autowired lateinit var recoveryCodes: RecoveryCodeService
    @Autowired lateinit var totp: TotpService
    @Autowired lateinit var incidents: SecurityIncidentService
    @Autowired lateinit var passwordReset: PasswordResetService
    @Autowired lateinit var credentialService: CredentialAdminService
    @Autowired lateinit var activityService: ActivityService

    // 이 슬라이스에서만 필요한 인프라 mock
    @MockitoBean lateinit var licenseStateMachine: LicenseStateMachine
    @MockitoBean lateinit var licenseProperties: LicenseProperties
    @MockitoBean lateinit var jdbc: JdbcTemplate
    @MockitoBean lateinit var redis: StringRedisTemplate
    @MockitoBean lateinit var dbSchema: DbSchemaProperties

    @BeforeEach
    fun stubInfrastructure() {
        whenever(clock.instant()).thenReturn(Fill.INSTANT)
        whenever(clock.millis()).thenReturn(Fill.INSTANT.toEpochMilli())
        whenever(clock.zone).thenReturn(ZoneOffset.UTC)
        whenever(tenantBoundary.currentTenantScope()).thenReturn(null)
        whenever(admins.findByEmail(anyString())).thenAnswer { inv ->
            Optional.of(AdminUser(inv.getArgument<String>(0), "x", "PLATFORM_OPERATOR"))
        }
        whenever(securityPolicy.get()).thenAnswer(Fill.ANSWER)
        whenever(securityPolicy.update(anyOrNull(), anyString())).thenAnswer(Fill.ANSWER)

        val tenant: Tenant = Mockito.mock(Tenant::class.java, Fill.ANSWER)
        whenever(tenantRepo.findAll()).thenReturn(listOf(tenant))
        whenever(tenantRepo.findAllById(anyOrNull())).thenReturn(listOf(tenant))
        val row: AuditLog = Mockito.mock(AuditLog::class.java, Fill.ANSWER)
        whenever(auditRepo.findAllByTenantOrdered(anyOrNull())).thenReturn(listOf(row))
        // overview 는 tenant id 목록을 리포지토리에서 직접 읽고(P3) 행은 verifier.verifyTenantRows 로 넘긴다.
        whenever(auditRepo.findDistinctTenantIds()).thenReturn(listOf(Fill.ID))
        whenever(auditRepo.search(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(PageImpl(listOf(row)))
        whenever(signingKeys.findAll()).thenReturn(listOf(Mockito.mock(SigningKey::class.java, Fill.ANSWER)))
        whenever(admins.findAllById(anyOrNull())).thenReturn(listOf(Mockito.mock(AdminUser::class.java, Fill.ANSWER)))

        val token = Fill.of(LicenseToken::class.java)
        whenever(licenseStateMachine.snapshot()).thenReturn(
            LicenseStateMachine.Snapshot(LicenseState.NETWORK_GRACE, token, Fill.INSTANT, "x"),
        )
        whenever(licenseProperties.heartbeatInterval()).thenReturn(Duration.ofHours(1))

        val rs = mock<ResultSet>()
        whenever(rs.getLong("version")).thenReturn(7L)
        whenever(rs.getDate("nextUpdate")).thenReturn(java.sql.Date.valueOf(Fill.DATE))
        whenever(rs.getTimestamp("fetchedAt")).thenReturn(Timestamp.from(Fill.INSTANT))
        whenever(jdbc.queryForObject(anyString(), any<RowMapper<Any>>()))
            .thenAnswer { inv -> inv.getArgument<RowMapper<*>>(1).mapRow(rs, 0) }
        val cursor = mock<Cursor<String>>()
        whenever(cursor.hasNext()).thenReturn(true, false)
        whenever(cursor.next()).thenReturn("mds:aaguid:1")
        whenever(redis.scan(any<ScanOptions>())).thenReturn(cursor)
    }

    @AfterEach
    fun resetFilledMocks() {
        FilledMocks.ALL.forEach { Mockito.reset(it) }
    }

    /** 레코드 응답(필드 순서 결정적) — 키 순서까지 고정. */
    private fun shape(name: String, req: MockHttpServletRequestBuilder) {
        snapshot(name, req, true)
    }

    /** `Map.of(...)` 응답(순회 순서가 JVM 실행마다 다름) — 키 집합·타입만 고정. */
    private fun unorderedShape(name: String, req: MockHttpServletRequestBuilder) {
        snapshot(name, req, false)
    }

    private fun snapshot(name: String, req: MockHttpServletRequestBuilder, ordered: Boolean) {
        val r = mvc.perform(req).andReturn()
        val res = r.response
        val meta = TreeMap<String, String>()
        meta["@status"] = res.status.toString()
        meta["@contentType"] = res.contentType.toString()
        val body = res.getContentAsString(StandardCharsets.UTF_8)
        val shape: String
        if (body.isEmpty()) {
            meta["@body"] = "empty"
            shape = AdminJsonShape.of("{}", false, meta)
        } else {
            shape = AdminJsonShape.of(body, ordered, meta)
        }
        ContractSnapshot.assertMatches("json/$name.shape.json", shape)
    }

    // ── 보안 체인 JSON(로그인·401·403) ──────────────────────

    @Test
    fun securityChainResponses() {
        val principal = operator().principal as AdminUserDetails
        whenever(uds.loadUserByUsername("op@example.com")).thenReturn(principal)
        whenever(encoder.matches(anyString(), anyString())).thenAnswer { inv -> "right" == inv.getArgument<Any?>(0) }

        unorderedShape(
            "security-login-success",
            post("/admin/login").with(csrf().asHeader())
                .param("email", "op@example.com").param("password", "right"),
        )
        shape(
            "security-login-failure",
            post("/admin/login").with(csrf().asHeader())
                .param("email", "op@example.com").param("password", "wrong"),
        )
        shape("security-unauthenticated-401", get("/admin/api/tenants"))
        shape(
            "security-csrf-missing-403",
            post("/admin/api/tenants").with(authentication(operator()))
                .contentType(MediaType.APPLICATION_JSON).content(TENANT_CREATE_BODY),
        )
    }

    // ── core GlobalExceptionHandler 오류 봉투 ───────────────

    @Test
    fun errorEnvelopes() {
        whenever(tenantService.get("missing")).thenThrow(BusinessException(ErrorCode.TENANT_NOT_FOUND))
        shape("error-business-404", asOp(get("/admin/api/tenants/missing")))
        shape("error-validation-400", asOp(json(post("/admin/api/tenants"), "{}")))
        shape("error-malformed-json-400", asOp(json(post("/admin/api/tenants"), "{")))
        shape("error-type-mismatch-400", asOp(get("/admin/api/tenants/not-a-uuid/credentials")))
        shape("error-missing-param-400", asOp(get("/admin/api/audit/chain/monthly-report")))
        shape("error-method-not-allowed-405", asOp(delete("/admin/api/tenants")))
        shape("error-illegal-argument-400", asOp(get("/admin/api/audit/chain/overview").param("windowHours", "0")))
        // @PreAuthorize(PLATFORM_OPERATOR) 를 RP_ADMIN 이 호출 — 메서드 보안 AccessDenied 봉투.
        shape(
            "error-role-forbidden-403",
            json(post("/admin/api/tenants"), TENANT_CREATE_BODY)
                .with(authentication(rpAdmin())).with(csrf()),
        )
        // RP_ADMIN 이 다른 tenant 의 감사 로그를 지정 — BusinessException(ACCESS_DENIED).
        whenever(tenantBoundary.currentTenantScope()).thenReturn(Fill.ID)
        shape(
            "error-business-403",
            get("/admin/api/audit").param("tenantId", OTHER_TENANT.toString())
                .with(authentication(rpAdmin())),
        )
    }

    // ── WebExceptionAdvice(화면 오류 봉투: htmx HX-Trigger JSON) ─

    @Test
    fun webExceptionAdviceHxTrigger() {
        whenever(tenantService.get("missing")).thenThrow(BusinessException(ErrorCode.TENANT_NOT_FOUND))
        hxTrigger(
            "web-error-htmx-toast",
            get("/admin/tenants/missing/funnel.csv")
                .header("HX-Request", "true").with(authentication(operator())),
        )
        hxTrigger(
            "web-error-htmx-confirm",
            get("/admin/tenants/missing/funnel.csv")
                .header("HX-Request", "true").header("X-Confirm", "1").with(authentication(operator())),
        )
    }

    private fun hxTrigger(name: String, req: MockHttpServletRequestBuilder) {
        val res = mvc.perform(req).andReturn().response
        val trigger: String? = res.getHeader("HX-Trigger")
        assertThat(trigger).`as`("$name HX-Trigger").isNotNull()
        val meta = TreeMap<String, String>()
        meta["@status"] = res.status.toString()
        meta["@body"] = if (res.getContentAsString(StandardCharsets.UTF_8).isEmpty()) "empty" else "present"
        // 위 isNotNull 단언이 null 을 이미 실패시킨다 — 원 Java 도 null 이면 그 단언에서 멈춘다.
        ContractSnapshot.assertMatches("json/$name.shape.json", AdminJsonShape.of(trigger!!, false, meta))
    }

    // ── 엔드포인트별 ────────────────────────────────────────

    @Test
    fun tenantAdmin() {
        shape("tenant-list", asOp(get("/admin/api/tenants")))
        shape("tenant-get", asOp(get("/admin/api/tenants/acme")))
        shape("tenant-create", asOp(json(post("/admin/api/tenants"), TENANT_CREATE_BODY)))
        shape("tenant-update-empty", asOp(json(put("/admin/api/tenants/acme"), "{}")))
        shape("tenant-diff-empty", asOp(json(post("/admin/api/tenants/acme/webauthn-config/diff"), "{}")))
        shape("tenant-suspend", asOp(post("/admin/api/tenants/acme/suspend")))
        shape("tenant-activate", asOp(post("/admin/api/tenants/acme/activate")))
    }

    @Test
    fun credentialAdmin() {
        shape("credential-list", asOp(get("$TENANT_PATH/credentials")))
        shape("credential-auth-events", asOp(get("$TENANT_PATH/credentials/AAAAAAAAAAAAAAAAAAAAAA/auth-events")))
        shape("credential-revoke", asOp(delete("$TENANT_PATH/credentials/AAAAAAAAAAAAAAAAAAAAAA")))
    }

    @Test
    fun profileMeLicense() {
        shape("profile-get", get("/admin/api/profile"))
        shape("me-get", asOp(get("/admin/api/me")))
        val pending = MockHttpSession()
        pending.setAttribute(MfaPendingFilter.MFA_PENDING_ATTR, java.lang.Boolean.TRUE)
        shape("me-get-mfa-pending", get("/admin/api/me").session(pending).with(authentication(operator())))
        shape("me-get-rp-admin", get("/admin/api/me").with(authentication(rpAdmin())))
        shape("license-get-onprem", get("/admin/api/license"))

        // SaaS 모드(LicenseStateMachine 빈 없음)는 컨트롤러를 직접 만들어 같은 ObjectMapper 로 직렬화한다.
        val saas = LicenseController(
            "saas",
            StaticListableBeanFactory().getBeanProvider(LicenseStateMachine::class.java), licenseProperties, clock,
        )
        val meta = TreeMap<String, String>()
        meta["@source"] = "LicenseController.get() direct, app ObjectMapper"
        val body = mapper.writeValueAsString(saas.get())
        ContractSnapshot.assertMatches("json/license-get-saas.shape.json", AdminJsonShape.of(body, true, meta))
    }

    @Test
    fun insightsFunnelAdoption() {
        shape("platform-insights-get", asOp(get("/admin/api/platform/insights")))
        shape("funnel-get", asOp(get("$TENANT_PATH/funnel")))
        shape("adoption-get", asOp(get("$TENANT_PATH/adoption")))
    }

    @Test
    fun activeTenant() {
        shape("active-tenant-get", get("/admin/api/active-tenant").with(authentication(rpAdmin())))
        shape(
            "active-tenant-switch-empty",
            json(post("/admin/api/active-tenant"), "{}")
                .with(authentication(rpAdmin())).with(csrf()),
        )
    }

    @Test
    fun mfa() {
        // {} — 코드 없음 → 401 invalid_code (복구 코드도 불일치).
        whenever(recoveryCodes.consume(anyOrNull(), isNull())).thenReturn(false)
        shape("mfa-verify-empty", asOp(json(post("/admin/api/mfa/verify"), "{}")))
        shape("mfa-confirm-empty", asOp(json(post("/admin/api/mfa/confirm"), "{}")))
        shape("mfa-disable-empty", asOp(json(post("/admin/api/mfa/disable"), "{}")))
        unorderedShape("mfa-enroll", asOp(post("/admin/api/mfa/enroll")))

        // 복구 코드로 통과(TOTP 불일치) — verified·usedRecoveryCode·remaining.
        whenever(totp.matchedCounter(anyString(), anyString(), anyLong())).thenReturn(OptionalLong.empty())
        whenever(recoveryCodes.consume(anyOrNull(), eq("rc-1"))).thenReturn(true)
        unorderedShape("mfa-verify-recovery", asOp(json(post("/admin/api/mfa/verify"), "{\"code\":\"rc-1\"}")))

        // TOTP 일치 — 비밀이 등록된 사용자.
        whenever(admins.findByEmail(anyString())).thenAnswer { inv ->
            val u = AdminUser(inv.getArgument<String>(0), "x", "PLATFORM_OPERATOR")
            u.setMfaSecret("sealed")
            Optional.of(u)
        }
        whenever(totp.matchedCounter(anyString(), eq("123456"), anyLong())).thenReturn(OptionalLong.of(42L))
        shape("mfa-verify-totp", asOp(json(post("/admin/api/mfa/verify"), "{\"code\":\"123456\"}")))
        unorderedShape("mfa-confirm", asOp(json(post("/admin/api/mfa/confirm"), "{\"code\":\"123456\"}")))
        shape("mfa-disable", asOp(json(post("/admin/api/mfa/disable"), "{\"code\":\"123456\"}")))

        // MFA 대기 세션의 enroll — MfaPendingFilter/컨트롤러 이중 차단 응답.
        val pending = MockHttpSession()
        pending.setAttribute(MfaPendingFilter.MFA_PENDING_ATTR, java.lang.Boolean.TRUE)
        shape(
            "mfa-enroll-pending",
            post("/admin/api/mfa/enroll").session(pending)
                .with(authentication(operator())).with(csrf()),
        )
    }

    @Test
    fun activityAndAudit() {
        shape("activity-get", asOp(get("/admin/api/activity")))
        shape("activity-detail", asOp(get("/admin/api/activity/" + Fill.ID)))
        shape("audit-list", asOp(get("/admin/api/audit")))
        shape("audit-verify", asOp(get("/admin/api/audit/verify")))
    }

    @Test
    fun auditChainMonitor() {
        shape("audit-chain-overview", asOp(get("/admin/api/audit/chain/overview")))
        shape("audit-chain-verify", asOp(get("/admin/api/audit/chain/verify").param("tenantId", Fill.ID.toString())))
        shape("audit-chain-backfill", asOp(post("/admin/api/audit/chain/backfill")))
        shape("audit-chain-incidents", asOp(get("/admin/api/audit/chain/incidents")))
        shape("audit-chain-incident-create-empty", asOp(json(post("/admin/api/audit/chain/incidents"), "{}")))
        shape(
            "audit-chain-incident-resolve-empty",
            asOp(json(post("/admin/api/audit/chain/incidents/" + Fill.ID + "/resolve"), "{}")),
        )
        val create = "{\"tenantId\":\"" + Fill.ID + "\"}"
        shape("audit-chain-incident-create", asOp(json(post("/admin/api/audit/chain/incidents"), create)))
        shape(
            "audit-chain-incident-resolve",
            asOp(json(post("/admin/api/audit/chain/incidents/" + Fill.ID + "/resolve"), "{\"note\":\"done\"}")),
        )

        // 컨트롤러 @ExceptionHandler 3종(Map.of 두 키 — 순서 비결정).
        whenever(incidents.create(anyOrNull(), anyOrNull(), anyString()))
            .thenThrow(IncidentConflictException("open incident exists"))
        unorderedShape("audit-chain-incident-conflict-409", asOp(json(post("/admin/api/audit/chain/incidents"), create)))
        Mockito.reset(incidents)
        whenever(incidents.create(anyOrNull(), anyOrNull(), anyString()))
            .thenThrow(IncidentNotTamperedException("chain intact"))
        unorderedShape(
            "audit-chain-incident-not-tampered-422",
            asOp(json(post("/admin/api/audit/chain/incidents"), create)),
        )
        whenever(incidents.resolve(anyOrNull(), anyString(), anyOrNull(), anyString()))
            .thenThrow(IncidentNotFoundException("no incident"))
        unorderedShape(
            "audit-chain-incident-not-found-404",
            asOp(json(post("/admin/api/audit/chain/incidents/" + Fill.ID + "/resolve"), "{\"note\":\"done\"}")),
        )
    }

    @Test
    fun keysSystemMds() {
        shape("keys-list", asOp(get("/admin/api/keys")))
        shape("keys-rotate", asOp(post("/admin/api/keys/rotate")))
        shape("system-info-get", asOp(get("/admin/api/system/info")))
        shape("mds-status", asOp(get("/admin/api/mds/status")))
        shape("mds-sync", asOp(post("/admin/api/mds/sync")))
        shape("mds-history", asOp(get("/admin/api/mds/history")))
    }

    @Test
    fun apiKeys() {
        shape("api-key-list", asOp(get("/admin/api/api-keys")))
        shape("api-key-issue-empty", asOp(json(post("/admin/api/api-keys"), "{}")))
        shape(
            "api-key-issue",
            asOp(
                json(
                    post("/admin/api/api-keys"),
                    "{\"tenantId\":\"" + Fill.ID + "\",\"name\":\"k\",\"scopes\":[\"passkey:read\"],\"expiresInMonths\":12}",
                ),
            ),
        )
        shape("api-key-rotate", asOp(post("/admin/api/api-keys/" + Fill.ID + "/rotate")))
        shape("api-key-revoke", asOp(delete("/admin/api/api-keys/" + Fill.ID)))
    }

    @Test
    fun operators() {
        shape("signup-request-empty", json(post("/admin/api/signup-requests"), "{}"))
        unorderedShape(
            "signup-request",
            json(
                post("/admin/api/signup-requests"),
                "{\"email\":\"new@example.com\",\"password\":\"correct-horse-battery\",\"reason\":\"r\"}",
            ),
        )
        shape("signup-request-list", asOp(get("/admin/api/signup-requests")))
        shape(
            "signup-request-approve-empty",
            asOp(json(post("/admin/api/signup-requests/" + Fill.ID + "/approve"), "{}")),
        )
        shape(
            "signup-request-approve",
            asOp(
                json(
                    post("/admin/api/signup-requests/" + Fill.ID + "/approve"),
                    "{\"role\":\"RP_ADMIN\",\"tenantIds\":[\"" + Fill.ID + "\"]}",
                ),
            ),
        )
        shape("signup-request-reject", asOp(post("/admin/api/signup-requests/" + Fill.ID + "/reject")))

        shape("admin-user-list", asOp(get("/admin/api/admin-users")))
        shape("admin-user-suspend", asOp(post("/admin/api/admin-users/" + Fill.ID + "/suspend")))
        shape("admin-user-activate", asOp(post("/admin/api/admin-users/" + Fill.ID + "/activate")))
        shape("admin-user-add-tenant-empty", asOp(json(post("/admin/api/admin-users/" + Fill.ID + "/tenants"), "{}")))
        shape(
            "admin-user-remove-tenant",
            asOp(delete("/admin/api/admin-users/" + Fill.ID + "/tenants/" + OTHER_TENANT)),
        )

        shape("password-reset-request-empty", json(post("/admin/api/password-reset/request"), "{}"))
        shape("password-reset-confirm-empty", json(post("/admin/api/password-reset/confirm"), "{}"))
        doThrow(IllegalArgumentException("invalid or expired token")).whenever(passwordReset).confirm("bad", "pw")
        shape(
            "password-reset-confirm-invalid",
            json(post("/admin/api/password-reset/confirm"), "{\"token\":\"bad\",\"newPassword\":\"pw\"}"),
        )
    }

    @Test
    fun policies() {
        shape("aaguid-policy-get", asOp(get("$TENANT_PATH/aaguid-policy")))
        shape("aaguid-policy-update-empty", asOp(json(put("$TENANT_PATH/aaguid-policy"), "{}")))
        shape("security-policy-get", asOp(get("/admin/api/security-policy")))
        shape("security-policy-update-empty", asOp(json(put("/admin/api/security-policy"), "{}")))
        shape(
            "security-policy-update",
            asOp(
                json(
                    put("/admin/api/security-policy"),
                    "{\"sessionIdleTimeoutMinutes\":30,\"mfaRequired\":true,\"corsAllowlist\":[\"https://a.example\"]}",
                ),
            ),
        )
    }

    /**
     * null 이 든 응답 — Fill 은 모든 필드를 채우므로 이 경우를 따로 고정한다. 전역 `non_null` 이라 null 필드는 키가
     * **빠진다**(값 null 로 나오지 않는다). Kotlin 전환에서 nullable 처리·포함 규칙이 바뀌면 여기서 드러난다.
     */
    @Test
    fun nullBearingResponses() {
        // 감사 로그: 시스템 행(actorId·actorEmail null) + 대상 없음(targetType·targetId null) + payload null.
        val systemRow: AuditLog = Mockito.mock(AuditLog::class.java, Fill.ANSWER)
        whenever(systemRow.actorId).thenReturn(null)
        whenever(systemRow.actorEmail).thenReturn(null)
        whenever(systemRow.targetType).thenReturn(null)
        whenever(systemRow.targetId).thenReturn(null)
        whenever(systemRow.payload).thenReturn(null)
        whenever(auditRepo.search(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(PageImpl(listOf(systemRow)))
        shape("audit-list-nulls", asOp(get("/admin/api/audit")))

        // credential: 선택 시각(lastUsedAt)·별칭·AAGUID·인증기 이름 null.
        whenever(credentialService.list(anyOrNull(), anyInt(), anyInt(), anyOrNull()))
            .thenReturn(
                PageView(
                    listOf(
                        CredentialAdminDto.CredentialView(
                            "AAAAAAAAAAAAAAAAAAAAAA", "dXNlci0x", null, null, null, "none", null, 0, null, Fill.ODT,
                        ),
                    ),
                    0, 50, 1, false,
                ),
            )
        shape("credential-list-nulls", asOp(get("$TENANT_PATH/credentials")))

        // activity: kpi.p95Ms(Long, 문서상 nullable) null, 이벤트의 tenant·actor null.
        whenever(activityService.snapshot(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(
            ActivityView(
                ActivityView.Kpi(1, 0, 0, null),
                listOf(),
                listOf(ActivityView.Event(Fill.ID, "MDS_SYNC", null, null, null, null, null, Fill.ODT, "system")),
            ),
        )
        shape("activity-get-nulls", asOp(get("/admin/api/activity")))
    }

    @Test
    fun environmentHasNoActiveProfile() {
        // profile-get 스냅샷의 active 배열 형태가 실행 환경에 따라 흔들리지 않는다는 전제를 고정한다.
        assertThat(env.activeProfiles).isEmpty()
    }
}
