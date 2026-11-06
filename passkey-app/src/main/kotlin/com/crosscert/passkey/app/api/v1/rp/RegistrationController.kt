package com.crosscert.passkey.app.api.v1.rp

import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationFinishRequest
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationFinishResponse
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartRequest
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartResponse
import com.crosscert.passkey.app.fido2.registration.RegistrationFinishService
import com.crosscert.passkey.app.fido2.registration.RegistrationStartService
import com.crosscert.passkey.core.api.ApiResponse
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// @RequestBody 파라미터는 non-null 로 둔다 — Kotlin nullable 이면 Spring 이 파라미터를
// optional 로 보고 빈/`null` 바디를 거부하지 않아 원 Java(required=true)와 동작이 달라진다.
@RestController
@RequestMapping("/api/v1/rp/registration")
class RegistrationController(
    private val startService: RegistrationStartService,
    private val finishService: RegistrationFinishService,
) {

    @PostMapping("/start")
    fun start(@Valid @RequestBody req: RegistrationStartRequest): ApiResponse<RegistrationStartResponse> =
        ApiResponse.ok(startService.start(req))

    @PostMapping("/finish")
    fun finish(@Valid @RequestBody req: RegistrationFinishRequest): ApiResponse<RegistrationFinishResponse> =
        ApiResponse.ok(finishService.finish(req))
}
