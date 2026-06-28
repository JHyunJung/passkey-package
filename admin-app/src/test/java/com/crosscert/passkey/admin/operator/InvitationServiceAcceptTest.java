package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.entity.AdminUserInvitation;
import com.crosscert.passkey.core.mail.MailSender;
import com.crosscert.passkey.core.repository.AdminUserInvitationRepository;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.crosscert.passkey.core.repository.AdminUserTenantRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import com.crosscert.passkey.core.config.KstTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
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

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-20T09:00:00Z"), KstTime.ZONE);

    private final InvitationService service = new InvitationService(
            invitationRepo, userRepo, mappingRepo, mailSender, passwordEncoder, clock);

    private AdminUser stubLookup() {
        UUID userId = UUID.randomUUID();
        AdminUserInvitation inv = mock(AdminUserInvitation.class);
        when(inv.getAdminUserId()).thenReturn(userId);
        when(inv.isExpired(any())).thenReturn(false);
        when(inv.isAccepted()).thenReturn(false);
        when(inv.getExpiresAt()).thenReturn(OffsetDateTime.now().plusSeconds(3600));
        when(invitationRepo.findByTokenHash(anyString())).thenReturn(Optional.of(inv));

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
}
