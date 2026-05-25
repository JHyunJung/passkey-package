package com.crosscert.passkey.app.api.v1.rp;

import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationFinishRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationFinishResponse;
import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationStartRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationStartResponse;
import com.crosscert.passkey.app.fido2.authentication.AuthenticationFinishService;
import com.crosscert.passkey.app.fido2.authentication.AuthenticationStartService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rp/authentication")
public class AuthenticationController {

    private final AuthenticationStartService start;
    private final AuthenticationFinishService finish;

    public AuthenticationController(AuthenticationStartService start,
                                    AuthenticationFinishService finish) {
        this.start = start;
        this.finish = finish;
    }

    @PostMapping("/start")
    public AuthenticationStartResponse start(@Valid @RequestBody AuthenticationStartRequest req) {
        return start.start(req);
    }

    @PostMapping("/finish")
    public AuthenticationFinishResponse finish(@Valid @RequestBody AuthenticationFinishRequest req) {
        return finish.finish(req);
    }
}
