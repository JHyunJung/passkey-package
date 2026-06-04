package com.crosscert.passkey.admin.apikey;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyCreateRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setup() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private ApiKeyAdminDto.ApiKeyCreateRequest req(String name) {
        return new ApiKeyAdminDto.ApiKeyCreateRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                name,
                Set.of("registration"));
    }

    @Test
    void name64CharsIsValid() {
        var violations = validator.validate(req("a".repeat(64)));
        assertThat(violations).isEmpty();
    }

    @Test
    void name65CharsViolatesSize() {
        var violations = validator.validate(req("a".repeat(65)));
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("name")
                        && v.getConstraintDescriptor().getAnnotation() instanceof Size);
    }

    @Test
    void blankNameViolatesNotBlank() {
        var violations = validator.validate(req("   "));
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("name")
                        && v.getConstraintDescriptor().getAnnotation() instanceof NotBlank);
    }

    @Test
    void nullNameViolatesNotBlank() {
        var violations = validator.validate(req(null));
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("name")
                        && v.getConstraintDescriptor().getAnnotation() instanceof NotBlank);
    }
}
