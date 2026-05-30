package com.crosscert.passkey.admin.operator;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Self-service password reset 엔드포인트 (P1-6).
 *
 * <p>두 엔드포인트 모두 미인증(permitAll). request 는 항상 200(enumeration 방지).
 * confirm 실패는 PasswordResetService 가 IllegalArgumentException → 400 으로 매핑.
 */
@RestController
@RequestMapping("/admin/api/password-reset")
public class PasswordResetController {

    private final PasswordResetService service;

    public PasswordResetController(PasswordResetService service) {
        this.service = service;
    }

    @PostMapping("/request")
    public ResponseEntity<?> request(@RequestBody RequestBodyDto body) {
        String email = body == null ? null : body.email();
        if (email != null && !email.isBlank()) {
            service.request(email);
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/confirm")
    public ResponseEntity<?> confirm(@RequestBody ConfirmBody body) {
        service.confirm(body.token(), body.newPassword());
        return ResponseEntity.ok(Map.of("reset", true));
    }

    public record RequestBodyDto(String email) {}
    public record ConfirmBody(String token, String newPassword) {}
}
