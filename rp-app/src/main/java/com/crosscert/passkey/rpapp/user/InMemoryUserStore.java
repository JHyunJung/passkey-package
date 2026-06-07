package com.crosscert.passkey.rpapp.user;

import com.crosscert.passkey.rpapp.common.exception.BusinessException;
import com.crosscert.passkey.rpapp.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class InMemoryUserStore {
    private final ConcurrentMap<String, RpAppUser> byHandle   = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String>       byUsername = new ConcurrentHashMap<>();
    private final SecureRandom rng = new SecureRandom();

    /** username 으로 새 userHandle (32B base64url) 발급 + pending 상태로 저장. 중복이면 USERNAME_TAKEN. */
    public String createPending(String username, String displayName) {
        byte[] raw = new byte[32];
        rng.nextBytes(raw);
        String userHandle = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        // putIfAbsent 로 username 을 원자적으로 예약. 동시 호출 시 두 번째는 non-null 반환 → 거부.
        if (byUsername.putIfAbsent(username, userHandle) != null) {
            throw new BusinessException(ErrorCode.USERNAME_TAKEN);
        }
        RpAppUser user = new RpAppUser(userHandle, username, displayName, Instant.now(), null);
        byHandle.put(userHandle, user);
        return userHandle;
    }

    /** registration/finish 성공 후 credentialId 채워서 확정. */
    public void confirmRegistration(String userHandle, String credentialId) {
        byHandle.computeIfPresent(userHandle, (k, u) ->
                new RpAppUser(u.userHandle(), u.username(), u.displayName(), u.createdAt(), credentialId));
    }

    public Optional<RpAppUser> findByUserHandle(String userHandle) {
        return Optional.ofNullable(byHandle.get(userHandle));
    }

    public Optional<RpAppUser> findByUsername(String username) {
        String handle = byUsername.get(username);
        return handle == null ? Optional.empty() : findByUserHandle(handle);
    }
}
