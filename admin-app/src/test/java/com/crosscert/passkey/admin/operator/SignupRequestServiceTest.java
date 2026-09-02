package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.core.config.KstTime;
import com.crosscert.passkey.core.entity.AdminSignupRequest;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.entity.AdminUserTenant;
import com.crosscert.passkey.core.mail.MailSender;
import com.crosscert.passkey.core.repository.AdminSignupRequestRepository;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.crosscert.passkey.core.repository.AdminUserTenantRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SignupRequestServiceTest {

    private final AdminSignupRequestRepository requests = mock(AdminSignupRequestRepository.class);
    private final AdminUserRepository users = mock(AdminUserRepository.class);
    private final AdminUserTenantRepository mappings = mock(AdminUserTenantRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final MailSender mail = mock(MailSender.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final Environment env = mock(Environment.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-03T01:00:00Z"), KstTime.ZONE);

    private final SignupRequestService service = new SignupRequestService(
            requests, users, mappings, audit, mail, encoder, clock, env);

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String ACTOR = "alice@crosscert.com";

    private AdminSignupRequest pending(UUID id, String email) {
        AdminSignupRequest r = mock(AdminSignupRequest.class);
        when(r.getId()).thenReturn(id);
        when(r.getEmail()).thenReturn(email);
        when(r.getBcryptHash()).thenReturn("$2a$12$stored");
        when(r.getReason()).thenReturn("reason");
        when(r.getRequestedAt()).thenReturn(OffsetDateTime.now(clock));
        return r;
    }

    /** userRepo.save() 가 돌려줄 "저장된" 계정 — id 는 persist 시 채워지므로 mock. */
    private AdminUser savedUser(UUID id, String email, String role) {
        AdminUser u = mock(AdminUser.class);
        when(u.getId()).thenReturn(id);
        when(u.getEmail()).thenReturn(email);
        when(u.getRole()).thenReturn(role);
        when(u.getStatus()).thenReturn("ACTIVE");
        when(u.isMfaEnabled()).thenReturn(false);
        return u;
    }

    // ── request ────────────────────────────────────────────────────────────

    @Test
    void request_newEmail_savesHashedRequest() {
        when(users.findByEmail("new@x.com")).thenReturn(Optional.empty());
        when(requests.existsByEmail("new@x.com")).thenReturn(false);
        when(requests.count()).thenReturn(0L);
        when(encoder.encode("password-12chars")).thenReturn("$2a$12$hashed");

        service.request(new SignupRequestDto.Create("New@X.com ", "password-12chars", "RP 담당"));

        ArgumentCaptor<AdminSignupRequest> cap = ArgumentCaptor.forClass(AdminSignupRequest.class);
        verify(requests).save(cap.capture());
        assertThat(cap.getValue().getEmail()).as("소문자·trim 정규화").isEqualTo("new@x.com");
        assertThat(cap.getValue().getBcryptHash()).isEqualTo("$2a$12$hashed");
        assertThat(cap.getValue().getReason()).isEqualTo("RP 담당");
        assertThat(cap.getValue().getRequestedAt()).isEqualTo(OffsetDateTime.now(clock));
    }

    @Test
    void request_emailAlreadyAnAdminUser_silentlySkips() {
        when(users.findByEmail("alice@crosscert.com")).thenReturn(Optional.of(AdminUser.create()));

        assertThatCode(() -> service.request(
                new SignupRequestDto.Create("alice@crosscert.com", "password-12chars", null)))
                .doesNotThrowAnyException();

        verify(requests, never()).save(any());
    }

    @Test
    void request_emailAlreadyPending_silentlySkips() {
        when(users.findByEmail("dup@x.com")).thenReturn(Optional.empty());
        when(requests.existsByEmail("dup@x.com")).thenReturn(true);

        service.request(new SignupRequestDto.Create("dup@x.com", "password-12chars", null));

        verify(requests, never()).save(any());
    }

    @Test
    void request_pendingCapReached_silentlySkips() {
        when(users.findByEmail("cap@x.com")).thenReturn(Optional.empty());
        when(requests.existsByEmail("cap@x.com")).thenReturn(false);
        when(requests.count()).thenReturn((long) SignupRequestService.MAX_PENDING);

        service.request(new SignupRequestDto.Create("cap@x.com", "password-12chars", null));

        verify(requests, never()).save(any());
        verify(encoder, never()).encode(anyString());
    }

    @Test
    void request_uniqueViolationOnConcurrentInsert_isSwallowed() {
        when(users.findByEmail("race@x.com")).thenReturn(Optional.empty());
        when(requests.existsByEmail("race@x.com")).thenReturn(false);
        when(requests.count()).thenReturn(0L);
        when(encoder.encode(anyString())).thenReturn("h");
        when(requests.save(any())).thenThrow(new DataIntegrityViolationException("uq_admin_signup_request_email"));

        assertThatCode(() -> service.request(
                new SignupRequestDto.Create("race@x.com", "password-12chars", null)))
                .doesNotThrowAnyException();
    }

    // ── approve ────────────────────────────────────────────────────────────

    @Test
    void approve_rpAdminWithTenant_createsActiveUserMappingAuditAndMail() {
        UUID reqId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID newUserId = UUID.randomUUID();
        AdminSignupRequest pendingReq = pending(reqId, "new@x.com");
        AdminUser savedUser = savedUser(newUserId, "new@x.com", "RP_ADMIN");
        when(requests.findById(reqId)).thenReturn(Optional.of(pendingReq));
        when(users.findByEmail("new@x.com")).thenReturn(Optional.empty());
        when(requests.deleteIfPresent(reqId)).thenReturn(1);
        when(users.save(any())).thenReturn(savedUser);

        AdminUserDto.View view = service.approve(reqId,
                new SignupRequestDto.Approve("RP_ADMIN", List.of(tenantId, tenantId)), ACTOR_ID, ACTOR);

        ArgumentCaptor<AdminUser> userCap = ArgumentCaptor.forClass(AdminUser.class);
        verify(users).save(userCap.capture());
        AdminUser created = userCap.getValue();
        assertThat(created.getEmail()).isEqualTo("new@x.com");
        assertThat(created.getBcryptHash()).as("요청의 해시를 그대로 복사").isEqualTo("$2a$12$stored");
        assertThat(created.getRole()).isEqualTo("RP_ADMIN");
        assertThat(created.getStatus()).isEqualTo("ACTIVE");
        assertThat(created.isEnabled()).isTrue();
        assertThat(created.getCreatedBy()).isEqualTo(ACTOR);

        ArgumentCaptor<AdminUserTenant> mapCap = ArgumentCaptor.forClass(AdminUserTenant.class);
        verify(mappings).save(mapCap.capture());   // 중복 tenantId 는 1건으로 접힘
        assertThat(mapCap.getValue().getAdminUserId()).isEqualTo(newUserId);
        assertThat(mapCap.getValue().getTenantId()).isEqualTo(tenantId);

        ArgumentCaptor<AuditAppendRequest> auditCap = ArgumentCaptor.forClass(AuditAppendRequest.class);
        verify(audit).append(auditCap.capture());
        assertThat(auditCap.getValue().action()).isEqualTo("ADMIN_SIGNUP_APPROVE");
        assertThat(auditCap.getValue().targetType()).isEqualTo("ADMIN_USER");
        assertThat(auditCap.getValue().targetId()).isEqualTo(newUserId.toString());
        assertThat(auditCap.getValue().payload()).containsEntry("role", "RP_ADMIN").containsEntry("tenantCount", 1);

        verify(mail).send(eq("new@x.com"), anyString(), anyString());
        assertThat(view.tenantIds()).containsExactly(tenantId);
    }

    @Test
    void approve_rpAdminWithoutTenant_rejected400() {
        UUID reqId = UUID.randomUUID();
        assertThatThrownBy(() -> service.approve(reqId,
                new SignupRequestDto.Approve("RP_ADMIN", List.of()), ACTOR_ID, ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RP_ADMIN requires at least one tenant");
        verify(requests, never()).deleteIfPresent(any());
    }

    @Test
    void approve_platformOperatorWithTenant_rejected400() {
        UUID reqId = UUID.randomUUID();
        assertThatThrownBy(() -> service.approve(reqId,
                new SignupRequestDto.Approve("PLATFORM_OPERATOR", List.of(UUID.randomUUID())), ACTOR_ID, ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PLATFORM_OPERATOR must not have tenant");
    }

    @Test
    void approve_unknownRole_rejected400() {
        assertThatThrownBy(() -> service.approve(UUID.randomUUID(),
                new SignupRequestDto.Approve("ROOT", null), ACTOR_ID, ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid role");
    }

    @Test
    void approve_missingRequest_404() {
        UUID reqId = UUID.randomUUID();
        when(requests.findById(reqId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(reqId,
                new SignupRequestDto.Approve("PLATFORM_OPERATOR", List.of()), ACTOR_ID, ACTOR))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void approve_emailNowExistsInAdminUser_409() {
        UUID reqId = UUID.randomUUID();
        AdminSignupRequest pendingReq = pending(reqId, "dup@x.com");
        when(requests.findById(reqId)).thenReturn(Optional.of(pendingReq));
        when(users.findByEmail("dup@x.com")).thenReturn(Optional.of(AdminUser.create()));

        assertThatThrownBy(() -> service.approve(reqId,
                new SignupRequestDto.Approve("PLATFORM_OPERATOR", List.of()), ACTOR_ID, ACTOR))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(users, never()).save(any());
    }

    @Test
    void approve_alreadyHandledByConcurrentAdmin_409_andNoUserCreated() {
        UUID reqId = UUID.randomUUID();
        AdminSignupRequest pendingReq = pending(reqId, "race@x.com");
        when(requests.findById(reqId)).thenReturn(Optional.of(pendingReq));
        when(users.findByEmail("race@x.com")).thenReturn(Optional.empty());
        when(requests.deleteIfPresent(reqId)).thenReturn(0);

        assertThatThrownBy(() -> service.approve(reqId,
                new SignupRequestDto.Approve("PLATFORM_OPERATOR", List.of()), ACTOR_ID, ACTOR))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(users, never()).save(any());
        verify(audit, never()).append(any());
    }

    @Test
    void approve_mailFailure_doesNotFailApproval() {
        UUID reqId = UUID.randomUUID();
        AdminSignupRequest pendingReq = pending(reqId, "m@x.com");
        AdminUser savedUser = savedUser(UUID.randomUUID(), "m@x.com", "PLATFORM_OPERATOR");
        when(requests.findById(reqId)).thenReturn(Optional.of(pendingReq));
        when(users.findByEmail("m@x.com")).thenReturn(Optional.empty());
        when(requests.deleteIfPresent(reqId)).thenReturn(1);
        when(users.save(any())).thenReturn(savedUser);
        doThrow(new RuntimeException("smtp down")).when(mail).send(anyString(), anyString(), anyString());

        assertThatCode(() -> service.approve(reqId,
                new SignupRequestDto.Approve("PLATFORM_OPERATOR", List.of()), ACTOR_ID, ACTOR))
                .doesNotThrowAnyException();
        verify(audit).append(any());
    }

    // ── reject ─────────────────────────────────────────────────────────────

    @Test
    void reject_deletesAuditsAndMails() {
        UUID reqId = UUID.randomUUID();
        AdminSignupRequest pendingReq = pending(reqId, "no@x.com");
        when(requests.findById(reqId)).thenReturn(Optional.of(pendingReq));
        when(requests.deleteIfPresent(reqId)).thenReturn(1);

        service.reject(reqId, ACTOR_ID, ACTOR);

        ArgumentCaptor<AuditAppendRequest> cap = ArgumentCaptor.forClass(AuditAppendRequest.class);
        verify(audit).append(cap.capture());
        assertThat(cap.getValue().action()).isEqualTo("ADMIN_SIGNUP_REJECT");
        assertThat(cap.getValue().targetType()).isEqualTo("ADMIN_SIGNUP_REQUEST");
        assertThat(cap.getValue().targetId()).isEqualTo(reqId.toString());
        verify(mail).send(eq("no@x.com"), anyString(), anyString());
    }

    @Test
    void reject_alreadyHandled_409() {
        UUID reqId = UUID.randomUUID();
        AdminSignupRequest pendingReq = pending(reqId, "no@x.com");
        when(requests.findById(reqId)).thenReturn(Optional.of(pendingReq));
        when(requests.deleteIfPresent(reqId)).thenReturn(0);

        assertThatThrownBy(() -> service.reject(reqId, ACTOR_ID, ACTOR))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(audit, never()).append(any());
    }

    @Test
    void reject_missingRequest_404() {
        UUID reqId = UUID.randomUUID();
        when(requests.findById(reqId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reject(reqId, ACTOR_ID, ACTOR))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── list ───────────────────────────────────────────────────────────────

    @Test
    void list_mapsToViewWithoutHash() {
        UUID id = UUID.randomUUID();
        AdminSignupRequest pendingReq = pending(id, "l@x.com");
        when(requests.findAllByOrderByRequestedAtAsc()).thenReturn(List.of(pendingReq));

        List<SignupRequestDto.View> views = service.list();

        assertThat(views).hasSize(1);
        assertThat(views.get(0).id()).isEqualTo(id);
        assertThat(views.get(0).email()).isEqualTo("l@x.com");
        assertThat(views.get(0).reason()).isEqualTo("reason");
    }
}
