package com.crosscert.passkey.samplerp.user;

import com.crosscert.passkey.samplerp.common.exception.BusinessException;
import com.crosscert.passkey.samplerp.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class InMemoryUserStore {
    private final ConcurrentMap<String, SampleRpUser> byHandle   = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String>       byUsername = new ConcurrentHashMap<>();
    private final SecureRandom rng = new SecureRandom();

    /** username 으로 새 userHandle (32B base64url) 발급 + pending 상태로 저장. 중복이면 USERNAME_TAKEN. */
    public String createPending(String username, String displayName) {
        if (byUsername.containsKey(username)) {
            throw new BusinessException(ErrorCode.USERNAME_TAKEN);
        }
        byte[] raw = new byte[32];
        rng.nextBytes(raw);
        String userHandle = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        SampleRpUser user = new SampleRpUser(userHandle, username, displayName, Instant.now(), null);
        byHandle.put(userHandle, user);
        byUsername.put(username, userHandle);
        return userHandle;
    }

    /** registration/finish 성공 후 credentialId 채워서 확정. */
    public void confirmRegistration(String userHandle, String credentialId) {
        byHandle.computeIfPresent(userHandle, (k, u) ->
                new SampleRpUser(u.userHandle(), u.username(), u.displayName(), u.createdAt(), credentialId));
    }

    public Optional<SampleRpUser> findByUserHandle(String userHandle) {
        return Optional.ofNullable(byHandle.get(userHandle));
    }

    public Optional<SampleRpUser> findByUsername(String username) {
        String handle = byUsername.get(username);
        return handle == null ? Optional.empty() : findByUserHandle(handle);
    }
}
