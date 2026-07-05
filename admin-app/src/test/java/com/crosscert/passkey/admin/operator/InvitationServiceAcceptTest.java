package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.entity.AdminUserInvitation;
import com.crosscert.passkey.core.mail.MailSender;
import com.crosscert.passkey.core.repository.AdminUserInvitationRepository;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.crosscert.passkey.core.repository.AdminUserTenantRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import com.crosscert.passkey.core.config.KstTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DB-free unit test that locks in {@code InvitationService.accept}: a valid
 * invitation hashes the password onto the user and activates the account.
 *
 * <p>Chosen over exercising the Oracle Testcontainers IT (AdminUserInvitationFlowIT)
 * because it is deterministic and runs without a database — the IT cannot run in this
 * environment (ORA-12541, no listener).
 */
class InvitationServiceAcceptTest {

    private final AdminUserInvitationRepository invitationRepo = mock(AdminUserInvitationRepository.class);
    private final AdminUserRepository userRepo = mock(AdminUserRepository.class);
    private final AdminUserTenantRepository mappingRepo = mock(AdminUserTenantRepository.class);
    private final MailSender mailSender = mock(MailSender.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final Environment env = mock(Environment.class);

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-20T09:00:00Z"), KstTime.ZONE);

    private final InvitationService service = new InvitationService(
            invitationRepo, userRepo, mappingRepo, mailSender, passwordEncoder, clock, env);

    private AdminUser stubLookup() {
        UUID userId = UUID.randomUUID();
        AdminUserInvitation inv = mock(AdminUserInvitation.class);
        when(inv.getAdminUserId()).thenReturn(userId);
        when(inv.isExpired(any())).thenReturn(false);
        when(inv.isAccepted()).thenReturn(false);
        when(inv.getExpiresAt()).thenReturn(OffsetDateTime.now().plusSeconds(3600));
        when(invitationRepo.findByTokenHash(anyString())).thenReturn(Optional.of(inv));
        // G09: accept() now wins the race via a conditional atomic UPDATE
        // (acceptIfPending) rather than the in-memory isAccepted() check
        // alone. 1 = this call is the (only) winner.
        when(invitationRepo.acceptIfPending(any(), any())).thenReturn(1);

        AdminUser user = mock(AdminUser.class);
        when(user.getEmail()).thenReturn("invitee@example.com");
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void accept_hashesPasswordAndActivates() {
        AdminUser user = stubLookup();
        when(passwordEncoder.encode("aVeryLongPassword1")).thenReturn("bcrypt$hash");

        service.accept("inv_token", "aVeryLongPassword1");

        // The password is encoded onto the user before the account is activated.
        InOrder order = inOrder(user);
        order.verify(user).setBcryptHash("bcrypt$hash");
        order.verify(user).setStatus("ACTIVE");
    }

    @Test
    void accept_reenablesLoginForPendingUser() {
        // G03: invite() now creates PENDING users with enabled=false. Once
        // the invitee actually sets a password, the account must be
        // re-enabled — otherwise a successfully-onboarded user could never
        // pass the login gate.
        AdminUser user = stubLookup();
        when(passwordEncoder.encode(anyString())).thenReturn("bcrypt$hash");

        service.accept("inv_token", "aVeryLongPassword1");

        InOrder order = inOrder(user);
        order.verify(user).setBcryptHash("bcrypt$hash");
        order.verify(user).setEnabled(true);
    }

    @Test
    void accept_secondConcurrentCallLosesRaceAndIsRejected() {
        // G09: two concurrent accept() calls for the same token both pass the
        // in-memory lookupValid() check (isAccepted()==false) before either
        // commits. The conditional atomic UPDATE (acceptIfPending) is what
        // actually serializes them — simulate the second (losing) caller by
        // stubbing acceptIfPending to return 0 (already consumed by the
        // first caller's UPDATE).
        UUID userId = UUID.randomUUID();
        AdminUserInvitation inv = mock(AdminUserInvitation.class);
        when(inv.getAdminUserId()).thenReturn(userId);
        when(inv.isExpired(any())).thenReturn(false);
        when(inv.isAccepted()).thenReturn(false);
        when(inv.getExpiresAt()).thenReturn(OffsetDateTime.now().plusSeconds(3600));
        when(invitationRepo.findByTokenHash(anyString())).thenReturn(Optional.of(inv));
        when(invitationRepo.acceptIfPending(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.accept("inv_token", "anotherPassword1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already used");

        // The loser must never mutate the user (no password/status change).
        verify(userRepo, never()).findById(any());
    }
}
