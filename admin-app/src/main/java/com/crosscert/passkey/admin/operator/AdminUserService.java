package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.entity.AdminUserTenant;
import com.crosscert.passkey.core.repository.AdminUserInvitationRepository;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.crosscert.passkey.core.repository.AdminUserTenantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class AdminUserService {

    private final AdminUserRepository userRepo;
    private final AdminUserInvitationRepository invitationRepo;
    private final InvitationService invitationService;
    private final AdminUserTenantRepository mappingRepo;
    private final Clock clock;

    public AdminUserService(AdminUserRepository userRepo,
                            AdminUserInvitationRepository invitationRepo,
                            InvitationService invitationService,
                            AdminUserTenantRepository mappingRepo,
                            Clock clock) {
        this.userRepo = userRepo;
        this.invitationRepo = invitationRepo;
        this.invitationService = invitationService;
        this.mappingRepo = mappingRepo;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AdminUserDto.View> list() {
        return userRepo.findAll().stream().map(this::toView).toList();
    }

    @Transactional
    public AdminUserDto.InviteResponse invite(AdminUserDto.InviteRequest req, String invitedBy) {
        if (!"PLATFORM_OPERATOR".equals(req.role()) && !"RP_ADMIN".equals(req.role())) {
            throw new IllegalArgumentException("Invalid role: " + req.role());
        }
        List<UUID> tenantIds = req.tenantIds() == null
                ? List.of() : req.tenantIds();
        if ("RP_ADMIN".equals(req.role()) && tenantIds.isEmpty()) {
            throw new IllegalArgumentException("RP_ADMIN requires at least one tenant");
        }
        if ("PLATFORM_OPERATOR".equals(req.role()) && !tenantIds.isEmpty()) {
            throw new IllegalArgumentException("PLATFORM_OPERATOR must not have tenant");
        }
        if (userRepo.findByEmail(req.email()).isPresent()) {
            throw new IllegalStateException("Email already exists: " + req.email());
        }

        AdminUser user = AdminUser.create();
        user.setEmail(req.email());
        user.setRole(req.role());
        user.setStatus("PENDING");
        user.setCreatedBy(invitedBy);
        AdminUser saved = userRepo.save(user);

        for (UUID tid : new LinkedHashSet<>(tenantIds)) {   // 중복 멱등
            mappingRepo.save(AdminUserTenant.of(saved.getId(), tid, invitedBy));
        }

        var inv = invitationService.createInvitation(saved.getId(), invitedBy, req.email());
        log.info("admin invite issued: emailMasked={} role={} tenantCount={}",
                mask(req.email()), req.role(), tenantIds.size());
        return new AdminUserDto.InviteResponse(toView(saved), inv);
    }

    @Transactional
    public void addTenant(UUID adminUserId, UUID tenantId, String actor) {
        AdminUser u = userRepo.findById(adminUserId)
                .orElseThrow(() -> new IllegalStateException("admin not found: " + adminUserId));
        if (!"RP_ADMIN".equals(u.getRole())) {
            throw new IllegalArgumentException("only RP_ADMIN can be mapped to a tenant");
        }
        if (mappingRepo.existsByAdminUserIdAndTenantId(adminUserId, tenantId)) {
            return; // 멱등
        }
        mappingRepo.save(AdminUserTenant.of(adminUserId, tenantId, actor));
        log.info("admin tenant added: adminId={} tenantId={} by={}", adminUserId, tenantId, mask(actor));
    }

    @Transactional
    public void removeTenant(UUID adminUserId, UUID tenantId, String actor) {
        AdminUser u = userRepo.findById(adminUserId)
                .orElseThrow(() -> new IllegalStateException("admin not found: " + adminUserId));
        if ("RP_ADMIN".equals(u.getRole()) && mappingRepo.countByAdminUserId(adminUserId) <= 1) {
            throw new IllegalStateException("cannot remove last tenant of RP_ADMIN");
        }
        mappingRepo.deleteByAdminUserIdAndTenantId(adminUserId, tenantId);
        log.info("admin tenant removed: adminId={} tenantId={} by={}", adminUserId, tenantId, mask(actor));
    }

    @Transactional
    public void suspend(UUID userId, String byUser) {
        AdminUser user = userRepo.findById(userId).orElseThrow();
        assertNotLockingOut(user, byUser, "suspend");
        user.setStatus("SUSPENDED");
        user.setSuspendedAt(OffsetDateTime.now(clock));
        user.setSuspendedBy(byUser);
        log.info("admin suspended: emailMasked={}", mask(user.getEmail()));
    }

    @Transactional
    public void activate(UUID userId) {
        AdminUser user = userRepo.findById(userId).orElseThrow();
        user.setStatus("ACTIVE");
        user.setSuspendedAt(null);
        user.setSuspendedBy(null);
        log.info("admin activated: emailMasked={}", mask(user.getEmail()));
    }

    @Transactional
    public AdminUserDto.InvitationInfo resendInvitation(UUID userId, String byUser, String email) {
        OffsetDateTime now = OffsetDateTime.now(clock);
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

    /** Masks an email to "a***@example.com" — never the full local part. */
    private static String mask(String email) {
        if (email == null) return "null";
        int at = email.indexOf('@');
        if (at <= 1) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }

    AdminUserDto.View toView(AdminUser u) {
        List<UUID> tids = mappingRepo.findTenantIdsByAdminUserId(u.getId());
        return new AdminUserDto.View(
                u.getId(), u.getEmail(), u.getRole(),
                u.getStatus() != null ? u.getStatus() : "ACTIVE",
                tids,
                u.getCreatedAt(), u.getLastLoginAt(),
                u.getSuspendedAt(), u.getCreatedBy(), u.isMfaEnabled());
    }
}
