package com.crosscert.passkey.app.api.v1.rp;

import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationFinishRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationFinishResponse;
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartResponse;
import com.crosscert.passkey.app.fido2.registration.RegistrationFinishService;
import com.crosscert.passkey.app.fido2.registration.RegistrationStartService;
import com.crosscert.passkey.core.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rp/registration")
public class RegistrationController {

    private final RegistrationStartService start;
    private final RegistrationFinishService finish;

    public RegistrationController(RegistrationStartService start,
                                  RegistrationFinishService finish) {
        this.start = start;
        this.finish = finish;
    }

    @PostMapping("/start")
    public ApiResponse<RegistrationStartResponse> start(
            @Valid @RequestBody RegistrationStartRequest req) {
        return ApiResponse.ok(start.start(req));
    }

    @PostMapping("/finish")
    public ApiResponse<RegistrationFinishResponse> finish(
            @Valid @RequestBody RegistrationFinishRequest req) {
        return ApiResponse.ok(finish.finish(req));
    }
}
