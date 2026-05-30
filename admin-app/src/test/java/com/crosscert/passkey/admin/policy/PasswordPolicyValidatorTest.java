package com.crosscert.passkey.admin.policy;

import com.crosscert.passkey.core.entity.SecurityPolicy;
import com.crosscert.passkey.core.repository.SecurityPolicyRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PasswordPolicyValidatorTest {

    private final SecurityPolicyRepository repo = mock(SecurityPolicyRepository.class);

    private SecurityPolicy policyWithMin(int min) {
        SecurityPolicy p = mock(SecurityPolicy.class);
        when(p.getPasswordMinLength()).thenReturn(min);
        return p;
    }

    @Test
    void validate_passesWhenLongEnough() {
        SecurityPolicy p = policyWithMin(12);
        when(repo.findById(1L)).thenReturn(Optional.of(p));
        PasswordPolicyValidator v = new PasswordPolicyValidator(repo);
        assertThatNoException().isThrownBy(() -> v.validate("aVeryLongPassword1"));
    }

    @Test
    void validate_throwsWhenTooShort() {
        SecurityPolicy p = policyWithMin(12);
        when(repo.findById(1L)).thenReturn(Optional.of(p));
        PasswordPolicyValidator v = new PasswordPolicyValidator(repo);
        // IllegalArgumentException specifically (not just RuntimeException): the
        // validator contract is IllegalArgumentException -> 400. Asserting the exact
        // type guards against a regression to e.g. IllegalStateException -> 500.
        assertThatThrownBy(() -> v.validate("short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("12");
    }
}
