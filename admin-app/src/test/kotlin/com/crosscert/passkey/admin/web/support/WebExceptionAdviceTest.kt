package com.crosscert.passkey.admin.web.support

import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.entity.AdminUser
import com.crosscert.passkey.core.repository.AdminUserRepository
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.instanceOf
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.stereotype.Controller
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.model
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import org.springframework.web.bind.annotation.GetMapping
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.Optional
import java.util.UUID

/** WebExceptionAdvice(htmx 토스트 / 오류 페이지)와 ViewModelAdvice(me·profile) — admin.web 패키지 더미 컨트롤러로 확인. */
@AdminWebSliceTest
@Import(WebExceptionAdviceTest.DummyPageController::class)
class WebExceptionAdviceTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var users: AdminUserRepository

    @Controller
    class DummyPageController {
        @GetMapping("/admin/_/test/business")
        fun business(): String { throw BusinessException(ErrorCode.TENANT_NOT_FOUND) }

        @GetMapping("/admin/_/test/boom")
        fun boom(): String { throw IllegalStateException("boom") }

        @GetMapping("/admin/_/test/nonstandard")
        fun nonStandard(): String {
            throw org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatusCode.valueOf(499), "client closed",
            )
        }

        @GetMapping("/admin/_/test/uuid/{id}")
        fun uuid(@org.springframework.web.bind.annotation.PathVariable id: UUID): String = "admin/error"

        @GetMapping("/admin/_/test/denied")
        fun denied(): String { throw org.springframework.security.access.AccessDeniedException("platform operator only") }

        @GetMapping("/admin/_/test/conflict")
        fun conflict(): String {
            throw org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "이미 처리된 요청입니다")
        }

        @GetMapping("/admin/_/test/model")
        fun model(): String = "admin/error" // 아무 템플릿이나 — 모델만 본다
    }

    companion object {
        private fun operator(): Authentication {
            val p = AdminUserDetails(
                UUID.randomUUID(), "alice@example.com", "x",
                "PLATFORM_OPERATOR", setOf(), true, null, Clock.systemUTC(),
            )
            return UsernamePasswordAuthenticationToken(p, "x", p.authorities)
        }
    }

    @Test
    fun htmxErrorKeepsRealStatusWithToastAndEmptyBody() {
        // 규칙 A: 실제 오류 상태(404)를 유지하고 토스트는 HX-Trigger 로, 본문은 비운다.
        val r = mvc.perform(get("/admin/_/test/business").header("HX-Request", "true").with(authentication(operator())))
            .andExpect(status().isNotFound())
            .andExpect(header().string("HX-Trigger", containsString("\"kind\":\"error\"")))
            .andExpect(content().string(""))
            .andReturn()
        val toast = ObjectMapper().readTree(r.response.getHeader("HX-Trigger")).get("toast")
        assertThat(toast.get("message").asString()).isEqualTo("찾을 수 없습니다. 삭제되었거나 주소가 바뀌었을 수 있습니다.")
        // 원문은 본문 메시지가 아니라 detail("자세히")로만 간다(errorToast.ts).
        assertThat(toast.get("message").asString()).doesNotContain("HTTP")
        assertThat(toast.get("detail").asString()).startsWith("HTTP 404 · [").contains(ErrorCode.TENANT_NOT_FOUND.code)
    }

    @Test
    fun htmxUnexpectedErrorIs500WithoutLeakingMessage() {
        val r = mvc.perform(get("/admin/_/test/boom").header("HX-Request", "true").with(authentication(operator())))
            .andExpect(status().isInternalServerError())
            .andExpect(content().string(""))
            .andReturn()
        val toast = ObjectMapper().readTree(r.response.getHeader("HX-Trigger")).get("toast")
        assertThat(toast.get("detail").asString()).isEqualTo("HTTP 500")
        assertThat(r.response.getHeader("HX-Trigger")).doesNotContain("boom")
    }

    @Test
    fun pageErrorRendersErrorTemplateWithStatus() {
        mvc.perform(get("/admin/_/test/business").with(authentication(operator())))
            .andExpect(status().isNotFound())
            .andExpect(view().name("admin/error"))
            .andExpect(content().string(containsString("찾을 수 없습니다")))
            .andExpect(content().string(containsString("삭제되었거나 주소가 바뀌었을 수 있습니다.")))
    }

    @Test
    fun unexpectedErrorIs500Page() {
        mvc.perform(get("/admin/_/test/boom").with(authentication(operator())))
            .andExpect(status().isInternalServerError())
            .andExpect(view().name("admin/error"))
            .andExpect(content().string(containsString("서버가 잠시 응답하지 않습니다")))
    }

    @Test
    fun nonStandardStatusCodeIsKept() {
        mvc.perform(get("/admin/_/test/nonstandard").with(authentication(operator())))
            .andExpect(status().`is`(499))
            .andExpect(view().name("admin/error"))
    }

    @Test
    fun modelHasMeAndProfile() {
        val u = AdminUser("alice@example.com", "x", "PLATFORM_OPERATOR")
        whenever(users.findByEmail(anyString())).thenReturn(Optional.of(u))
        mvc.perform(get("/admin/_/test/model").with(authentication(operator())))
            .andExpect(status().isOk())
            .andExpect(model().attribute("me", instanceOf<Any>(CurrentAdmin::class.java)))
            .andExpect(model().attribute("profile", instanceOf<Any>(com.crosscert.passkey.admin.config.ProfileController.ProfileView::class.java)))
    }

    @Test
    fun badUuidPathIs400WithoutFrameworkMessage() {
        // 경로의 UUID 변환 실패(MethodArgumentTypeMismatchException): 400, 원문("Failed to convert…", "Invalid UUID string")은 토스트·오류 화면 어디에도 없다.
        val r = mvc.perform(get("/admin/_/test/uuid/not-a-uuid").header("HX-Request", "true").with(authentication(operator())))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(""))
            .andReturn()
        val trig = r.response.getHeader("HX-Trigger")
        val toast = ObjectMapper().readTree(trig).get("toast")
        assertThat(toast.get("detail").asString()).isEqualTo("HTTP 400")
        assertThat(trig).doesNotContain("convert").doesNotContain("UUID").doesNotContain("not-a-uuid")
        val page = mvc.perform(get("/admin/_/test/uuid/not-a-uuid").with(authentication(operator())))
            .andExpect(status().isBadRequest())
            .andExpect(view().name("admin/error"))
            .andReturn().response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
        assertThat(page).doesNotContain("convert").doesNotContain("Invalid UUID").doesNotContain("not-a-uuid")
    }

    @Test
    fun rawDetailOnlyCarriesBusinessMessagesAndStatusReasons() {
        // 권한 거부의 원문(내부 사유)은 싣지 않는다
        val d = mvc.perform(get("/admin/_/test/denied").header("HX-Request", "true").with(authentication(operator())))
            .andExpect(status().isForbidden()).andReturn()
        assertThat(ObjectMapper().readTree(d.response.getHeader("HX-Trigger")).get("toast").get("detail").asString()).isEqualTo("HTTP 403")
        // 4xx ResponseStatusException 은 reason 만(“409 CONFLICT \"…\"” 원문 아님)
        val c = mvc.perform(get("/admin/_/test/conflict").header("HX-Request", "true").with(authentication(operator())))
            .andExpect(status().isConflict()).andReturn()
        assertThat(ObjectMapper().readTree(c.response.getHeader("HX-Trigger")).get("toast").get("detail").asString())
            .isEqualTo("HTTP 409 이미 처리된 요청입니다")
    }
}
