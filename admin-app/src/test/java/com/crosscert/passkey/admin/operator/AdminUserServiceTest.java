package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserInvitationRepository;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.crosscert.passkey.core.repository.AdminUserTenantRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminUserServiceTest {

    private final AdminUserRepository userRepo = mock(AdminUserRepository.class);
    private final AdminUserInvitationRepository invitationRepo = mock(AdminUserInvitationRepository.class);
    private final InvitationService invitationService = mock(InvitationService.class);
    private final AdminUserTenantRepository mappingRepo = mock(AdminUserTenantRepository.class);
    private final Clock clock = Clock.systemUTC();

    private final AdminUserService service = new AdminUserService(
            userRepo, invitationRepo, invitationService, mappingRepo, clock);

    @Test
    void rpAdminInviteRequiresAtLeastOneTenant() {
        when(userRepo.findByEmail(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.invite(
                new AdminUserDto.InviteRequest("rp@x.com", "RP_ADMIN", List.of()), "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RP_ADMIN requires at least one tenant");
    }

    @Test
    void platformOperatorInviteMustHaveNoTenant() {
        when(userRepo.findByEmail(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.invite(
                new AdminUserDto.InviteRequest("po@x.com", "PLATFORM_OPERATOR",
                        List.of(UUID.randomUUID())), "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PLATFORM_OPERATOR must not have tenant");
    }

    @Test
    void removeLastTenantOfRpAdminRejected() {
        UUID adminId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        AdminUser user = AdminUser.create();
        user.setRole("RP_ADMIN");
        when(userRepo.findById(adminId)).thenReturn(Optional.of(user));
        when(mappingRepo.countByAdminUserId(adminId)).thenReturn(1L);

        assertThatThrownBy(() -> service.removeTenant(adminId, tenantId, "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot remove last tenant of RP_ADMIN");
    }
}
