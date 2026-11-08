package com.crosscert.passkey.admin.contract;

import com.crosscert.passkey.admin.activity.ActivityController;
import com.crosscert.passkey.admin.activity.ActivityService;
import com.crosscert.passkey.admin.apikey.ApiKeyAdminController;
import com.crosscert.passkey.admin.apikey.ApiKeyAdminService;
import com.crosscert.passkey.admin.audit.AuditChainBackfillService;
import com.crosscert.passkey.admin.audit.AuditChainMonitorController;
import com.crosscert.passkey.admin.audit.AuditChainVerifier;
import com.crosscert.passkey.admin.audit.AuditLogController;
import com.crosscert.passkey.admin.audit.IncidentConflictException;
import com.crosscert.passkey.admin.audit.IncidentNotFoundException;
import com.crosscert.passkey.admin.audit.IncidentNotTamperedException;
import com.crosscert.passkey.admin.audit.MonthlyReportController;
import com.crosscert.passkey.admin.audit.MonthlyReportService;
import com.crosscert.passkey.admin.audit.SecurityIncidentService;
import com.crosscert.passkey.admin.auth.ActiveTenantController;
import com.crosscert.passkey.admin.auth.ActiveTenantResolver;
import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.auth.AdminUserDetailsService;
import com.crosscert.passkey.admin.auth.MfaController;
import com.crosscert.passkey.admin.auth.MfaPendingFilter;
import com.crosscert.passkey.admin.auth.MfaSecretCipher;
import com.crosscert.passkey.admin.auth.RecoveryCodeService;
import com.crosscert.passkey.admin.auth.TenantBoundary;
import com.crosscert.passkey.admin.auth.TotpService;
import com.crosscert.passkey.admin.credential.CredentialAdminController;
import com.crosscert.passkey.admin.credential.CredentialAdminService;
import com.crosscert.passkey.admin.funnel.AdoptionController;
import com.crosscert.passkey.admin.funnel.AdoptionService;
import com.crosscert.passkey.admin.funnel.FunnelController;
import com.crosscert.passkey.admin.funnel.FunnelService;
import com.crosscert.passkey.admin.insights.PlatformInsightsController;
import com.crosscert.passkey.admin.insights.PlatformInsightsService;
import com.crosscert.passkey.admin.keymgmt.KeyMgmtController;
import com.crosscert.passkey.admin.keymgmt.KeyRotationService;
import com.crosscert.passkey.admin.license.LicenseController;
import com.crosscert.passkey.admin.mds.MdsAdminController;
import com.crosscert.passkey.admin.mds.MdsHistoryService;
import com.crosscert.passkey.admin.mds.MdsSchedulerService;
import com.crosscert.passkey.admin.operator.AdminUserController;
import com.crosscert.passkey.admin.operator.AdminUserService;
import com.crosscert.passkey.admin.operator.PasswordResetController;
import com.crosscert.passkey.admin.operator.PasswordResetService;
import com.crosscert.passkey.admin.operator.SignupRequestController;
import com.crosscert.passkey.admin.operator.SignupRequestService;
import com.crosscert.passkey.admin.policy.AaguidPolicyController;
import com.crosscert.passkey.admin.policy.AaguidPolicyService;
import com.crosscert.passkey.admin.policy.SecurityPolicyController;
import com.crosscert.passkey.admin.policy.SecurityPolicyService;
import com.crosscert.passkey.admin.system.SystemInfoController;
import com.crosscert.passkey.admin.system.SystemInfoService;
import com.crosscert.passkey.admin.tenant.TenantAdminController;
import com.crosscert.passkey.admin.tenant.TenantAdminService;
import com.crosscert.passkey.admin.tenant.TenantLifecycleService;
import com.crosscert.passkey.admin.tenant.WebauthnDiffService;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import com.crosscert.passkey.admin.web.tenant.FunnelTabController;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.config.CoreJacksonConfig;
import com.crosscert.passkey.core.config.DbSchemaProperties;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.entity.AuditLog;
import com.crosscert.passkey.core.entity.SecurityIncident;
import com.crosscert.passkey.core.entity.SigningKey;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.license.LicenseProperties;
import com.crosscert.passkey.core.license.LicenseState;
import com.crosscert.passkey.core.license.LicenseStateMachine;
import com.crosscert.passkey.core.license.LicenseToken;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import com.crosscert.passkey.core.repository.SigningKeyRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * admin 의 모든 {@code @RestController}/{@code @ResponseBody} 엔드포인트 JSON 형태 스냅샷 (Kotlin 전환 안전망, 동결).
 *
 * <p>대상(전환 시점 grep {@code @RestController|@ResponseBody} 전수 24파일): TenantAdmin, CredentialAdmin, Profile, License,
 * PlatformInsights, Me, ActiveTenant, Mfa, Activity, AuditLog, MonthlyReport(JSON 오류 — 성공은 PDF 로
 * {@link ExportCharacterizationTest}), KeyMgmt, SystemInfo, Adoption, ApiKeyAdmin, AuditChainMonitor(+@ExceptionHandler 3종),
 * MdsAdmin, Funnel, SignupRequest, AdminUser, PasswordReset, AaguidPolicy, SecurityPolicy, 그리고 화면 오류 봉투
 * {@code WebExceptionAdvice}(htmx 오류의 {@code HX-Trigger} JSON). 보안 체인의 JSON 응답(로그인 성공·실패, 401 진입점,
 * 403 거부 처리기)도 함께 고정한다.
 *
 * <p>각 GET 의 성공 응답 1개, 쓰기 엔드포인트는 빈 본문 {@code {}} 응답 1개(검증이 없으면 그 성공 응답), 대표 오류
 * (검증 실패 400·404·403·401·405·형식 오류)를 슬라이스(MockMvc)로 부른다. 서비스는 {@link Fill} 로 모든 필드가 채워진
 * 결정적 값을 돌려주므로 응답의 키 집합 전체가 드러난다. 스냅샷은 {@link AdminJsonShape}(키 이름·직렬화 순서·타입 토큰·
 * 상태 코드·Content-Type) — {@code contract/json/*.shape.json}.
 *
 * <p>ObjectMapper 는 운영과 같은 경로(Boot Jackson 자동구성 + {@link CoreJacksonConfig} + application.yml)다.
 * jackson-module-kotlin 이 등록되면 같은 자동구성으로 이 슬라이스에도 올라오므로 그 영향이 여기서 판정된다.
 *
 * <p>기록: {@code ./gradlew :admin-app:test --tests '*AdminJsonShapeContractTest' -Pcontract.record=true}
 */
@AdminWebSliceTest(controllers = {
        TenantAdminController.class, CredentialAdminController.class, LicenseController.class,
        PlatformInsightsController.class, ActiveTenantController.class, MfaController.class,
        ActivityController.class, AuditLogController.class, MonthlyReportController.class,
        KeyMgmtController.class, SystemInfoController.class, AdoptionController.class,
        ApiKeyAdminController.class, AuditChainMonitorController.class, MdsAdminController.class,
        FunnelController.class, SignupRequestController.class, AdminUserController.class,
        PasswordResetController.class, AaguidPolicyController.class, SecurityPolicyController.class,
        // WebExceptionAdvice(admin.web 패키지 한정) 봉투를 내는 화면 컨트롤러 — 서비스 예외를 그대로 던진다.
        FunnelTabController.class
        // MeController·ProfileController 는 AdminWebSliceTest 가 이미 @Import 한다.
})
@Import({CoreJacksonConfig.class, AdminJsonShapeContractTest.FilledMocks.class})
class AdminJsonShapeContractTest {

    static final UUID OP_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    static final String TENANT_PATH = "/admin/api/tenants/" + Fill.ID;

    /** 서비스 기본 응답을 {@link Fill} 로 채우는 mock — 테스트마다 초기화(기본 Answer 는 유지)한다. */
    @TestConfiguration
    static class FilledMocks {
        static final List<Object> ALL = new ArrayList<>();

        private static <T> T filled(Class<T> type) {
            T m = mock(type, Fill.ANSWER);
            ALL.add(m);
            return m;
        }

        @Bean TenantAdminService tenantAdminService() { return filled(TenantAdminService.class); }
        @Bean WebauthnDiffService webauthnDiffService() { return filled(WebauthnDiffService.class); }
        @Bean TenantLifecycleService tenantLifecycleService() { return filled(TenantLifecycleService.class); }
        @Bean CredentialAdminService credentialAdminService() { return filled(CredentialAdminService.class); }
        @Bean PlatformInsightsService platformInsightsService() { return filled(PlatformInsightsService.class); }
        @Bean ActiveTenantResolver activeTenantResolver() { return filled(ActiveTenantResolver.class); }
        @Bean TotpService totpService() { return filled(TotpService.class); }
        @Bean RecoveryCodeService recoveryCodeService() { return filled(RecoveryCodeService.class); }
        @Bean MfaSecretCipher mfaSecretCipher() { return filled(MfaSecretCipher.class); }
        @Bean ActivityService activityService() { return filled(ActivityService.class); }
        @Bean AuditChainVerifier auditChainVerifier() { return filled(AuditChainVerifier.class); }
        @Bean MonthlyReportService monthlyReportService() { return filled(MonthlyReportService.class); }
        @Bean KeyRotationService keyRotationService() { return filled(KeyRotationService.class); }
        @Bean SystemInfoService systemInfoService() { return filled(SystemInfoService.class); }
        @Bean AdoptionService adoptionService() { return filled(AdoptionService.class); }
        @Bean FunnelService funnelService() { return filled(FunnelService.class); }
        @Bean ApiKeyAdminService apiKeyAdminService() { return filled(ApiKeyAdminService.class); }
        @Bean AuditChainBackfillService auditChainBackfillService() { return filled(AuditChainBackfillService.class); }
        @Bean SecurityIncidentService securityIncidentService() { return filled(SecurityIncidentService.class); }
        @Bean MdsSchedulerService mdsSchedulerService() { return filled(MdsSchedulerService.class); }
        @Bean MdsHistoryService mdsHistoryService() { return filled(MdsHistoryService.class); }
        @Bean SignupRequestService signupRequestService() { return filled(SignupRequestService.class); }
        @Bean AdminUserService adminUserService() { return filled(AdminUserService.class); }
        @Bean PasswordResetService passwordResetService() { return filled(PasswordResetService.class); }
        @Bean AaguidPolicyService aaguidPolicyService() { return filled(AaguidPolicyService.class); }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired Environment env;

    // AdminWebSliceTest 묶음의 mock
    @Autowired Clock clock;
    @Autowired AdminUserRepository admins;
    @Autowired TenantRepository tenantRepo;
    @Autowired AuditLogRepository auditRepo;
    @Autowired SigningKeyRepository signingKeys;
    @Autowired TenantBoundary tenantBoundary;
    @Autowired SecurityPolicyService securityPolicy;
    @Autowired AdminUserDetailsService uds;
    @Autowired PasswordEncoder encoder;

    // Fill mock
    @Autowired TenantAdminService tenantService;
    @Autowired RecoveryCodeService recoveryCodes;
    @Autowired TotpService totp;
    @Autowired SecurityIncidentService incidents;
    @Autowired PasswordResetService passwordReset;
    @Autowired CredentialAdminService credentialService;
    @Autowired ActivityService activityService;

    // 이 슬라이스에서만 필요한 인프라 mock
    @MockitoBean LicenseStateMachine licenseStateMachine;
    @MockitoBean LicenseProperties licenseProperties;
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean StringRedisTemplate redis;
    @MockitoBean DbSchemaProperties dbSchema;

    @BeforeEach
    void stubInfrastructure() throws Exception {
        when(clock.instant()).thenReturn(Fill.INSTANT);
        when(clock.millis()).thenReturn(Fill.INSTANT.toEpochMilli());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(tenantBoundary.currentTenantScope()).thenReturn(Optional.empty());
        when(admins.findByEmail(anyString())).thenAnswer(inv -> Optional.of(new AdminUser(inv.getArgument(0), "x", "PLATFORM_OPERATOR")));
        when(securityPolicy.get()).thenAnswer(Fill.ANSWER);
        when(securityPolicy.update(any(), anyString())).thenAnswer(Fill.ANSWER);

        Tenant tenant = mock(Tenant.class, Fill.ANSWER);
        when(tenantRepo.findAll()).thenReturn(List.of(tenant));
        when(tenantRepo.findAllById(any())).thenReturn(List.of(tenant));
        AuditLog row = mock(AuditLog.class, Fill.ANSWER);
        when(auditRepo.findAllByTenantOrdered(any())).thenReturn(List.of(row));
        when(auditRepo.search(any(), any(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(row)));
        when(signingKeys.findAll()).thenReturn(List.of(mock(SigningKey.class, Fill.ANSWER)));
        when(admins.findAllById(any())).thenReturn(List.of(mock(AdminUser.class, Fill.ANSWER)));

        LicenseToken token = Fill.of(LicenseToken.class);
        when(licenseStateMachine.snapshot()).thenReturn(
                new LicenseStateMachine.Snapshot(LicenseState.NETWORK_GRACE, token, Fill.INSTANT, "x"));
        when(licenseProperties.heartbeatInterval()).thenReturn(Duration.ofHours(1));

        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("version")).thenReturn(7L);
        when(rs.getDate("nextUpdate")).thenReturn(java.sql.Date.valueOf(Fill.DATE));
        when(rs.getTimestamp("fetchedAt")).thenReturn(Timestamp.from(Fill.INSTANT));
        when(jdbc.queryForObject(anyString(), any(RowMapper.class)))
                .thenAnswer(inv -> ((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
        @SuppressWarnings("unchecked")
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn("mds:aaguid:1");
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
    }

    @AfterEach
    void resetFilledMocks() {
        FilledMocks.ALL.forEach(Mockito::reset);
    }

    // ── principals ─────────────────────────────────────────

    static Authentication operator() {
        return auth("PLATFORM_OPERATOR", Set.of());
    }

    static Authentication rpAdmin() {
        return auth("RP_ADMIN", Set.of(Fill.ID));
    }

    private static Authentication auth(String role, Set<UUID> tenants) {
        AdminUserDetails p = new AdminUserDetails(OP_ID, "op@example.com", "x", role, tenants, true, null, Clock.systemUTC());
        return new UsernamePasswordAuthenticationToken(p, "x", p.getAuthorities());
    }

    // ── 요청·스냅샷 도우미 ──────────────────────────────────

    private static MockHttpServletRequestBuilder asOp(MockHttpServletRequestBuilder b) {
        return b.with(authentication(operator())).with(csrf());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** 레코드 응답(필드 순서 결정적) — 키 순서까지 고정. */
    private void shape(String name, MockHttpServletRequestBuilder req) throws Exception {
        snapshot(name, req, true);
    }

    /** {@code Map.of(...)} 응답(순회 순서가 JVM 실행마다 다름) — 키 집합·타입만 고정. */
    private void unorderedShape(String name, MockHttpServletRequestBuilder req) throws Exception {
        snapshot(name, req, false);
    }

    private void snapshot(String name, MockHttpServletRequestBuilder req, boolean ordered) throws Exception {
        MvcResult r = mvc.perform(req).andReturn();
        MockHttpServletResponse res = r.getResponse();
        TreeMap<String, String> meta = new TreeMap<>();
        meta.put("@status", String.valueOf(res.getStatus()));
        meta.put("@contentType", String.valueOf(res.getContentType()));
        String body = res.getContentAsString(StandardCharsets.UTF_8);
        String shape;
        if (body.isEmpty()) {
            meta.put("@body", "empty");
            shape = AdminJsonShape.of("{}", false, meta);
        } else {
            shape = AdminJsonShape.of(body, ordered, meta);
        }
        ContractSnapshot.assertMatches("json/" + name + ".shape.json", shape);
    }

    private static final String TENANT_CREATE_BODY = """
            {"slug":"acme","displayName":"Acme","rpId":"acme.example","rpName":"Acme",
             "allowedOrigins":["https://acme.example"],"acceptedFormats":["none"],
             "requireUserVerification":true,"mdsRequired":false,"attestationConveyance":"NONE","webauthnTimeoutMs":60000}""";

    // ── 보안 체인 JSON(로그인·401·403) ──────────────────────

    @Test
    void securityChainResponses() throws Exception {
        AdminUserDetails principal = (AdminUserDetails) operator().getPrincipal();
        when(uds.loadUserByUsername("op@example.com")).thenReturn(principal);
        when(encoder.matches(anyString(), anyString())).thenAnswer(inv -> "right".equals(inv.getArgument(0)));

        unorderedShape("security-login-success", post("/admin/login").with(csrf().asHeader())
                .param("email", "op@example.com").param("password", "right"));
        shape("security-login-failure", post("/admin/login").with(csrf().asHeader())
                .param("email", "op@example.com").param("password", "wrong"));
        shape("security-unauthenticated-401", get("/admin/api/tenants"));
        shape("security-csrf-missing-403", post("/admin/api/tenants").with(authentication(operator()))
                .contentType(MediaType.APPLICATION_JSON).content(TENANT_CREATE_BODY));
    }

    // ── core GlobalExceptionHandler 오류 봉투 ───────────────

    @Test
    void errorEnvelopes() throws Exception {
        when(tenantService.get("missing")).thenThrow(new BusinessException(ErrorCode.TENANT_NOT_FOUND));
        shape("error-business-404", asOp(get("/admin/api/tenants/missing")));
        shape("error-validation-400", asOp(json(post("/admin/api/tenants"), "{}")));
        shape("error-malformed-json-400", asOp(json(post("/admin/api/tenants"), "{")));
        shape("error-type-mismatch-400", asOp(get("/admin/api/tenants/not-a-uuid/credentials")));
        shape("error-missing-param-400", asOp(get("/admin/api/audit/chain/monthly-report")));
        shape("error-method-not-allowed-405", asOp(delete("/admin/api/tenants")));
        shape("error-illegal-argument-400", asOp(get("/admin/api/audit/chain/overview").param("windowHours", "0")));
        // @PreAuthorize(PLATFORM_OPERATOR) 를 RP_ADMIN 이 호출 — 메서드 보안 AccessDenied 봉투.
        shape("error-role-forbidden-403", json(post("/admin/api/tenants"), TENANT_CREATE_BODY)
                .with(authentication(rpAdmin())).with(csrf()));
        // RP_ADMIN 이 다른 tenant 의 감사 로그를 지정 — BusinessException(ACCESS_DENIED).
        when(tenantBoundary.currentTenantScope()).thenReturn(Optional.of(Fill.ID));
        shape("error-business-403", get("/admin/api/audit").param("tenantId", OTHER_TENANT.toString())
                .with(authentication(rpAdmin())));
    }

    // ── WebExceptionAdvice(화면 오류 봉투: htmx HX-Trigger JSON) ─

    @Test
    void webExceptionAdviceHxTrigger() throws Exception {
        when(tenantService.get("missing")).thenThrow(new BusinessException(ErrorCode.TENANT_NOT_FOUND));
        hxTrigger("web-error-htmx-toast", get("/admin/tenants/missing/funnel.csv")
                .header("HX-Request", "true").with(authentication(operator())));
        hxTrigger("web-error-htmx-confirm", get("/admin/tenants/missing/funnel.csv")
                .header("HX-Request", "true").header("X-Confirm", "1").with(authentication(operator())));
    }

    private void hxTrigger(String name, MockHttpServletRequestBuilder req) throws Exception {
        MockHttpServletResponse res = mvc.perform(req).andReturn().getResponse();
        String trigger = res.getHeader("HX-Trigger");
        assertThat(trigger).as(name + " HX-Trigger").isNotNull();
        TreeMap<String, String> meta = new TreeMap<>();
        meta.put("@status", String.valueOf(res.getStatus()));
        meta.put("@body", res.getContentAsString(StandardCharsets.UTF_8).isEmpty() ? "empty" : "present");
        ContractSnapshot.assertMatches("json/" + name + ".shape.json", AdminJsonShape.of(trigger, false, meta));
    }

    // ── 엔드포인트별 ────────────────────────────────────────

    @Test
    void tenantAdmin() throws Exception {
        shape("tenant-list", asOp(get("/admin/api/tenants")));
        shape("tenant-get", asOp(get("/admin/api/tenants/acme")));
        shape("tenant-create", asOp(json(post("/admin/api/tenants"), TENANT_CREATE_BODY)));
        shape("tenant-update-empty", asOp(json(put("/admin/api/tenants/acme"), "{}")));
        shape("tenant-diff-empty", asOp(json(post("/admin/api/tenants/acme/webauthn-config/diff"), "{}")));
        shape("tenant-suspend", asOp(post("/admin/api/tenants/acme/suspend")));
        shape("tenant-activate", asOp(post("/admin/api/tenants/acme/activate")));
    }

    @Test
    void credentialAdmin() throws Exception {
        shape("credential-list", asOp(get(TENANT_PATH + "/credentials")));
        shape("credential-auth-events", asOp(get(TENANT_PATH + "/credentials/AAAAAAAAAAAAAAAAAAAAAA/auth-events")));
        shape("credential-revoke", asOp(delete(TENANT_PATH + "/credentials/AAAAAAAAAAAAAAAAAAAAAA")));
    }

    @Test
    void profileMeLicense() throws Exception {
        shape("profile-get", get("/admin/api/profile"));
        shape("me-get", asOp(get("/admin/api/me")));
        MockHttpSession pending = new MockHttpSession();
        pending.setAttribute(MfaPendingFilter.MFA_PENDING_ATTR, Boolean.TRUE);
        shape("me-get-mfa-pending", get("/admin/api/me").session(pending).with(authentication(operator())));
        shape("me-get-rp-admin", get("/admin/api/me").with(authentication(rpAdmin())));
        shape("license-get-onprem", get("/admin/api/license"));

        // SaaS 모드(LicenseStateMachine 빈 없음)는 컨트롤러를 직접 만들어 같은 ObjectMapper 로 직렬화한다.
        LicenseController saas = new LicenseController("saas",
                new StaticListableBeanFactory().getBeanProvider(LicenseStateMachine.class), licenseProperties, clock);
        TreeMap<String, String> meta = new TreeMap<>();
        meta.put("@source", "LicenseController.get() direct, app ObjectMapper");
        String body = mapper.writeValueAsString(saas.get());
        ContractSnapshot.assertMatches("json/license-get-saas.shape.json", AdminJsonShape.of(body, true, meta));
    }

    @Test
    void insightsFunnelAdoption() throws Exception {
        shape("platform-insights-get", asOp(get("/admin/api/platform/insights")));
        shape("funnel-get", asOp(get(TENANT_PATH + "/funnel")));
        shape("adoption-get", asOp(get(TENANT_PATH + "/adoption")));
    }

    @Test
    void activeTenant() throws Exception {
        shape("active-tenant-get", get("/admin/api/active-tenant").with(authentication(rpAdmin())));
        shape("active-tenant-switch-empty", json(post("/admin/api/active-tenant"), "{}")
                .with(authentication(rpAdmin())).with(csrf()));
    }

    @Test
    void mfa() throws Exception {
        // {} — 코드 없음 → 401 invalid_code (복구 코드도 불일치).
        when(recoveryCodes.consume(any(), isNull())).thenReturn(false);
        shape("mfa-verify-empty", asOp(json(post("/admin/api/mfa/verify"), "{}")));
        shape("mfa-confirm-empty", asOp(json(post("/admin/api/mfa/confirm"), "{}")));
        shape("mfa-disable-empty", asOp(json(post("/admin/api/mfa/disable"), "{}")));
        unorderedShape("mfa-enroll", asOp(post("/admin/api/mfa/enroll")));

        // 복구 코드로 통과(TOTP 불일치) — verified·usedRecoveryCode·remaining.
        when(totp.matchedCounter(anyString(), anyString(), anyLong())).thenReturn(OptionalLong.empty());
        when(recoveryCodes.consume(any(), eq("rc-1"))).thenReturn(true);
        unorderedShape("mfa-verify-recovery", asOp(json(post("/admin/api/mfa/verify"), "{\"code\":\"rc-1\"}")));

        // TOTP 일치 — 비밀이 등록된 사용자.
        when(admins.findByEmail(anyString())).thenAnswer(inv -> {
            AdminUser u = new AdminUser(inv.getArgument(0), "x", "PLATFORM_OPERATOR");
            u.setMfaSecret("sealed");
            return Optional.of(u);
        });
        when(totp.matchedCounter(anyString(), eq("123456"), anyLong())).thenReturn(OptionalLong.of(42L));
        shape("mfa-verify-totp", asOp(json(post("/admin/api/mfa/verify"), "{\"code\":\"123456\"}")));
        unorderedShape("mfa-confirm", asOp(json(post("/admin/api/mfa/confirm"), "{\"code\":\"123456\"}")));
        shape("mfa-disable", asOp(json(post("/admin/api/mfa/disable"), "{\"code\":\"123456\"}")));

        // MFA 대기 세션의 enroll — MfaPendingFilter/컨트롤러 이중 차단 응답.
        MockHttpSession pending = new MockHttpSession();
        pending.setAttribute(MfaPendingFilter.MFA_PENDING_ATTR, Boolean.TRUE);
        shape("mfa-enroll-pending", post("/admin/api/mfa/enroll").session(pending)
                .with(authentication(operator())).with(csrf()));
    }

    @Test
    void activityAndAudit() throws Exception {
        shape("activity-get", asOp(get("/admin/api/activity")));
        shape("activity-detail", asOp(get("/admin/api/activity/" + Fill.ID)));
        shape("audit-list", asOp(get("/admin/api/audit")));
        shape("audit-verify", asOp(get("/admin/api/audit/verify")));
    }

    @Test
    void auditChainMonitor() throws Exception {
        shape("audit-chain-overview", asOp(get("/admin/api/audit/chain/overview")));
        shape("audit-chain-verify", asOp(get("/admin/api/audit/chain/verify").param("tenantId", Fill.ID.toString())));
        shape("audit-chain-backfill", asOp(post("/admin/api/audit/chain/backfill")));
        shape("audit-chain-incidents", asOp(get("/admin/api/audit/chain/incidents")));
        shape("audit-chain-incident-create-empty", asOp(json(post("/admin/api/audit/chain/incidents"), "{}")));
        shape("audit-chain-incident-resolve-empty", asOp(json(post("/admin/api/audit/chain/incidents/" + Fill.ID + "/resolve"), "{}")));
        String create = "{\"tenantId\":\"" + Fill.ID + "\"}";
        shape("audit-chain-incident-create", asOp(json(post("/admin/api/audit/chain/incidents"), create)));
        shape("audit-chain-incident-resolve",
                asOp(json(post("/admin/api/audit/chain/incidents/" + Fill.ID + "/resolve"), "{\"note\":\"done\"}")));

        // 컨트롤러 @ExceptionHandler 3종(Map.of 두 키 — 순서 비결정).
        when(incidents.create(any(), any(), anyString())).thenThrow(new IncidentConflictException("open incident exists"));
        unorderedShape("audit-chain-incident-conflict-409", asOp(json(post("/admin/api/audit/chain/incidents"), create)));
        Mockito.reset(incidents);
        when(incidents.create(any(), any(), anyString())).thenThrow(new IncidentNotTamperedException("chain intact"));
        unorderedShape("audit-chain-incident-not-tampered-422", asOp(json(post("/admin/api/audit/chain/incidents"), create)));
        when(incidents.resolve(any(), anyString(), any(), anyString())).thenThrow(new IncidentNotFoundException("no incident"));
        unorderedShape("audit-chain-incident-not-found-404",
                asOp(json(post("/admin/api/audit/chain/incidents/" + Fill.ID + "/resolve"), "{\"note\":\"done\"}")));
    }

    @Test
    void keysSystemMds() throws Exception {
        shape("keys-list", asOp(get("/admin/api/keys")));
        shape("keys-rotate", asOp(post("/admin/api/keys/rotate")));
        shape("system-info-get", asOp(get("/admin/api/system/info")));
        shape("mds-status", asOp(get("/admin/api/mds/status")));
        shape("mds-sync", asOp(post("/admin/api/mds/sync")));
        shape("mds-history", asOp(get("/admin/api/mds/history")));
    }

    @Test
    void apiKeys() throws Exception {
        shape("api-key-list", asOp(get("/admin/api/api-keys")));
        shape("api-key-issue-empty", asOp(json(post("/admin/api/api-keys"), "{}")));
        shape("api-key-issue", asOp(json(post("/admin/api/api-keys"),
                "{\"tenantId\":\"" + Fill.ID + "\",\"name\":\"k\",\"scopes\":[\"passkey:read\"],\"expiresInMonths\":12}")));
        shape("api-key-rotate", asOp(post("/admin/api/api-keys/" + Fill.ID + "/rotate")));
        shape("api-key-revoke", asOp(delete("/admin/api/api-keys/" + Fill.ID)));
    }

    @Test
    void operators() throws Exception {
        shape("signup-request-empty", json(post("/admin/api/signup-requests"), "{}"));
        unorderedShape("signup-request", json(post("/admin/api/signup-requests"),
                "{\"email\":\"new@example.com\",\"password\":\"correct-horse-battery\",\"reason\":\"r\"}"));
        shape("signup-request-list", asOp(get("/admin/api/signup-requests")));
        shape("signup-request-approve-empty", asOp(json(post("/admin/api/signup-requests/" + Fill.ID + "/approve"), "{}")));
        shape("signup-request-approve", asOp(json(post("/admin/api/signup-requests/" + Fill.ID + "/approve"),
                "{\"role\":\"RP_ADMIN\",\"tenantIds\":[\"" + Fill.ID + "\"]}")));
        shape("signup-request-reject", asOp(post("/admin/api/signup-requests/" + Fill.ID + "/reject")));

        shape("admin-user-list", asOp(get("/admin/api/admin-users")));
        shape("admin-user-suspend", asOp(post("/admin/api/admin-users/" + Fill.ID + "/suspend")));
        shape("admin-user-activate", asOp(post("/admin/api/admin-users/" + Fill.ID + "/activate")));
        shape("admin-user-add-tenant-empty", asOp(json(post("/admin/api/admin-users/" + Fill.ID + "/tenants"), "{}")));
        shape("admin-user-remove-tenant", asOp(delete("/admin/api/admin-users/" + Fill.ID + "/tenants/" + OTHER_TENANT)));

        shape("password-reset-request-empty", json(post("/admin/api/password-reset/request"), "{}"));
        shape("password-reset-confirm-empty", json(post("/admin/api/password-reset/confirm"), "{}"));
        doThrow(new IllegalArgumentException("invalid or expired token")).when(passwordReset).confirm("bad", "pw");
        shape("password-reset-confirm-invalid", json(post("/admin/api/password-reset/confirm"),
                "{\"token\":\"bad\",\"newPassword\":\"pw\"}"));
    }

    @Test
    void policies() throws Exception {
        shape("aaguid-policy-get", asOp(get(TENANT_PATH + "/aaguid-policy")));
        shape("aaguid-policy-update-empty", asOp(json(put(TENANT_PATH + "/aaguid-policy"), "{}")));
        shape("security-policy-get", asOp(get("/admin/api/security-policy")));
        shape("security-policy-update-empty", asOp(json(put("/admin/api/security-policy"), "{}")));
        shape("security-policy-update", asOp(json(put("/admin/api/security-policy"),
                "{\"sessionIdleTimeoutMinutes\":30,\"mfaRequired\":true,\"corsAllowlist\":[\"https://a.example\"]}")));
    }

    /**
     * null 이 든 응답 — Fill 은 모든 필드를 채우므로 이 경우를 따로 고정한다. 전역 {@code non_null} 이라 null 필드는 키가
     * <b>빠진다</b>(값 null 로 나오지 않는다). Kotlin 전환에서 nullable 처리·포함 규칙이 바뀌면 여기서 드러난다.
     */
    @Test
    void nullBearingResponses() throws Exception {
        // 감사 로그: 시스템 행(actorId·actorEmail null) + 대상 없음(targetType·targetId null) + payload null.
        AuditLog systemRow = mock(AuditLog.class, Fill.ANSWER);
        when(systemRow.getActorId()).thenReturn(null);
        when(systemRow.getActorEmail()).thenReturn(null);
        when(systemRow.getTargetType()).thenReturn(null);
        when(systemRow.getTargetId()).thenReturn(null);
        when(systemRow.getPayload()).thenReturn(null);
        when(auditRepo.search(any(), any(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(systemRow)));
        shape("audit-list-nulls", asOp(get("/admin/api/audit")));

        // credential: 선택 시각(lastUsedAt)·별칭·AAGUID·인증기 이름 null.
        when(credentialService.list(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(new com.crosscert.passkey.core.api.PageView<>(List.of(
                        new com.crosscert.passkey.admin.credential.CredentialAdminDto.CredentialView(
                                "AAAAAAAAAAAAAAAAAAAAAA", "dXNlci0x", null, null, null, "none", null, 0, null, Fill.ODT)),
                        0, 50, 1, false));
        shape("credential-list-nulls", asOp(get(TENANT_PATH + "/credentials")));

        // activity: kpi.p95Ms(Long, 문서상 nullable) null, 이벤트의 tenant·actor null.
        when(activityService.snapshot(any(), any(), any(), any(), any())).thenReturn(new com.crosscert.passkey.admin.activity.ActivityView(
                new com.crosscert.passkey.admin.activity.ActivityView.Kpi(1, 0, 0, null), List.of(),
                List.of(new com.crosscert.passkey.admin.activity.ActivityView.Event(
                        Fill.ID, "MDS_SYNC", null, null, null, null, null, Fill.ODT, "system"))));
        shape("activity-get-nulls", asOp(get("/admin/api/activity")));
    }

    @Test
    void environmentHasNoActiveProfile() {
        // profile-get 스냅샷의 active 배열 형태가 실행 환경에 따라 흔들리지 않는다는 전제를 고정한다.
        assertThat(env.getActiveProfiles()).isEmpty();
    }
}
