package com.crosscert.passkey.app.api.v1.rp.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F20 — RegistrationStartRequest.displayName/username 에 상한이 없어 임의로 큰
 * 문자열이 @Valid 를 통과해 Redis(RegistrationChallenge) 저장/응답 echo 로 그대로
 * 흘러갔다(하드닝 드리프트). @Size(max) 부여로 oversized 입력을 400 으로 거부하는지
 * Bean Validation 레벨에서 직접 검증한다(컨트롤러가 @Valid @RequestBody 로 위임하는
 * 동일한 검증기).
 */
class RegistrationStartRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void rejectsOversizedUsername() {
        String hugeUsername = "a".repeat(300);
        RegistrationStartRequest req = new RegistrationStartRequest("dXNlcg", "Disp", hugeUsername);

        Set<ConstraintViolation<RegistrationStartRequest>> violations = validator.validate(req);

        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("username"));
    }

    @Test
    void rejectsOversizedDisplayName() {
        String hugeDisplayName = "a".repeat(300);
        RegistrationStartRequest req = new RegistrationStartRequest("dXNlcg", hugeDisplayName, "alice");

        Set<ConstraintViolation<RegistrationStartRequest>> violations = validator.validate(req);

        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("displayName"));
    }

    @Test
    void acceptsNormalSizedInput() {
        RegistrationStartRequest req = new RegistrationStartRequest("dXNlcg", "Alice Doe", "alice");

        Set<ConstraintViolation<RegistrationStartRequest>> violations = validator.validate(req);

        assertThat(violations).isEmpty();
    }
}
