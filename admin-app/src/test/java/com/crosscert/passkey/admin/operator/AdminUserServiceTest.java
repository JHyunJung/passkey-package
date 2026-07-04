package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.entity.AdminUserTenant;
import com.crosscert.passkey.core.repository.AdminUserInvitationRepository;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.crosscert.passkey.core.repository.AdminUserTenantRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    private final AuditLogService audit = mock(AuditLogService.class);
    private final Clock clock = Clock.systemUTC();

    private final AdminUserService service = new AdminUserService(
            userRepo, invitationRepo, invitationService, mappingRepo, audit, clock);

    @Test
    void rpAdminInviteRequiresAtLeastOneTenant() {
        when(userRepo.findByEmail(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.invite(
                new AdminUserDto.InviteRequest("rp@x.com", "RP_ADMIN", List.of()),
                UUID.randomUUID(), "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RP_ADMIN requires at least one tenant");
    }

    @Test
    void platformOperatorInviteMustHaveNoTenant() {
        when(userRepo.findByEmail(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.invite(
                new AdminUserDto.InviteRequest("po@x.com", "PLATFORM_OPERATOR",
                        List.of(UUID.randomUUID())), UUID.randomUUID(), "alice"))
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
        when(mappingRepo.findByAdminUserIdForUpdate(adminId))
                .thenReturn(List.of(AdminUserTenant.of(adminId, tenantId, "alice")));

        assertThatThrownBy(() -> service.removeTenant(adminId, tenantId, UUID.randomUUID(), "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot remove last tenant of RP_ADMIN");
    }

    @Test
    void removeTenantLocksMappingRowsBeforeCounting() {
        // G08: count-then-delete must be serialized by a pessimistic lock on
        // the admin's mapping rows (findByAdminUserIdForUpdate), not the
        // lock-free countByAdminUserId — otherwise two concurrent removals
        // of distinct tenants both observe count=2 and both proceed
        // (write-skew leaving 0 tenants).
        UUID adminId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID otherTenantId = UUID.randomUUID();
        AdminUser user = AdminUser.create();
        user.setRole("RP_ADMIN");
        when(userRepo.findById(adminId)).thenReturn(Optional.of(user));
        when(mappingRepo.findByAdminUserIdForUpdate(adminId)).thenReturn(List.of(
                AdminUserTenant.of(adminId, tenantId, "alice"),
                AdminUserTenant.of(adminId, otherTenantId, "alice")));

        service.removeTenant(adminId, tenantId, UUID.randomUUID(), "alice");

        verify(mappingRepo, times(1)).findByAdminUserIdForUpdate(adminId);
        verify(mappingRepo, never()).countByAdminUserId(any());
        verify(mappingRepo).deleteByAdminUserIdAndTenantId(adminId, tenantId);
    }

    @Test
    void suspendDisablesLoginAndAppendsAuditEntry() {
        // G03 + G04: suspend must flip ENABLED=false (the actual login gate)
        // and append a tamper-evident audit row — not just flip STATUS.
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        AdminUser actor = AdminUser.create();
        actor.setEmail("alice@x.com");
        actor.setRole("PLATFORM_OPERATOR");
        AdminUser target = AdminUser.create();
        target.setEmail("bob@x.com");
        target.setRole("RP_ADMIN");
        when(userRepo.findById(targetId)).thenReturn(Optional.of(target));

        service.suspend(targetId, actorId, "alice@x.com");

        assertThat(target.getStatus()).isEqualTo("SUSPENDED");
        assertThat(target.isEnabled()).isFalse();
        verify(audit).append(any(AuditAppendRequest.class));
    }

    @Test
    void activateReenablesLogin() {
        UUID targetId = UUID.randomUUID();
        AdminUser target = AdminUser.create();
        target.setEmail("bob@x.com");
        target.setStatus("SUSPENDED");
        target.setEnabled(false);
        when(userRepo.findById(targetId)).thenReturn(Optional.of(target));

        service.activate(targetId, UUID.randomUUID(), "alice@x.com");

        assertThat(target.getStatus()).isEqualTo("ACTIVE");
        assertThat(target.isEnabled()).isTrue();
        verify(audit).append(any(AuditAppendRequest.class));
    }

    @Test
    void suspendLastActivePlatformOperatorLocksBeforeCounting() {
        // G06: the last-PO lockout guard must count under a pessimistic lock
        // on the active-PO row set (findActivePlatformOperatorsForUpdate),
        // not a lock-free findAll() scan — otherwise two concurrent suspends
        // of distinct operators both observe count=2 and both proceed.
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        AdminUser actor = AdminUser.create();
        actor.setEmail("alice@x.com");
        actor.setRole("PLATFORM_OPERATOR");
        AdminUser target = AdminUser.create();
        target.setEmail("bob@x.com");
        target.setRole("PLATFORM_OPERATOR");
        when(userRepo.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepo.findActivePlatformOperatorsForUpdate()).thenReturn(List.of(actor, target));

        service.suspend(targetId, actorId, "alice@x.com");

        verify(userRepo, times(1)).findActivePlatformOperatorsForUpdate();
        verify(userRepo, never()).findAll();
    }

    @Test
    void suspendLastActivePlatformOperatorRejected() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        AdminUser target = AdminUser.create();
        target.setEmail("bob@x.com");
        target.setRole("PLATFORM_OPERATOR");
        when(userRepo.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepo.findActivePlatformOperatorsForUpdate()).thenReturn(List.of(target));

        assertThatThrownBy(() -> service.suspend(targetId, actorId, "someoneelse@x.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("last active PLATFORM_OPERATOR");
    }

    @Test
    void inviteAppendsAuditEntry() {
        when(userRepo.findByEmail(any())).thenReturn(Optional.empty());
        // AdminUser.getId() is populated by Hibernate's @UuidGenerator on
        // persist — a mocked repo.save() never runs that lifecycle, so a
        // plain AdminUser.create() has a null id. Mock the entity itself to
        // stub getId() to a fixed UUID (invite() needs saved.getId() for
        // both the audit targetId and the mapping rows).
        UUID savedId = UUID.randomUUID();
        AdminUser saved = mock(AdminUser.class);
        when(saved.getId()).thenReturn(savedId);
        when(saved.getEmail()).thenReturn("new@x.com");
        when(saved.getRole()).thenReturn("PLATFORM_OPERATOR");
        when(saved.getStatus()).thenReturn("PENDING");
        when(saved.isMfaEnabled()).thenReturn(false);
        when(userRepo.save(any())).thenReturn(saved);
        when(invitationService.createInvitation(any(), any(), any()))
                .thenReturn(new AdminUserDto.InvitationInfo("prefix12", "plain", "url", null));

        service.invite(new AdminUserDto.InviteRequest("new@x.com", "PLATFORM_OPERATOR", List.of()),
                UUID.randomUUID(), "alice@x.com");

        verify(audit).append(any(AuditAppendRequest.class));
    }

    @Test
    void inviteCreatesDisabledPendingUser() {
        // G03: PENDING accounts (no password set yet) must not satisfy the
        // ENABLED login gate. AdminUser.create() defaults enabledFlag="Y",
        // so invite() must explicitly disable the freshly-created user
        // before it is persisted.
        when(userRepo.findByEmail(any())).thenReturn(Optional.empty());
        // AdminUser.getId() is populated by Hibernate's @UuidGenerator on
        // persist — a mocked repo.save() never runs that lifecycle. Return a
        // mock standing in for the persisted row (stubbed getId() only) so
        // invite()'s post-save code (mapping rows, audit) doesn't NPE, while
        // capturing the *actual* pre-save AdminUser passed into save() to
        // assert on its real enabled-flag state.
        UUID savedId = UUID.randomUUID();
        AdminUser savedStandIn = mock(AdminUser.class);
        when(savedStandIn.getId()).thenReturn(savedId);
        when(savedStandIn.getEmail()).thenReturn("new@x.com");
        when(savedStandIn.getRole()).thenReturn("PLATFORM_OPERATOR");
        when(savedStandIn.getStatus()).thenReturn("PENDING");
        when(savedStandIn.isMfaEnabled()).thenReturn(false);
        when(userRepo.save(any())).thenReturn(savedStandIn);
        when(invitationService.createInvitation(any(), any(), any()))
                .thenReturn(new AdminUserDto.InvitationInfo("prefix12", "plain", "url", null));

        service.invite(new AdminUserDto.InviteRequest("new@x.com", "PLATFORM_OPERATOR", List.of()),
                UUID.randomUUID(), "alice@x.com");

        ArgumentCaptor<AdminUser> captor = ArgumentCaptor.forClass(AdminUser.class);
        verify(userRepo).save(captor.capture());
        assertThat(captor.getValue().isEnabled()).isFalse();
    }

    @Test
    void addTenantAppendsAuditEntry() {
        UUID adminId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        AdminUser user = AdminUser.create();
        user.setRole("RP_ADMIN");
        when(userRepo.findById(adminId)).thenReturn(Optional.of(user));
        when(mappingRepo.existsByAdminUserIdAndTenantId(adminId, tenantId)).thenReturn(false);

        service.addTenant(adminId, tenantId, UUID.randomUUID(), "alice@x.com");

        verify(audit).append(any(AuditAppendRequest.class));
    }

    @Test
    void removeTenantAppendsAuditEntry() {
        UUID adminId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID otherTenantId = UUID.randomUUID();
        AdminUser user = AdminUser.create();
        user.setRole("RP_ADMIN");
        when(userRepo.findById(adminId)).thenReturn(Optional.of(user));
        when(mappingRepo.findByAdminUserIdForUpdate(adminId)).thenReturn(List.of(
                AdminUserTenant.of(adminId, tenantId, "alice"),
                AdminUserTenant.of(adminId, otherTenantId, "alice")));

        service.removeTenant(adminId, tenantId, UUID.randomUUID(), "alice@x.com");

        verify(audit).append(any(AuditAppendRequest.class));
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
