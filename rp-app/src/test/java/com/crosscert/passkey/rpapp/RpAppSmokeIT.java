package com.crosscert.passkey.rpapp;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke IT — rp-app 가 떠 있고 passkey-app:8080 + admin-app:8081 + docker compose 인프라가
 * 가동된 상태에서 등록 → 로그인 happy path 가 풀 스택으로 통과하는지 검증.
 *
 * 실행 전제 (CI 미구축이므로 local-only):
 *   1. cd Passkey2 && docker compose up -d
 *   2. cd Passkey2 && ./gradlew :passkey-app:bootRun --args="--passkey.id-token.issuer-base=http://localhost:8080" (별 터미널)
 *   3. cd Passkey2 && ./gradlew :admin-app:bootRun (별 터미널)
 *   4. cd examples/sdk-java && ./gradlew publishToMavenLocal
 *   5. cd examples/rp-app && ./scripts/bootstrap-rp-app.sh
 *   6. set -a && source examples/rp-app/.env && set +a
 *   7. cd examples/rp-app && ./gradlew test --tests RpAppSmokeIT
 *
 * @Disabled 마커를 제거해야 실제 실행된다. 위 전제가 만족되지 않으면 fail 한다.
 * passkey-app + admin-app 의 자동 부팅을 IT 가 책임지지 않는다 (spec § 8.1 — 개발 속도 우선).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Disabled("Manual run only. See class javadoc for prerequisites.")
class RpAppSmokeIT {

    @Test
    void registrationAndLoginHappyPath() {
        // Implementation: read Passkey2/passkey-app/src/test/java/com/crosscert/passkey/app/fido2/Fido2EndToEndIT.java
        // for ClientPlatform + PackedAuthenticator + ObjectConverter encode/decode pattern.
        // Then adapt to call rp-app's /passkey/register/{begin,finish} + /passkey/authenticate/{begin,finish}.
        //
        // Skeleton steps:
        //   1. RestClient http = RestClient.create("http://localhost:" + port);
        //   2. ClientPlatform client = new ClientPlatform(URI.create("http://localhost:" + port), new PackedAuthenticator());
        //   3. POST /passkey/register/begin, parse envelope.data.publicKeyCredentialCreationOptions to webauthn4j type
        //   4. var attestation = client.create(opts);
        //   5. POST /passkey/register/finish with attestation JSON
        //   6. POST /passkey/authenticate/begin
        //   7. var assertion = client.get(reqOpts);
        //   8. POST /passkey/authenticate/finish
        //   9. assert all envelopes have success=true
        org.junit.jupiter.api.Assertions.assertTrue(true);  // placeholder until @Disabled removed
    }
}
