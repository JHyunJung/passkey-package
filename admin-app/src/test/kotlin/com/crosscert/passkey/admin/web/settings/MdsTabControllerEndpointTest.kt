package com.crosscert.passkey.admin.web.settings

import com.crosscert.passkey.admin.audit.AuditChainMonitorController
import com.crosscert.passkey.admin.auth.ActiveTenantController
import com.crosscert.passkey.admin.auth.MfaController
import com.crosscert.passkey.admin.license.LicenseController
import com.crosscert.passkey.admin.mds.MdsAdminController
import com.crosscert.passkey.admin.operator.SignupRequestController
import com.crosscert.passkey.admin.system.SystemInfoController
import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.core.api.ApiResponse
import org.assertj.core.api.Assertions.assertThat
import org.jsoup.Jsoup
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.ZoneOffset

/** 설정 › MDS 탭의 endpoint 표시가 하드코딩이 아니라 실제 `passkey.mds.blob-endpoint` 를 따른다. */
@AdminWebSliceTest(controllers = [MdsTabController::class])
@TestPropertySource(properties = ["passkey.mds.blob-endpoint=https://mds.example.test/blob"])
class MdsTabControllerEndpointTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var clock: Clock
    @MockitoBean lateinit var mds: MdsAdminController
    @MockitoBean lateinit var mfa: MfaController
    @MockitoBean lateinit var signups: SignupRequestController
    @MockitoBean lateinit var systemInfo: SystemInfoController
    @MockitoBean lateinit var license: LicenseController
    @MockitoBean lateinit var chain: AuditChainMonitorController
    @MockitoBean lateinit var activeTenants: ActiveTenantController

    @Test
    @DisplayName("blob-endpoint 를 바꾸면 화면의 '주소 (endpoint)' 도 그 값을 보인다")
    fun endpointFollowsConfiguration() {
        whenever(clock.instant()).thenReturn(MdsTabControllerTest.NOW)
        whenever(clock.zone).thenReturn(ZoneOffset.UTC)
        whenever(mds.status()).thenReturn(
            ApiResponse.ok(MdsTabControllerTest.status(12, "2026-10-12", "2026-10-10T23:57:00Z", "MDS_STRICT_OPTIONAL", 28, 30)),
        )
        whenever(mds.history(anyInt())).thenReturn(ApiResponse.ok(listOf()))

        val r = mvc.perform(
            get(MdsTabController.TAB_PATH).with(authentication(MdsTabControllerTest.platform())).header("HX-Request", "true"),
        ).andReturn()

        assertThat(r.response.status).isEqualTo(200)
        val d = Jsoup.parse(r.response.getContentAsString(StandardCharsets.UTF_8))
        assertThat(d.selectFirst("dl.kv dd.kv__nowrap")!!.text()).isEqualTo("https://mds.example.test/blob")
    }
}
