package com.crosscert.passkey.app.fido2.challenge

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64

@Component
class ChallengeIssuer {

    private val rng = SecureRandom()

    fun newChallengeBytes(): ByteArray = ByteArray(CHALLENGE_BYTES).also(rng::nextBytes)

    fun newToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(newChallengeBytes())

    private companion object {
        const val CHALLENGE_BYTES = 32
    }
}
