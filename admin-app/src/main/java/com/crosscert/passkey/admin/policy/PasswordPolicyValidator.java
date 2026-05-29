package com.crosscert.passkey.admin.policy;

import com.crosscert.passkey.core.entity.SecurityPolicy;
import com.crosscert.passkey.core.repository.SecurityPolicyRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** P0-6: security_policy.password_min_length 를 실제 강제. */
@Component
public class PasswordPolicyValidator {

    private static final Long SINGLETON_ID = 1L;
    private final SecurityPolicyRepository repo;

    public PasswordPolicyValidator(SecurityPolicyRepository repo) {
        this.repo = repo;
    }

    /**
     * Throws {@link IllegalArgumentException} when the password is shorter than the
     * configured minimum. The core {@code GlobalExceptionHandler} maps
     * IllegalArgumentException -> 400 INVALID_INPUT, so clients get a clean 400 (not 500)
     * and the min-length value is preserved in the message for the operator.
     */
    @Transactional(readOnly = true)
    public void validate(String password) {
        SecurityPolicy p = repo.findById(SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("security_policy singleton missing"));
        int min = p.getPasswordMinLength();
        if (password == null || password.length() < min) {
            throw new IllegalArgumentException(
                    "password 가 정책 최소 길이(" + min + ")보다 짧습니다");
        }
    }
}
