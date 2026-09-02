package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.admin.auth.AdminUserDetails;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 가입 요청·승인 엔드포인트.
 *
 * <p>{@code POST /admin/api/signup-requests} 만 공개(permitAll, CSRF 제외)이고
 * 항상 202 를 돌려준다 — 계정 열거 방지. 나머지는 PLATFORM_OPERATOR 전용이라
 * 메서드 단위 @PreAuthorize 를 쓴다(클래스 단위면 공개 POST 까지 막힌다).
 * 응답은 AdminUserController 처럼 raw JSON(envelope 없음).
 */
@RestController
@RequestMapping("/admin/api/signup-requests")
public class SignupRequestController {

    private final SignupRequestService service;

    public SignupRequestController(SignupRequestService service) {
        this.service = service;
    }

    private static UUID actorId(Authentication auth) {
        return ((AdminUserDetails) auth.getPrincipal()).getId();
    }

    @PostMapping
    public ResponseEntity<Map<String, Boolean>> request(@Valid @RequestBody SignupRequestDto.Create body) {
        service.request(body);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("accepted", true));
    }

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    public List<SignupRequestDto.View> list() {
        return service.list();
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    public AdminUserDto.View approve(@PathVariable UUID id,
                                     @Valid @RequestBody SignupRequestDto.Approve body,
                                     Authentication auth) {
        return service.approve(id, body, actorId(auth), auth.getName());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    public ResponseEntity<Void> reject(@PathVariable UUID id, Authentication auth) {
        service.reject(id, actorId(auth), auth.getName());
        return ResponseEntity.noContent().build();
    }
}
