package com.crosscert.passkey.admin.apikey

import jakarta.validation.Validation
import jakarta.validation.Validator
import jakarta.validation.ValidatorFactory
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.UUID

class ApiKeyCreateRequestValidationTest {

    companion object {
        private lateinit var factory: ValidatorFactory
        private lateinit var validator: Validator

        @JvmStatic
        @BeforeAll
        fun setup() {
            factory = Validation.buildDefaultValidatorFactory()
            validator = factory.validator
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            factory.close()
        }
    }

    private fun reqMonths(months: Int?): ApiKeyAdminDto.ApiKeyCreateRequest =
        ApiKeyAdminDto.ApiKeyCreateRequest(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "valid-name",
            setOf("registration"),
            months,
        )

    private fun req(name: String?): ApiKeyAdminDto.ApiKeyCreateRequest =
        ApiKeyAdminDto.ApiKeyCreateRequest(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            name,
            setOf("registration"),
            12,
        )

    @Test
    fun name64CharsIsValid() {
        val violations = validator.validate(req("a".repeat(64)))
        assertThat(violations).isEmpty()
    }

    @Test
    fun name65CharsViolatesSize() {
        val violations = validator.validate(req("a".repeat(65)))
        assertThat(violations)
            .anyMatch { v ->
                v.propertyPath.toString() == "name" &&
                    v.constraintDescriptor.annotation is Size
            }
    }

    @Test
    fun blankNameViolatesNotBlank() {
        val violations = validator.validate(req("   "))
        assertThat(violations)
            .anyMatch { v ->
                v.propertyPath.toString() == "name" &&
                    v.constraintDescriptor.annotation is NotBlank
            }
    }

    @Test
    fun nullNameViolatesNotBlank() {
        val violations = validator.validate(req(null))
        assertThat(violations)
            .anyMatch { v ->
                v.propertyPath.toString() == "name" &&
                    v.constraintDescriptor.annotation is NotBlank
            }
    }

    @Test
    fun nullExpiresInMonthsIsValid() {
        assertThat(validator.validate(reqMonths(null))).isEmpty()
    }

    @Test
    fun expiresInMonths24IsValid() {
        assertThat(validator.validate(reqMonths(24))).isEmpty()
    }

    @Test
    fun expiresInMonths0ViolatesMin() {
        val violations = validator.validate(reqMonths(0))
        assertThat(violations)
            .anyMatch { v ->
                v.propertyPath.toString() == "expiresInMonths" &&
                    v.constraintDescriptor.annotation is Min
            }
    }

    @Test
    fun expiresInMonths37ViolatesMax() {
        val violations = validator.validate(reqMonths(37))
        assertThat(violations)
            .anyMatch { v ->
                v.propertyPath.toString() == "expiresInMonths" &&
                    v.constraintDescriptor.annotation is Max
            }
    }
}
