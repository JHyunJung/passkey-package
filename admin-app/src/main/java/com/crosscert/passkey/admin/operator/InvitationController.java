package com.crosscert.passkey.admin.operator;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/api/invitations")
public class InvitationController {

    private final InvitationService service;

    public InvitationController(InvitationService service) {
        this.service = service;
    }

    @GetMapping("/{token}")
    public AdminUserDto.InvitationCheck check(@PathVariable String token) {
        return service.check(token);
    }

    @PostMapping("/{token}/accept")
    public void accept(@PathVariable String token,
                       @Valid @RequestBody AdminUserDto.AcceptRequest req) {
        service.accept(token, req.password());
    }
}
