package com.crosscert.passkey.app.fido2.challenge;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
public class ChallengeIssuer {

    private static final int CHALLENGE_BYTES = 32;
    private final SecureRandom rng = new SecureRandom();

    public byte[] newChallengeBytes() {
        byte[] buf = new byte[CHALLENGE_BYTES];
        rng.nextBytes(buf);
        return buf;
    }

    public String newToken() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(newChallengeBytes());
    }
}
