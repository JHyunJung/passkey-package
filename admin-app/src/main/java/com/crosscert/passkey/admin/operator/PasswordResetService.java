package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.admin.policy.PasswordPolicyValidator;
import com.crosscert.passkey.core.entity.AdminPasswordResetToken;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.mail.MailSender;
import com.crosscert.passkey.core.repository.AdminPasswordResetTokenRepository;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Admin self-service password reset (P1-6).
 *
 * <p>InvitationService 패턴 복제 — sha-256 token_hash, MailSender, 1회용 토큰.
 * request 는 enumeration 방지를 위해 사용자 존재 여부와 무관하게 동일하게 동작
 * (없으면 조용히 no-op). confirm 은 PasswordPolicyValidator 재사용 + lockout 리셋.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final Duration TOKEN_TTL = Duration.ofHours(1);
    private static final SecureRandom RNG = new SecureRandom();
    private static final String URL_PREFIX = "/reset-password?token=";

    private final AdminPasswordResetTokenRepository tokens;
    private final AdminUserRepository users;
    private final MailSender mail;
    private final PasswordEncoder encoder;
    private final PasswordPolicyValidator policy;
    private final Clock clock;

    @Value("${admin.invite.base-url:http://localhost:5173}")
    private String baseUrl;

    public PasswordResetService(AdminPasswordResetTokenRepository tokens,
                                AdminUserRepository users,
                                MailSender mail,
                                PasswordEncoder encoder,
                                PasswordPolicyValidator policy,
                                Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.mail = mail;
        this.encoder = encoder;
        this.policy = policy;
        this.clock = clock;
    }

    @Transactional
    public void request(String email) {
        var userOpt = users.findByEmail(email);
        if (userOpt.isEmpty()) {
            log.info("password reset requested for unknown email: {}", maskEmail(email));
            return;
        }
        AdminUser user = userOpt.get();
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        String plaintext = "rst_" + hex(raw);
        String prefix = plaintext.substring(0, 8);
        Instant now = clock.instant();
        var tok = new AdminPasswordResetToken(
                user.getId(), sha256Hex(plaintext), prefix, now.plus(TOKEN_TTL), now);
        tokens.save(tok);

        String resetUrl = baseUrl + URL_PREFIX + plaintext;
        try {
            mail.send(email, "비밀번호 재설정 — Passkey2",
                    String.format("재설정 URL: <a href=\"%s\">%s</a><br>만료: %s",
                            resetUrl, resetUrl, tok.getExpiresAt()));
        } catch (Exception ignore) {
            // 메일 실패해도 토큰은 발급됨
        }
        log.info("password reset token issued: email={} tokenPrefix={}", maskEmail(email), prefix);
    }

    @Transactional
    public void confirm(String plaintext, String newPassword) {
        String hash = sha256Hex(plaintext);
        AdminPasswordResetToken tok = tokens.findByTokenHash(hash)
                .orElseThrow(() -> new IllegalArgumentException("invalid token"));
        Instant now = clock.instant();
        if (tok.isConsumed()) throw new IllegalArgumentException("token already used");
        if (tok.isExpired(now)) throw new IllegalArgumentException("token expired");

        AdminUser user = users.findById(tok.getAdminUserId())
                .orElseThrow(() -> new IllegalArgumentException("invalid token"));
        policy.validate(newPassword);
        user.setBcryptHash(encoder.encode(newPassword));
        user.recordSuccessfulLogin();
        // 동시 confirm race 는 의도적으로 용인: 토큰은 설계상 1회용이고 operator 가
        // 직접 시작하며, double-spend 시 최악이 last-writer-wins 비밀번호 재설정(가치 누출 없음).
        // recovery code(consume)와 달리 conditional update 를 쓰지 않는 이유.
        tok.consume(now);
        log.info("password reset confirmed: email={} tokenPrefix={}",
                maskEmail(user.getEmail()), tok.getTokenPrefix());
    }

    String hashForTest(String plaintext) { return sha256Hex(plaintext); }

    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) return "(unknown)";
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    private static String sha256Hex(String s) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
