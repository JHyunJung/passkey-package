package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.entity.AdminUserTenant;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.crosscert.passkey.core.repository.AdminUserTenantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class AdminUserService {

    private final AdminUserRepository userRepo;
    private final AdminUserTenantRepository mappingRepo;
    private final AuditLogService audit;
    private final Clock clock;

    public AdminUserService(AdminUserRepository userRepo,
                            AdminUserTenantRepository mappingRepo,
                            AuditLogService audit,
                            Clock clock) {
        this.userRepo = userRepo;
        this.mappingRepo = mappingRepo;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AdminUserDto.View> list() {
        List<AdminUser> users = userRepo.findAll();
        // G17: single IN-query for all mappings instead of one query per user
        // (F09 activity-feed findAllById batch pattern).
        List<UUID> userIds = users.stream().map(AdminUser::getId).toList();
        Map<UUID, List<UUID>> tenantIdsByUser = new HashMap<>();
        for (AdminUserTenant m : mappingRepo.findByAdminUserIdIn(userIds)) {
            tenantIdsByUser.computeIfAbsent(m.getAdminUserId(), k -> new ArrayList<>())
                    .add(m.getTenantId());
        }
        return users.stream()
                .map(u -> toView(u, tenantIdsByUser.getOrDefault(u.getId(), List.of())))
                .toList();
    }

    @Transactional
    public void addTenant(UUID adminUserId, UUID tenantId, UUID actorId, String actor) {
        AdminUser u = userRepo.findById(adminUserId)
                .orElseThrow(() -> new IllegalStateException("admin not found: " + adminUserId));
        if (!"RP_ADMIN".equals(u.getRole())) {
            throw new IllegalArgumentException("only RP_ADMIN can be mapped to a tenant");
        }
        if (mappingRepo.existsByAdminUserIdAndTenantId(adminUserId, tenantId)) {
            return; // 멱등
        }
        mappingRepo.save(AdminUserTenant.of(adminUserId, tenantId, actor));
        audit.append(new AuditAppendRequest(actorId, actor, "ADMIN_USER_TENANT_ADD",
                "ADMIN_USER", adminUserId.toString(), tenantId,
                Map.of("tenantId", tenantId.toString())));
        log.info("admin tenant added: adminId={} tenantId={} by={}", adminUserId, tenantId, mask(actor));
    }

    @Transactional
    public void removeTenant(UUID adminUserId, UUID tenantId, UUID actorId, String actor) {
        AdminUser u = userRepo.findById(adminUserId)
                .orElseThrow(() -> new IllegalStateException("admin not found: " + adminUserId));
        // G08: lock every mapping row for this admin BEFORE counting so a
        // concurrent removeTenant on a different tenant of the same admin
        // serializes against this one (write-skew fix — see repository
        // Javadoc for the full concurrency argument).
        List<AdminUserTenant> mappings = mappingRepo.findByAdminUserIdForUpdate(adminUserId);
        if ("RP_ADMIN".equals(u.getRole()) && mappings.size() <= 1) {
            throw new IllegalStateException("cannot remove last tenant of RP_ADMIN");
        }
        mappingRepo.deleteByAdminUserIdAndTenantId(adminUserId, tenantId);
        audit.append(new AuditAppendRequest(actorId, actor, "ADMIN_USER_TENANT_REMOVE",
                "ADMIN_USER", adminUserId.toString(), tenantId,
                Map.of("tenantId", tenantId.toString())));
        log.info("admin tenant removed: adminId={} tenantId={} by={}", adminUserId, tenantId, mask(actor));
    }

    @Transactional
    public void suspend(UUID userId, UUID actorId, String byUser) {
        AdminUser user = userRepo.findById(userId).orElseThrow();
        assertNotLockingOut(user, byUser, "suspend");
        user.setStatus("SUSPENDED");
        user.setSuspendedAt(OffsetDateTime.now(clock));
        user.setSuspendedBy(byUser);
        // G03: STATUS alone is not read by the login gate — DaoAuthenticationProvider's
        // DefaultPreAuthenticationChecks only consults isEnabled()/isAccountNonLocked().
        // Flip ENABLED so a suspended operator is actually rejected at /admin/login,
        // not just cosmetically flagged.
        user.setEnabled(false);
        audit.append(new AuditAppendRequest(actorId, byUser, "ADMIN_USER_SUSPEND",
                "ADMIN_USER", userId.toString(), null, Map.of()));
        log.info("admin suspended: emailMasked={}", mask(user.getEmail()));
    }

    @Transactional
    public void activate(UUID userId, UUID actorId, String byUser) {
        AdminUser user = userRepo.findById(userId).orElseThrow();
        user.setStatus("ACTIVE");
        user.setSuspendedAt(null);
        user.setSuspendedBy(null);
        user.setEnabled(true);
        audit.append(new AuditAppendRequest(actorId, byUser, "ADMIN_USER_ACTIVATE",
                "ADMIN_USER", userId.toString(), null, Map.of()));
        log.info("admin activated: emailMasked={}", mask(user.getEmail()));
    }

    private void assertNotLockingOut(AdminUser user, String byUser, String action) {
        if (user.getEmail().equals(byUser)) {
            throw new IllegalStateException("Cannot " + action + " yourself");
        }
        if ("PLATFORM_OPERATOR".equals(user.getRole())
                && "ACTIVE".equals(user.getStatus() != null ? user.getStatus() : "ACTIVE")) {
            // G06: lock every active-PLATFORM_OPERATOR row BEFORE counting so a
            // concurrent suspend of a different operator serializes against
            // this one instead of both observing a stale pre-suspend count
            // (write-skew fix — see repository Javadoc for the full argument).
            long activePoCount = userRepo.findActivePlatformOperatorsForUpdate().size();
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
        return toView(u, tids);
    }

    private AdminUserDto.View toView(AdminUser u, List<UUID> tids) {
        return new AdminUserDto.View(
                u.getId(), u.getEmail(), u.getRole(),
                u.getStatus() != null ? u.getStatus() : "ACTIVE",
                tids,
                u.getCreatedAt(), u.getLastLoginAt(),
                u.getSuspendedAt(), u.getCreatedBy(), u.isMfaEnabled());
    }
}
