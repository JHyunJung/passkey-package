package com.crosscert.passkey.app.api.v1.rp;

import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationFinishRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationFinishResponse;
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartResponse;
import com.crosscert.passkey.app.fido2.registration.RegistrationFinishService;
import com.crosscert.passkey.app.fido2.registration.RegistrationStartService;
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
    public RegistrationStartResponse start(@Valid @RequestBody RegistrationStartRequest req) {
        return start.start(req);
    }

    @PostMapping("/finish")
    public RegistrationFinishResponse finish(@Valid @RequestBody RegistrationFinishRequest req) {
        return finish.finish(req);
    }
}
