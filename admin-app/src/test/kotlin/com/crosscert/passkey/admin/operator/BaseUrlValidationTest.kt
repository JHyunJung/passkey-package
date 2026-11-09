package com.crosscert.passkey.admin.operator

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.core.env.Environment

/**
 * G11: admin.invite.base-url must not silently boot in prod with the
 * localhost dev default — see PasswordResetService/SignupRequestService's
 * validateBaseUrlForProd() @PostConstruct hooks.
 */
class BaseUrlValidationTest {

    @Test
    fun prodProfileWithLocalhostDefaultFailsFast() {
        val env = mock<Environment>()
        whenever(env.activeProfiles).thenReturn(arrayOf("prod"))

        assertThatThrownBy {
            BaseUrlValidation.assertNotLocalhostFallbackInProd(
                env, "http://localhost:5173", "admin.invite.base-url",
            )
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("admin.invite.base-url")
            .hasMessageContaining("ADMIN_INVITE_BASE_URL")
    }

    @Test
    fun prodProfileWithHttpsLocalhostAlsoFailsFast() {
        val env = mock<Environment>()
        whenever(env.activeProfiles).thenReturn(arrayOf("prod"))

        assertThatThrownBy {
            BaseUrlValidation.assertNotLocalhostFallbackInProd(
                env, "https://localhost:5173", "admin.invite.base-url",
            )
        }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun prodProfileWithRealOriginBootsFine() {
        val env = mock<Environment>()
        whenever(env.activeProfiles).thenReturn(arrayOf("prod"))

        assertThatCode {
            BaseUrlValidation.assertNotLocalhostFallbackInProd(
                env, "https://admin.passkey.example.com", "admin.invite.base-url",
            )
        }
            .doesNotThrowAnyException()
    }

    @Test
    fun nonProdProfileWithLocalhostDefaultIsAllowed() {
        val env = mock<Environment>()
        whenever(env.activeProfiles).thenReturn(arrayOf("dev"))

        assertThatCode {
            BaseUrlValidation.assertNotLocalhostFallbackInProd(
                env, "http://localhost:5173", "admin.invite.base-url",
            )
        }
            .doesNotThrowAnyException()
    }
}
