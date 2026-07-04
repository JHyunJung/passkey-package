package com.crosscert.passkey.admin.operator;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * G11: admin.invite.base-url must not silently boot in prod with the
 * localhost dev default — see InvitationService/PasswordResetService's
 * validateBaseUrlForProd() @PostConstruct hooks.
 */
class BaseUrlValidationTest {

    @Test
    void prodProfileWithLocalhostDefaultFailsFast() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});

        assertThatThrownBy(() -> BaseUrlValidation.assertNotLocalhostFallbackInProd(
                env, "http://localhost:5173", "admin.invite.base-url"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admin.invite.base-url")
                .hasMessageContaining("ADMIN_INVITE_BASE_URL");
    }

    @Test
    void prodProfileWithHttpsLocalhostAlsoFailsFast() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});

        assertThatThrownBy(() -> BaseUrlValidation.assertNotLocalhostFallbackInProd(
                env, "https://localhost:5173", "admin.invite.base-url"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void prodProfileWithRealOriginBootsFine() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});

        assertThatCode(() -> BaseUrlValidation.assertNotLocalhostFallbackInProd(
                env, "https://admin.passkey.example.com", "admin.invite.base-url"))
                .doesNotThrowAnyException();
    }

    @Test
    void nonProdProfileWithLocalhostDefaultIsAllowed() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"dev"});

        assertThatCode(() -> BaseUrlValidation.assertNotLocalhostFallbackInProd(
                env, "http://localhost:5173", "admin.invite.base-url"))
                .doesNotThrowAnyException();
    }
}
