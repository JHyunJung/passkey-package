package com.crosscert.passkey.app.fido2.challenge

import com.crosscert.passkey.app.fido2.support.toBase64Url
import org.springframework.stereotype.Component
import java.security.SecureRandom

private const val CHALLENGE_BYTES = 32

@Component
class ChallengeIssuer {

    private val rng = SecureRandom()

    fun newChallengeBytes(): ByteArray = ByteArray(CHALLENGE_BYTES).also(rng::nextBytes)

    fun newToken(): String = newChallengeBytes().toBase64Url()
}
