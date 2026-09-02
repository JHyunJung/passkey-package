package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.core.entity.AdminSignupRequest;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.entity.AdminUserTenant;
import com.crosscert.passkey.core.mail.MailSender;
import com.crosscert.passkey.core.repository.AdminSignupRequestRepository;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.crosscert.passkey.core.repository.AdminUserTenantRepository;
import com.crosscert.passkey.core.util.CryptoUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 어드민 가입 요청·승인 흐름.
 *
 * <p>request 는 공개 엔드포인트에서 호출된다. 계정 열거를 막기 위해 이메일 존재
 * 여부·대기 상한 도달 여부와 무관하게 예외 없이 반환하고, 호출측은 항상 같은
 * 응답을 준다. 저장하지 않는 경우는 마스킹 이메일로 WARN 만 남긴다.
 *
 * <p>approve/reject 는 PLATFORM_OPERATOR 전용. 요청 행 삭제를 건수 반환 쿼리로
 * 먼저 실행해 두 관리자의 동시 처리를 DB 에서 직렬화한다(0건이면 409).
 */
@Slf4j
@Service
public class SignupRequestService {

    /** 승인 대기 상한. 도달하면 새 요청을 조용히 받지 않는다(폭주 방지). */
    static final int MAX_PENDING = 100;

    private final AdminSignupRequestRepository requests;
    private final AdminUserRepository users;
    private final AdminUserTenantRepository mappings;
    private final AuditLogService audit;
    private final MailSender mail;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final Environment env;

    /** 승인 메일의 로그인 URL 앞부분. PasswordResetService 와 같은 속성을 읽는다. */
    @Value("${admin.invite.base-url:http://localhost:5173}")
    private String baseUrl;

    public SignupRequestService(AdminSignupRequestRepository requests,
                                AdminUserRepository users,
                                AdminUserTenantRepository mappings,
                                AuditLogService audit,
                                MailSender mail,
                                PasswordEncoder encoder,
                                Clock clock,
                                Environment env) {
        this.requests = requests;
        this.users = users;
        this.mappings = mappings;
        this.audit = audit;
        this.mail = mail;
        this.encoder = encoder;
        this.clock = clock;
        this.env = env;
    }

    /** G11 fail-fast — prod 에서 localhost 기본값으로 뜨면 승인 메일 링크가 깨진다. */
    @PostConstruct
    void validateBaseUrlForProd() {
        BaseUrlValidation.assertNotLocalhostFallbackInProd(env, baseUrl, "admin.invite.base-url");
    }

    /**
     * 의도적으로 @Transactional 을 붙이지 않는다 — save() 의 UNIQUE 위반(동시 요청)을
     * 여기서 잡아 삼키려면, 예외가 트랜잭션을 rollback-only 로 만들지 않아야 한다.
     * 각 리포지토리 호출은 자체 트랜잭션으로 돈다.
     */
    public void request(SignupRequestDto.Create req) {
        String email = normalize(req.email());
        String masked = CryptoUtils.maskEmail(email);

        if (users.findByEmail(email).isPresent()) {
            log.warn("signup request skipped: email={} reason=already-admin", masked);
            return;
        }
        if (requests.existsByEmail(email)) {
            log.warn("signup request skipped: email={} reason=already-pending", masked);
            return;
        }
        if (requests.count() >= MAX_PENDING) {
            log.warn("signup request skipped: email={} reason=pending-cap max={}", masked, MAX_PENDING);
            return;
        }

        String reason = req.reason() == null || req.reason().isBlank() ? null : req.reason().trim();
        var row = new AdminSignupRequest(email, encoder.encode(req.password()), reason, OffsetDateTime.now(clock));
        try {
            requests.save(row);
        } catch (DataIntegrityViolationException e) {
            // 같은 이메일의 동시 요청 — 먼저 들어간 쪽이 남는다. 응답은 동일.
            log.warn("signup request skipped: email={} reason=concurrent-duplicate", masked);
            return;
        }
        log.info("signup request accepted: email={}", masked);
    }

    @Transactional(readOnly = true)
    public List<SignupRequestDto.View> list() {
        return requests.findAllByOrderByRequestedAtAsc().stream()
                .map(r -> new SignupRequestDto.View(r.getId(), r.getEmail(), r.getReason(), r.getRequestedAt()))
                .toList();
    }

    @Transactional
    public AdminUserDto.View approve(UUID requestId, SignupRequestDto.Approve body,
                                     UUID actorId, String actorEmail) {
        // 1. 역할 규칙 — 초대 흐름의 검증을 그대로 옮김.
        if (!"PLATFORM_OPERATOR".equals(body.role()) && !"RP_ADMIN".equals(body.role())) {
            throw new IllegalArgumentException("Invalid role: " + body.role());
        }
        List<UUID> tenantIds = body.tenantIds() == null ? List.of() : body.tenantIds();
        if (tenantIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("tenantIds must not contain null");
        }
        if ("RP_ADMIN".equals(body.role()) && tenantIds.isEmpty()) {
            throw new IllegalArgumentException("RP_ADMIN requires at least one tenant");
        }
        if ("PLATFORM_OPERATOR".equals(body.role()) && !tenantIds.isEmpty()) {
            throw new IllegalArgumentException("PLATFORM_OPERATOR must not have tenant");
        }

        // 2. 요청 조회
        AdminSignupRequest req = requests.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "signup request not found"));
        String email = req.getEmail();
        String bcryptHash = req.getBcryptHash();

        // 3. 그 사이 같은 이메일 계정이 생겼으면 409
        if (users.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        }

        // 4. 경합 판정 — 반드시 admin_user INSERT 보다 먼저. deleteIfPresent 는
        //    clearAutomatically 라 영속성 컨텍스트를 비운다; 뒤에 만드는 엔티티는
        //    무관하지만 앞에 save() 만 해둔 엔티티는 유실된다.
        if (requests.deleteIfPresent(requestId) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "signup request already handled");
        }

        // 5. 계정 생성 — 승인 즉시 로그인 가능(ACTIVE + enabled). 해시는 요청의 것을 복사.
        AdminUser user = AdminUser.create();
        user.setEmail(email);
        user.setBcryptHash(bcryptHash);
        user.setRole(body.role());
        user.setStatus("ACTIVE");
        user.setEnabled(true);
        user.setCreatedBy(actorEmail);
        // saveAndFlush (not save) — a plain save() defers the INSERT (and its
        // UNIQUE(email) violation) to commit, which happens *outside* this
        // method after the exception-translation below has already been
        // skipped, surfacing as an uncaught 500. Flushing here forces the
        // violation to throw inside the try, where it can still be mapped
        // to 409; the exception propagating out of @Transactional still
        // rolls the whole approval back.
        AdminUser saved;
        try {
            saved = users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        }

        List<UUID> distinctTenants = List.copyOf(new LinkedHashSet<>(tenantIds));
        for (UUID tid : distinctTenants) {
            mappings.save(AdminUserTenant.of(saved.getId(), tid, actorEmail));
        }

        // 6. 감사
        audit.append(new AuditAppendRequest(actorId, actorEmail, "ADMIN_SIGNUP_APPROVE",
                "ADMIN_USER", saved.getId().toString(), null,
                Map.of("role", body.role(), "tenantCount", distinctTenants.size())));
        log.info("signup request approved: email={} role={} tenantCount={} by={}",
                CryptoUtils.maskEmail(email), body.role(), distinctTenants.size(), CryptoUtils.maskEmail(actorEmail));

        // 7. 결과 메일 — 실패해도 승인은 성공
        String loginUrl = baseUrl + "/admin";
        sendQuietly(email, "관리자 계정 승인 — Passkey2",
                String.format("가입 요청이 승인되었습니다.<br>로그인: <a href=\"%s\">%s</a>", loginUrl, loginUrl));

        return new AdminUserDto.View(
                saved.getId(), saved.getEmail(), saved.getRole(),
                saved.getStatus() != null ? saved.getStatus() : "ACTIVE",
                distinctTenants,
                saved.getCreatedAt(), saved.getLastLoginAt(),
                saved.getSuspendedAt(), saved.getCreatedBy(), saved.isMfaEnabled());
    }

    @Transactional
    public void reject(UUID requestId, UUID actorId, String actorEmail) {
        AdminSignupRequest req = requests.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "signup request not found"));
        String email = req.getEmail();

        if (requests.deleteIfPresent(requestId) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "signup request already handled");
        }

        audit.append(new AuditAppendRequest(actorId, actorEmail, "ADMIN_SIGNUP_REJECT",
                "ADMIN_SIGNUP_REQUEST", requestId.toString(), null, Map.of()));
        log.info("signup request rejected: email={} by={}",
                CryptoUtils.maskEmail(email), CryptoUtils.maskEmail(actorEmail));

        sendQuietly(email, "관리자 계정 요청 거절 — Passkey2",
                "가입 요청이 거절되었습니다. 문의는 플랫폼 운영자에게 하세요.");
    }

    private void sendQuietly(String to, String subject, String body) {
        try {
            mail.send(to, subject, body);
        } catch (Exception e) {
            log.warn("signup result mail failed: email={} cause={}", CryptoUtils.maskEmail(to), e.toString());
        }
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
