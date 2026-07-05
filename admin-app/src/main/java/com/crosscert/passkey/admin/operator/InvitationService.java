package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.core.entity.AdminUserInvitation;
import com.crosscert.passkey.core.mail.MailSender;
import com.crosscert.passkey.core.repository.AdminUserInvitationRepository;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.crosscert.passkey.core.repository.AdminUserTenantRepository;
import com.crosscert.passkey.core.util.CryptoUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class InvitationService {

    private static final Duration TOKEN_TTL = Duration.ofDays(7);
    private static final String URL_PREFIX = "/accept-invite?token=";

    private final AdminUserInvitationRepository invitationRepo;
    private final AdminUserRepository userRepo;
    private final AdminUserTenantRepository mappingRepo;
    private final MailSender mailSender;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final Environment env;

    @Value("${admin.invite.base-url:http://localhost:5173}")
    private String baseUrl;

    public InvitationService(AdminUserInvitationRepository invitationRepo,
                             AdminUserRepository userRepo,
                             AdminUserTenantRepository mappingRepo,
                             MailSender mailSender,
                             PasswordEncoder passwordEncoder,
                             Clock clock,
                             Environment env) {
        this.invitationRepo = invitationRepo;
        this.userRepo = userRepo;
        this.mappingRepo = mappingRepo;
        this.mailSender = mailSender;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.env = env;
    }

    /**
     * G11 fail-fast — prod profile must not boot with the localhost dev
     * default for admin.invite.base-url. Without this, a prod deployment
     * that forgets to set ADMIN_INVITE_BASE_URL boots successfully but
     * mails out invite/reset links pointing at http://localhost:5173 (the
     * operator's own loopback), which never reaches the real front-end —
     * a broken-link lockout that looks fine until an operator clicks it.
     */
    @PostConstruct
    void validateBaseUrlForProd() {
        BaseUrlValidation.assertNotLocalhostFallbackInProd(env, baseUrl, "admin.invite.base-url");
    }

    @Transactional
    public AdminUserDto.InvitationInfo createInvitation(UUID adminUserId, String invitedBy, String email) {
        String plaintext = CryptoUtils.randomToken("inv_");
        String tokenHash = CryptoUtils.sha256Hex(plaintext);
        String prefix = plaintext.substring(0, 8);

        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime expiresAt = now.plus(TOKEN_TTL);
        var inv = new AdminUserInvitation(adminUserId, tokenHash, prefix, invitedBy, now, expiresAt);
        invitationRepo.save(inv);

        String acceptUrl = baseUrl + URL_PREFIX + plaintext;
        try {
            String subject = "관리자 초대 — Passkey2";
            String body = String.format(
                    "초대자: %s<br>초대 수락 URL: <a href=\"%s\">%s</a><br>만료: %s",
                    invitedBy, acceptUrl, acceptUrl, expiresAt);
            mailSender.send(email, subject, body);
        } catch (Exception ignore) {
            // 메일 실패해도 invite 자체는 성공 — UI 의 plaintext 복사가 fallback
        }
        return new AdminUserDto.InvitationInfo(prefix, plaintext, acceptUrl, expiresAt);
    }

    @Transactional(readOnly = true)
    public AdminUserDto.InvitationCheck check(String plaintext) {
        var inv = lookupValid(plaintext, OffsetDateTime.now(clock));
        var user = userRepo.findById(inv.getAdminUserId())
                .orElseThrow(() -> new IllegalStateException("user not found"));
        List<UUID> tids = mappingRepo.findTenantIdsByAdminUserId(user.getId());
        return new AdminUserDto.InvitationCheck(
                user.getEmail(), user.getRole(), tids, inv.getExpiresAt());
    }

    @Transactional
    public void accept(String plaintext, String password) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        var inv = lookupValid(plaintext, now);
        // G09: conditional atomic UPDATE (accepted_at IS NULL -> now) instead
        // of read-check-then-dirty-checking-update. Two concurrent accept()
        // calls for the same token both pass lookupValid()'s in-memory
        // isAccepted() check before either commits; without this DB-level
        // predicate, both would proceed and the token would be consumed
        // twice (last-writer-wins password). acceptIfPending() serializes
        // the race: only the winner gets 1 row affected.
        int updated = invitationRepo.acceptIfPending(inv.getId(), now);
        if (updated == 0) {
            log.warn("invitation used: tokenPrefix={}", inv.getTokenPrefix());
            throw new IllegalStateException("Token already used");
        }
        var user = userRepo.findById(inv.getAdminUserId())
                .orElseThrow(() -> new IllegalStateException("user not found"));
        user.setBcryptHash(passwordEncoder.encode(password));
        user.setStatus("ACTIVE");
        // G03: invite() creates PENDING users with enabled=false (no password
        // set yet). Now that the invitee has set a password and the account
        // is being activated, restore ENABLED — the actual login gate — or
        // the user could never pass /admin/login despite a valid password.
        user.setEnabled(true);
        log.info("invitation accepted: email={} tokenPrefix={}",
                CryptoUtils.maskEmail(user.getEmail()), inv.getTokenPrefix());
    }

    private AdminUserInvitation lookupValid(String plaintext, OffsetDateTime now) {
        String hash = CryptoUtils.sha256Hex(plaintext);
        var inv = invitationRepo.findByTokenHash(hash)
                .orElseThrow(() -> {
                    // tokenPrefix is the first 8 chars of plaintext (matches
                    // the persisted prefix shape); never logs the full token.
                    String tp = plaintext == null || plaintext.length() < 8
                            ? "(short)"
                            : plaintext.substring(0, 8);
                    log.warn("invitation lookup failed: tokenPrefix={} reason=not-found", tp);
                    return new IllegalStateException("Invalid token");
                });
        if (inv.isExpired(now)) {
            log.warn("invitation expired: tokenPrefix={}", inv.getTokenPrefix());
            throw new IllegalStateException("Token expired");
        }
        if (inv.isAccepted()) {
            log.warn("invitation used: tokenPrefix={}", inv.getTokenPrefix());
            throw new IllegalStateException("Token already used");
        }
        return inv;
    }
}
