package com.crosscert.passkey.admin.operator

import com.crosscert.passkey.admin.auth.AdminUserDetails
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** 원 Java 의 (AdminUserDetails) 캐스트 — 다른 principal 은 ClassCastException, null 은 NPE. */
private fun actorId(auth: Authentication): UUID = (auth.principal as AdminUserDetails).id

/**
 * 가입 요청·승인 엔드포인트.
 *
 * `POST /admin/api/signup-requests` 만 공개(permitAll, CSRF 제외)이고
 * 항상 202 를 돌려준다 — 계정 열거 방지. 나머지는 PLATFORM_OPERATOR 전용이라
 * 메서드 단위 @PreAuthorize 를 쓴다(클래스 단위면 공개 POST 까지 막힌다).
 * 응답은 AdminUserController 처럼 raw JSON(envelope 없음).
 */
@RestController
@RequestMapping("/admin/api/signup-requests")
class SignupRequestController(
    private val service: SignupRequestService,
) {

    /**
     * activated 는 PLATFORM_OPERATOR 자동 승인(QA 전용 설정)으로 계정이 바로 만들어졌을 때만 true.
     * 그 설정이 꺼져 있으면 항상 false 라 기존처럼 응답이 같다.
     */
    @PostMapping
    fun request(@Valid @RequestBody body: SignupRequestDto.Create): ResponseEntity<Map<String, Boolean>> {
        val activated = service.request(body)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(java.util.Map.of("accepted", true, "activated", activated))
    }

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun list(): List<SignupRequestDto.View> = service.list()

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun approve(
        @PathVariable id: UUID,
        @Valid @RequestBody body: SignupRequestDto.Approve,
        auth: Authentication,
    ): AdminUserDto.View = service.approve(id, body, actorId(auth), auth.name)

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun reject(@PathVariable id: UUID, auth: Authentication): ResponseEntity<Void> {
        service.reject(id, actorId(auth), auth.name)
        return ResponseEntity.noContent().build()
    }
}
