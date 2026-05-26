package com.crosscert.passkey.admin.keymgmt;

import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.crosscert.passkey.core.repository.SigningKeyRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/api/keys")
public class KeyMgmtController {

    private final SigningKeyRepository repo;
    private final KeyRotationService rotation;
    private final AdminUserRepository admins;

    public KeyMgmtController(SigningKeyRepository repo,
                             KeyRotationService rotation,
                             AdminUserRepository admins) {
        this.repo = repo;
        this.rotation = rotation;
        this.admins = admins;
    }

    @GetMapping
    public ApiResponse<KeyMgmtDto.KeyList> list() {
        var keys = repo.findAll().stream()
                .map(KeyMgmtDto.SigningKeyView::from)
                .toList();
        return ApiResponse.ok(new KeyMgmtDto.KeyList(keys));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/rotate")
    public ApiResponse<KeyMgmtDto.RotateResponse> rotate(Authentication auth) {
        long actorId = admins.findByEmail(auth.getName()).orElseThrow().getId();
        var result = rotation.rotate(actorId, auth.getName());
        return ApiResponse.ok(new KeyMgmtDto.RotateResponse(result.oldKid(), result.newKid()));
    }
}
