package com.crosscert.passkey.app.fido2.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttestationPolicyTest {

    @Test
    void parsesAllFieldsFromJson() {
        String json = """
            {
              "acceptedFormats": ["none","packed","apple"],
              "requireUserVerification": true,
              "mdsRequired": false
            }
            """;
        AttestationPolicy p = AttestationPolicy.fromJson(json);
        assertThat(p.acceptedFormats()).containsExactlyInAnyOrder("none","packed","apple");
        assertThat(p.requireUserVerification()).isTrue();
        assertThat(p.mdsRequired()).isFalse();
    }

    @Test
    void nullJsonReturnsConservativeDefault() {
        AttestationPolicy p = AttestationPolicy.fromJson(null);
        assertThat(p.acceptedFormats()).contains("none","packed");
        assertThat(p.requireUserVerification()).isTrue();
        assertThat(p.mdsRequired()).isFalse();
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> AttestationPolicy.fromJson("{not valid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attestation_policy JSON");
    }

    @Test
    void rejectsAcceptedFormatsThatIsNotArray() {
        // Fail closed — a misconfigured policy is a deployment bug,
        // not a license to silently widen the default policy.
        assertThatThrownBy(() -> AttestationPolicy.fromJson(
                "{\"acceptedFormats\":\"packed\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be an array");
    }

    @Test
    void rejectsAcceptedFormatsEmptyArray() {
        assertThatThrownBy(() -> AttestationPolicy.fromJson(
                "{\"acceptedFormats\":[]}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
    }

    @Test
    void acceptedFormatsAreImmutableAfterConstruction() {
        AttestationPolicy p = AttestationPolicy.fromJson(null);
        assertThatThrownBy(() -> p.acceptedFormats().add("attacker-injected-format"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
