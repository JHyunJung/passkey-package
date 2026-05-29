package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.core.entity.AdminUserInvitation;
import com.crosscert.passkey.core.mail.MailSender;
import com.crosscert.passkey.core.repository.AdminUserInvitationRepository;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    private static final Duration TOKEN_TTL = Duration.ofDays(7);
    private static final SecureRandom RNG = new SecureRandom();
    private static final String URL_PREFIX = "/accept-invite?token=";

    /** Mask email — first letter + *** + domain. Mirrors AdminSecurityConfig.maskEmail. */
    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) return "(unknown)";
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }

    private final AdminUserInvitationRepository invitationRepo;
    private final AdminUserRepository userRepo;
    private final MailSender mailSender;
    private final PasswordEncoder passwordEncoder;
    private final com.crosscert.passkey.admin.policy.PasswordPolicyValidator passwordPolicyValidator;

    @Value("${admin.invite.base-url:http://localhost:5173}")
    private String baseUrl;

    public InvitationService(AdminUserInvitationRepository invitationRepo,
                             AdminUserRepository userRepo,
                             MailSender mailSender,
                             PasswordEncoder passwordEncoder,
                             com.crosscert.passkey.admin.policy.PasswordPolicyValidator passwordPolicyValidator) {
        this.invitationRepo = invitationRepo;
        this.userRepo = userRepo;
        this.mailSender = mailSender;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicyValidator = passwordPolicyValidator;
    }

    @Transactional
    public AdminUserDto.InvitationInfo createInvitation(UUID adminUserId, String invitedBy, String email) {
        byte[] tokenBytes = new byte[32];
        RNG.nextBytes(tokenBytes);
        String plaintext = "inv_" + hex(tokenBytes);
        String tokenHash = sha256Hex(plaintext);
        String prefix = plaintext.substring(0, 8);

        Instant expiresAt = Instant.now().plus(TOKEN_TTL);
        var inv = new AdminUserInvitation(adminUserId, tokenHash, prefix, invitedBy, expiresAt);
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
        var inv = lookupValid(plaintext);
        var user = userRepo.findById(inv.getAdminUserId())
                .orElseThrow(() -> new IllegalStateException("user not found"));
        return new AdminUserDto.InvitationCheck(
                user.getEmail(), user.getRole(), user.getTenantId(), inv.getExpiresAt());
    }

    @Transactional
    public void accept(String plaintext, String password) {
        var inv = lookupValid(plaintext);
        var user = userRepo.findById(inv.getAdminUserId())
                .orElseThrow(() -> new IllegalStateException("user not found"));
        passwordPolicyValidator.validate(password);
        user.setBcryptHash(passwordEncoder.encode(password));
        user.setStatus("ACTIVE");
        inv.accept();
        log.info("invitation accepted: email={} tokenPrefix={}",
                maskEmail(user.getEmail()), inv.getTokenPrefix());
    }

    private AdminUserInvitation lookupValid(String plaintext) {
        String hash = sha256Hex(plaintext);
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
        if (inv.isExpired()) {
            log.warn("invitation expired: tokenPrefix={}", inv.getTokenPrefix());
            throw new IllegalStateException("Token expired");
        }
        if (inv.isAccepted()) {
            log.warn("invitation used: tokenPrefix={}", inv.getTokenPrefix());
            throw new IllegalStateException("Token already used");
        }
        return inv;
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    private static String sha256Hex(String s) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            return hex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
