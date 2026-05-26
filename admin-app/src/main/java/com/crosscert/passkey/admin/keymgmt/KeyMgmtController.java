package com.crosscert.passkey.admin.keymgmt;

import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.crosscert.passkey.core.repository.SigningKeyRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
    public KeyMgmtDto.KeyList list() {
        var keys = repo.findAll().stream()
                .map(KeyMgmtDto.SigningKeyView::from)
                .toList();
        return new KeyMgmtDto.KeyList(keys);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/rotate")
    public KeyMgmtDto.RotateResponse rotate(Authentication auth) {
        long actorId = admins.findByEmail(auth.getName()).orElseThrow().getId();
        try {
            var result = rotation.rotate(actorId, auth.getName());
            return new KeyMgmtDto.RotateResponse(result.oldKid(), result.newKid());
        } catch (RotationConflictException e) {
            // lease conflict → 409 Conflict; other IllegalStateException bubbles to 500
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }
}
