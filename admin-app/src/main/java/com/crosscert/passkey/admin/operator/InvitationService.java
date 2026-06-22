package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.core.entity.AdminUserInvitation;
import com.crosscert.passkey.core.mail.MailSender;
import com.crosscert.passkey.core.repository.AdminUserInvitationRepository;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.crosscert.passkey.core.util.CryptoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
public class InvitationService {

    private static final Duration TOKEN_TTL = Duration.ofDays(7);
    private static final String URL_PREFIX = "/accept-invite?token=";

    private final AdminUserInvitationRepository invitationRepo;
    private final AdminUserRepository userRepo;
    private final MailSender mailSender;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Value("${admin.invite.base-url:http://localhost:5173}")
    private String baseUrl;

    public InvitationService(AdminUserInvitationRepository invitationRepo,
                             AdminUserRepository userRepo,
                             MailSender mailSender,
                             PasswordEncoder passwordEncoder,
                             Clock clock) {
        this.invitationRepo = invitationRepo;
        this.userRepo = userRepo;
        this.mailSender = mailSender;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
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
        return new AdminUserDto.InvitationCheck(
                user.getEmail(), user.getRole(), user.getTenantId(), inv.getExpiresAt());
    }

    @Transactional
    public void accept(String plaintext, String password) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        var inv = lookupValid(plaintext, now);
        var user = userRepo.findById(inv.getAdminUserId())
                .orElseThrow(() -> new IllegalStateException("user not found"));
        user.setBcryptHash(passwordEncoder.encode(password));
        user.setStatus("ACTIVE");
        inv.accept(now);
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
