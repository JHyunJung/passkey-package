package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.admin.policy.PasswordPolicyValidator;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.entity.AdminUserInvitation;
import com.crosscert.passkey.core.mail.MailSender;
import com.crosscert.passkey.core.repository.AdminUserInvitationRepository;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DB-free unit test that locks in the P0-6 wiring: {@code InvitationService.accept}
 * must call {@link PasswordPolicyValidator#validate} BEFORE setting the bcrypt hash.
 *
 * <p>Chosen over exercising the Oracle Testcontainers IT (AdminUserInvitationFlowIT)
 * because it is deterministic and runs without a database — the IT cannot run in this
 * environment (ORA-12541, no listener). It pins the regression that the validator
 * actually fires through the service, and that a too-short password aborts before the
 * password is hashed / the account activated.
 */
class InvitationServiceAcceptTest {

    private final AdminUserInvitationRepository invitationRepo = mock(AdminUserInvitationRepository.class);
    private final AdminUserRepository userRepo = mock(AdminUserRepository.class);
    private final MailSender mailSender = mock(MailSender.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final PasswordPolicyValidator passwordPolicyValidator = mock(PasswordPolicyValidator.class);

    private final InvitationService service = new InvitationService(
            invitationRepo, userRepo, mailSender, passwordEncoder, passwordPolicyValidator);

    private AdminUser stubLookup() {
        UUID userId = UUID.randomUUID();
        AdminUserInvitation inv = mock(AdminUserInvitation.class);
        when(inv.getAdminUserId()).thenReturn(userId);
        when(inv.isExpired()).thenReturn(false);
        when(inv.isAccepted()).thenReturn(false);
        when(inv.getExpiresAt()).thenReturn(Instant.now().plusSeconds(3600));
        when(invitationRepo.findByTokenHash(anyString())).thenReturn(Optional.of(inv));

        AdminUser user = mock(AdminUser.class);
        when(user.getEmail()).thenReturn("invitee@example.com");
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void accept_tooShortPassword_throws_andNeverHashes() {
        AdminUser user = stubLookup();
        doThrow(new IllegalArgumentException("password 가 정책 최소 길이(12)보다 짧습니다"))
                .when(passwordPolicyValidator).validate("short");

        assertThatThrownBy(() -> service.accept("inv_token", "short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("12");

        // The validator must short-circuit before any account mutation.
        verify(passwordPolicyValidator).validate("short");
        verify(user, never()).setBcryptHash(anyString());
        verify(user, never()).setStatus(anyString());
    }

    @Test
    void accept_validPassword_validatesBeforeHashing() {
        AdminUser user = stubLookup();
        when(passwordEncoder.encode("aVeryLongPassword1")).thenReturn("bcrypt$hash");

        service.accept("inv_token", "aVeryLongPassword1");

        // validate() must run before the password is hashed onto the user.
        InOrder order = inOrder(passwordPolicyValidator, user);
        order.verify(passwordPolicyValidator).validate("aVeryLongPassword1");
        order.verify(user).setBcryptHash("bcrypt$hash");
    }
}
