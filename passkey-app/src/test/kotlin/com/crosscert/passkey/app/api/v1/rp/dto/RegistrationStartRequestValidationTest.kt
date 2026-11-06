package com.crosscert.passkey.app.api.v1.rp.dto

import jakarta.validation.Validation
import jakarta.validation.Validator
import jakarta.validation.ValidatorFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * F20 — RegistrationStartRequest.displayName/username 에 상한이 없어 임의로 큰
 * 문자열이 @Valid 를 통과해 Redis(RegistrationChallenge) 저장/응답 echo 로 그대로
 * 흘러갔다(하드닝 드리프트). @Size(max) 부여로 oversized 입력을 400 으로 거부하는지
 * Bean Validation 레벨에서 직접 검증한다(컨트롤러가 @Valid @RequestBody 로 위임하는
 * 동일한 검증기).
 */
class RegistrationStartRequestValidationTest {

    companion object {
        // 클래스 단위 1회 생성·종료(@BeforeAll/@AfterAll) — 수명주기 콜백에서 초기화되므로 lateinit.
        private lateinit var factory: ValidatorFactory
        private lateinit var validator: Validator

        @JvmStatic
        @BeforeAll
        fun setUp() {
            factory = Validation.buildDefaultValidatorFactory()
            validator = factory.validator
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            factory.close()
        }
    }

    @Test
    fun rejectsOversizedUsername() {
        val hugeUsername = "a".repeat(300)
        val req = RegistrationStartRequest("dXNlcg", "Disp", hugeUsername)

        val violations = validator.validate(req)

        assertThat(violations).anyMatch { it.propertyPath.toString() == "username" }
    }

    @Test
    fun rejectsOversizedDisplayName() {
        val hugeDisplayName = "a".repeat(300)
        val req = RegistrationStartRequest("dXNlcg", hugeDisplayName, "alice")

        val violations = validator.validate(req)

        assertThat(violations).anyMatch { it.propertyPath.toString() == "displayName" }
    }

    @Test
    fun acceptsNormalSizedInput() {
        val req = RegistrationStartRequest("dXNlcg", "Alice Doe", "alice")

        val violations = validator.validate(req)

        assertThat(violations).isEmpty()
    }
}
