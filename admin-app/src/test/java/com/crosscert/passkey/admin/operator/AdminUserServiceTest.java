package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.entity.AdminUserTenant;
import com.crosscert.passkey.core.repository.AdminUserInvitationRepository;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.crosscert.passkey.core.repository.AdminUserTenantRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void listBatchesTenantMappingsInsteadOfPerUserQueries() {
        // G17: list() must load mappings for all admin users via a single
        // IN-query (findByAdminUserIdIn), never via the per-user
        // findTenantIdsByAdminUserId (which would be N+1 for N admins).
        // AdminUser.getId() is populated by Hibernate's @UuidGenerator on
        // persist — mock the entity to give each user a distinct, stable id.
        UUID u1Id = UUID.randomUUID();
        UUID u2Id = UUID.randomUUID();
        AdminUser u1 = mock(AdminUser.class);
        when(u1.getId()).thenReturn(u1Id);
        when(u1.getEmail()).thenReturn("a@x.com");
        when(u1.getRole()).thenReturn("RP_ADMIN");
        when(u1.getStatus()).thenReturn("ACTIVE");
        AdminUser u2 = mock(AdminUser.class);
        when(u2.getId()).thenReturn(u2Id);
        when(u2.getEmail()).thenReturn("b@x.com");
        when(u2.getRole()).thenReturn("RP_ADMIN");
        when(u2.getStatus()).thenReturn("ACTIVE");
        when(userRepo.findAll()).thenReturn(List.of(u1, u2));

        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        when(mappingRepo.findByAdminUserIdIn(any()))
                .thenReturn(List.of(
                        AdminUserTenant.of(u1Id, tenantA, "alice"),
                        AdminUserTenant.of(u2Id, tenantB, "alice")));

        List<AdminUserDto.View> views = service.list();

        assertThat(views).hasSize(2);
        assertThat(views.get(0).tenantIds()).containsExactly(tenantA);
        assertThat(views.get(1).tenantIds()).containsExactly(tenantB);
        verify(mappingRepo, times(1)).findByAdminUserIdIn(any());
        verify(mappingRepo, never()).findTenantIdsByAdminUserId(any());
    }
}
