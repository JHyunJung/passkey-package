package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserInvitationRepository;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AdminUserService {

    private final AdminUserRepository userRepo;
    private final AdminUserInvitationRepository invitationRepo;
    private final InvitationService invitationService;
    private final Clock clock;

    public AdminUserService(AdminUserRepository userRepo,
                            AdminUserInvitationRepository invitationRepo,
                            InvitationService invitationService,
                            Clock clock) {
        this.userRepo = userRepo;
        this.invitationRepo = invitationRepo;
        this.invitationService = invitationService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AdminUserDto.View> list() {
        return userRepo.findAll().stream().map(AdminUserService::toView).toList();
    }

    @Transactional
    public AdminUserDto.InviteResponse invite(AdminUserDto.InviteRequest req, String invitedBy) {
        if (!"PLATFORM_OPERATOR".equals(req.role()) && !"RP_ADMIN".equals(req.role())) {
            throw new IllegalArgumentException("Invalid role: " + req.role());
        }
        if ("RP_ADMIN".equals(req.role()) && req.tenantId() == null) {
            throw new IllegalArgumentException("RP_ADMIN requires tenantId");
        }
        if ("PLATFORM_OPERATOR".equals(req.role()) && req.tenantId() != null) {
            throw new IllegalArgumentException("PLATFORM_OPERATOR must not have tenantId");
        }
        if (userRepo.findByEmail(req.email()).isPresent()) {
            throw new IllegalStateException("Email already exists: " + req.email());
        }

        AdminUser user = AdminUser.create();
        user.setEmail(req.email());
        user.setRole(req.role());
        user.setStatus("PENDING");
        user.setCreatedBy(invitedBy);
        if (req.tenantId() != null) user.setTenantId(req.tenantId());
        AdminUser saved = userRepo.save(user);

        var inv = invitationService.createInvitation(saved.getId(), invitedBy, req.email());
        return new AdminUserDto.InviteResponse(toView(saved), inv);
    }

    @Transactional
    public void suspend(UUID userId, String byUser) {
        AdminUser user = userRepo.findById(userId).orElseThrow();
        assertNotLockingOut(user, byUser, "suspend");
        user.setStatus("SUSPENDED");
        user.setSuspendedAt(clock.instant());
        user.setSuspendedBy(byUser);
    }

    @Transactional
    public void activate(UUID userId) {
        AdminUser user = userRepo.findById(userId).orElseThrow();
        user.setStatus("ACTIVE");
        user.setSuspendedAt(null);
        user.setSuspendedBy(null);
    }

    @Transactional
    public AdminUserDto.InvitationInfo resendInvitation(UUID userId, String byUser, String email) {
        Instant now = clock.instant();
        // Resend revokes any prior pending invitations — the new token replaces them.
        var existing = invitationRepo.findByAdminUserIdAndAcceptedAtIsNull(userId);
        for (var inv : existing) {
            inv.recordResend(now);
            inv.markRevoked(now);
        }
        return invitationService.createInvitation(userId, byUser, email);
    }

    private void assertNotLockingOut(AdminUser user, String byUser, String action) {
        if (user.getEmail().equals(byUser)) {
            throw new IllegalStateException("Cannot " + action + " yourself");
        }
        if ("PLATFORM_OPERATOR".equals(user.getRole())
                && "ACTIVE".equals(user.getStatus() != null ? user.getStatus() : "ACTIVE")) {
            long activePoCount = userRepo.findAll().stream()
                    .filter(u -> "PLATFORM_OPERATOR".equals(u.getRole())
                            && "ACTIVE".equals(u.getStatus() != null ? u.getStatus() : "ACTIVE"))
                    .count();
            if (activePoCount <= 1) {
                throw new IllegalStateException("Cannot " + action + " the last active PLATFORM_OPERATOR");
            }
        }
    }

    static AdminUserDto.View toView(AdminUser u) {
        return new AdminUserDto.View(
                u.getId(), u.getEmail(), u.getRole(),
                u.getStatus() != null ? u.getStatus() : "ACTIVE",
                u.getTenantId(),
                u.getCreatedAt(), u.getLastLoginAt(),
                u.getSuspendedAt(), u.getCreatedBy());
    }
}
