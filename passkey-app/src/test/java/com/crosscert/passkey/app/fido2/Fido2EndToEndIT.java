package com.crosscert.passkey.app.fido2;

import com.crosscert.passkey.app.support.PasskeyAppItSupport;
import com.crosscert.passkey.core.tenant.TenantContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import com.webauthn4j.data.client.Origin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 acceptance gate (T26). Boots the full passkey-app context
 * against a Testcontainers Oracle XE 21 (with the V1–V8 migrations
 * applied by Flyway on context start) and a Testcontainers Redis 7,
 * then exercises the FIDO2 RP API end-to-end over real HTTP.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>{@link #happyPath()} — register + authenticate, then verify the
 *       ID Token against {@code /.well-known/jwks.json}.</li>
 *   <li>{@link #crossTenantIsolation()} — credential registered under
 *       T_A is invisible to T_B's /authentication/start.</li>
 *   <li>{@link #missingApiKey()} — no X-API-Key header → 401.</li>
 *   <li>{@link #wrongApiKey()} — right prefix, wrong secret → 401.</li>
 *   <li>{@link #expiredChallenge()} — manually deleted Redis challenge
 *       key → /finish returns 400.</li>
 *   <li>{@link #signCountReplay()} — resubmitted assertion body fails
 *       the strict-monotonic signCount check.</li>
 *   <li>{@link #rateLimit429()} — &gt;300 calls to /authentication/start
 *       within a minute eventually returns 429.</li>
 *   <li>{@link #jwksAlgRS256()} — JWKS document advertises alg=RS256,
 *       use=sig, with a kid present.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class Fido2EndToEndIT {

    // ------------------------------------------------------------
    // Containers
    // ------------------------------------------------------------

    // 컨테이너 설정·부트스트랩·프로퍼티 등록 절차는 PasskeyAppItSupport 공용
    // (bootstrap-schema.sql 을 SYS 로 실행 → 런타임 DataSource 는 PSK_APP_RUNTIME_USER,
    // Flyway 는 application-test.yml 의 spring.flyway.user=PSK_APP_OWNER).
    // 컨테이너 인스턴스와 수명주기는 이 클래스가 소유한다.
    @org.testcontainers.junit.jupiter.Container
    static final OracleContainer ORACLE = PasskeyAppItSupport.oracleContainer();

    @org.testcontainers.junit.jupiter.Container
    static final GenericContainer<?> REDIS = PasskeyAppItSupport.redisContainer();

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry reg) throws Exception {
        PasskeyAppItSupport.bootstrapAndRegister(reg, ORACLE, REDIS);
    }

    // ------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------

    @LocalServerPort
    int port;

    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired StringRedisTemplate redis;
    @Autowired RedisConnectionFactory redisFactory;

    /**
     * Independent PSK_APP_ADMIN_USER pool used ONLY by seed, cleanup and
     * verification queries (PasskeyAppItSupport.TenantSeeder). The primary
     * Spring DataSource logs in as PSK_APP_RUNTIME_USER (SELECT-only on
     * {@code tenant}), so seeding needs PSK_APP_ADMIN's full DML.
     */
    private static PasskeyAppItSupport.TenantSeeder seeder;
    private static JdbcTemplate adminJdbc;

    private RestTemplate http;

    // Phase 6: tenant IDs are UUIDs stored as RAW(16). Fixed deterministic
    // UUIDs are used for seeding so test data is reproducible and the
    // JWT audience assertion can compare against a known value.
    private static final UUID TENANT_A_UUID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TENANT_B_UUID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final String TENANT_A_HEX =
            TENANT_A_UUID.toString().replace("-", "");
    private static final String TENANT_B_HEX =
            TENANT_B_UUID.toString().replace("-", "");

    private static final String SLUG_A = "acme";
    private static final String SLUG_B = "beta";
    private static final String PREFIX_A = "pk_aaaaaaaa";
    private static final String PREFIX_B = "pk_bbbbbbbb";
    private static final String SECRET_A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAA1";
    private static final String SECRET_B = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBB2";
    // Standard userHandle (16 random bytes base64url-encoded) — the
    // server decodes it back to bytes via Base64.getUrlDecoder.
    private static final String USER_HANDLE_A =
            Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[]{
                    1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
    // Origin must match the seeded allowed_origins JSON value below.
    // ApiKeyAuthFilter / OncePerRequestFilter sees the inbound URL's
    // host:port — for MockMvc-free RestTemplate calls into a randomly
    // assigned port that's "http://localhost:<port>", which webauthn4j's
    // CollectedClientData.origin field will reflect. Seed the same
    // value so the server's parseRegistrationResponseJSON accepts it.
    private String origin() {
        return "http://localhost:" + port;
    }

    // ------------------------------------------------------------
    // Seed / cleanup
    // ------------------------------------------------------------

    @BeforeEach
    void seed() {
        TenantContextHolder.clear();
        if (seeder == null) {
            seeder = new PasskeyAppItSupport.TenantSeeder(ORACLE, "fido2-it-admin-pool");
            adminJdbc = seeder.jdbc();
        }

        // Clear out any state lingering from a previous scenario before
        // seeding anew (FK-aware order, incl. ceremony_event and
        // admin_user_tenant — see TenantSeeder.wipe()).
        seeder.wipe();

        seeder.seedTenant(TENANT_A_HEX, SLUG_A, "Tenant A", origin(), PREFIX_A, encoder.encode(SECRET_A));
        seeder.seedTenant(TENANT_B_HEX, SLUG_B, "Tenant B", origin(), PREFIX_B, encoder.encode(SECRET_B));

        http = new RestTemplate();
        http.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse res) {
                // Disable automatic 4xx/5xx → exception so individual
                // scenarios can assert non-2xx status codes directly.
                return false;
            }
        });
    }

    @AfterAll
    static void closeAdminPool() {
        // codex P2: close the static admin Hikari pool to avoid leaking
        // the test-only connection pool across JVMs in parallel CI runs.
        if (seeder != null) {
            seeder.close();
            seeder = null;
            adminJdbc = null;
        }
    }

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        // Same FK-aware order on cleanup, via the admin pool so the DELETEs
        // run with full DML grants regardless of tenant context.
        if (seeder != null) {
            seeder.wipe();
        }
        // Redis is shared across scenarios — flush all challenge tokens
        // and rate-limit buckets so the next test starts clean.
        redisFactory.getConnection().serverCommands().flushAll();
    }

    // ------------------------------------------------------------
    // HTTP helpers
    // ------------------------------------------------------------

    private HttpHeaders authHeaders(String prefix, String secret) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-API-Key", prefix + secret);
        return h;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // ------------------------------------------------------------
    // 1. Happy path
    // ------------------------------------------------------------

    @Test
    void happyPath() throws Exception {
        Fido2TestAuthenticator authn = new Fido2TestAuthenticator(
                Origin.create(origin()), mapper);

        JsonNode regStart = registerStart(SLUG_A, PREFIX_A, SECRET_A, USER_HANDLE_A);
        String regToken = regStart.get("registrationToken").asString();
        JsonNode creationOptions = regStart.get("publicKeyCredentialCreationOptions");

        JsonNode attestationJson = authn.register(creationOptions);
        JsonNode regFinish = registerFinish(PREFIX_A, SECRET_A, regToken, attestationJson);
        assertThat(regFinish.get("credentialId").asString()).isNotBlank();

        // Authenticate against the same credential. Pass USER_HANDLE_A
        // so /start filters its allowCredentials to that one credential
        // (otherwise the usernameless path returns empty and ClientPlatform
        // has no descriptor to look up).
        JsonNode authStart = authenticationStart(PREFIX_A, SECRET_A, USER_HANDLE_A);
        String authToken = authStart.get("authenticationToken").asString();
        JsonNode requestOptions = authStart.get("publicKeyCredentialRequestOptions");

        JsonNode assertionJson = authn.authenticate(requestOptions);
        JsonNode authFinish = authenticationFinish(PREFIX_A, SECRET_A, authToken, assertionJson);

        String idToken = authFinish.get("idToken").asString();
        assertThat(idToken).isNotBlank();

        // Verify JWT signature against the JWKS endpoint — this proves
        // the full RP-to-end-app contract (header.kid → JWKS lookup →
        // RS256 signature verification).
        JWKSet jwks = JWKSet.parse(fetchJwks().toString());
        SignedJWT parsed = SignedJWT.parse(idToken);
        String kid = parsed.getHeader().getKeyID();
        RSAKey rsa = (RSAKey) jwks.getKeyByKeyId(kid);
        assertThat(rsa).as("kid %s present in JWKS", kid).isNotNull();
        JWSVerifier verifier = new RSASSAVerifier(rsa.toRSAPublicKey());
        assertThat(parsed.verify(verifier)).isTrue();

        // Phase 6: the JWT audience is the tenant UUID string (IdTokenIssuer
        // calls tenantUuid.toString()), not the legacy slug/label.
        assertThat(parsed.getJWTClaimsSet().getAudience())
                .as("audience must contain the issuing tenant UUID")
                .contains(TENANT_A_UUID.toString());

        // Phase 6: cred_id claim is now base64url of 16-byte UUID
        // (IdTokenIssuer.uuidToBytes: MSB then LSB via ByteBuffer).
        // Verify the claim decodes to exactly 16 bytes and round-trips
        // back to a valid UUID — confirms the PK type migration is
        // reflected correctly in the issued token.
        String credIdClaim = parsed.getJWTClaimsSet().getStringClaim("cred_id");
        assertThat(credIdClaim)
                .as("cred_id claim must be present")
                .isNotBlank();
        byte[] credIdBytes = Base64.getUrlDecoder().decode(credIdClaim);
        assertThat(credIdBytes)
                .as("cred_id claim must decode to 16-byte UUID")
                .hasSize(16);
        ByteBuffer bb = ByteBuffer.wrap(credIdBytes);
        UUID credentialId = new UUID(bb.getLong(), bb.getLong());
        assertThat(credentialId)
                .as("cred_id must round-trip to a non-nil UUID")
                .isNotEqualTo(new UUID(0, 0));

        // Task 9: a full reg→auth cycle must have written exactly four
        // ceremony_event rows — one per ceremony phase. The begin events
        // (RegistrationStartService / AuthenticationStartService) and the
        // *_FINISH_OK events (RegistrationFinishService /
        // AuthenticationFinishService) are all recorded via
        // CeremonyEventRecorder.recordAfterCommit. afterCommit runs
        // synchronously during each request's transaction commit, before
        // the HTTP response returns, so the rows are present the moment the
        // four endpoint calls above have completed (no polling needed).
        // seed() truncated ceremony_event for THIS tenant's clean slate, so
        // a global count is unambiguous within this isolated scenario.
        assertCeremonyEventCount("REGISTRATION_BEGIN", 1);
        assertCeremonyEventCount("REGISTRATION_FINISH_OK", 1);
        assertCeremonyEventCount("AUTHENTICATION_BEGIN", 1);
        assertCeremonyEventCount("AUTHENTICATION_FINISH_OK", 1);
    }

    /**
     * Asserts the exact number of {@code ceremony_event} rows for one
     * action. Reads via the PSK_APP_ADMIN pool (V41 grants PSK_APP_ADMIN SELECT on
     * ceremony_event) and qualifies the table as PSK_APP_OWNER.* to match the
     * schema-qualification convention used throughout this IT.
     */
    private void assertCeremonyEventCount(String action, long expected) {
        Long n = adminJdbc.queryForObject(
                "SELECT COUNT(*) FROM PSK_APP_OWNER.ceremony_event WHERE action = ?",
                Long.class, action);
        assertThat(n)
                .as("ceremony_event count for action=%s", action)
                .isEqualTo(expected);
    }

    // ------------------------------------------------------------
    // 2. Cross-tenant isolation
    // ------------------------------------------------------------

    @Test
    void crossTenantIsolation() throws Exception {
        // Register under T_A first so the database has T_A's credential.
        Fido2TestAuthenticator authn = new Fido2TestAuthenticator(
                Origin.create(origin()), mapper);
        JsonNode regStart = registerStart(SLUG_A, PREFIX_A, SECRET_A, USER_HANDLE_A);
        JsonNode attestationJson = authn.register(
                regStart.get("publicKeyCredentialCreationOptions"));
        registerFinish(PREFIX_A, SECRET_A, regStart.get("registrationToken").asString(),
                attestationJson);

        // Now call /authentication/start from T_B with T_A's userHandle.
        // The app-level @Filter must hide T_A's credential row from T_B, so
        // allowCredentials comes back EMPTY (no credentials match userHandle
        // within T_B's view of the credential table).
        JsonNode authStart = authenticationStart(PREFIX_B, SECRET_B, USER_HANDLE_A);
        JsonNode allow = authStart.get("publicKeyCredentialRequestOptions")
                .get("allowCredentials");
        assertThat(allow.isArray()).isTrue();
        assertThat(allow.size())
                .as("app-level @Filter must hide T_A's credential from T_B's /authentication/start")
                .isZero();
    }

    // ------------------------------------------------------------
    // 3. Missing API key
    // ------------------------------------------------------------

    @Test
    void missingApiKey() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"userHandle\":\"" + USER_HANDLE_A
                + "\",\"displayName\":\"a\",\"username\":\"a\"}";
        ResponseEntity<String> res = http.exchange(
                url("/api/v1/rp/registration/start"),
                HttpMethod.POST, new HttpEntity<>(body, h), String.class);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
    }

    // ------------------------------------------------------------
    // 4. Wrong API key
    // ------------------------------------------------------------

    @Test
    void wrongApiKey() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        // Right prefix shape (pk_ + 8 chars), wrong secret.
        h.set("X-API-Key", "pk_aaaaaaaaWRONG_SECRET_VALUE_THAT_IS_LONG_ENOUGH");
        String body = "{\"userHandle\":\"" + USER_HANDLE_A
                + "\",\"displayName\":\"a\",\"username\":\"a\"}";
        ResponseEntity<String> res = http.exchange(
                url("/api/v1/rp/registration/start"),
                HttpMethod.POST, new HttpEntity<>(body, h), String.class);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
    }

    // ------------------------------------------------------------
    // 5. Expired challenge — Redis key deleted between /start and /finish
    // ------------------------------------------------------------

    @Test
    void expiredChallenge() throws Exception {
        Fido2TestAuthenticator authn = new Fido2TestAuthenticator(
                Origin.create(origin()), mapper);
        JsonNode regStart = registerStart(SLUG_A, PREFIX_A, SECRET_A, USER_HANDLE_A);
        String token = regStart.get("registrationToken").asString();

        // Simulate TTL expiry by deleting the Redis key directly. The
        // ChallengeStore key namespace is "challenge:reg:<token>" — see
        // ChallengeStore.REG_PREFIX.
        Boolean deleted = redis.delete("challenge:reg:" + token);
        assertThat(deleted).as("Redis challenge key must have been present").isTrue();

        // Produce a valid attestation, but the /finish call will fail
        // because takeRegistration returns Optional.empty.
        JsonNode attestationJson = authn.register(
                regStart.get("publicKeyCredentialCreationOptions"));
        ObjectNode body = mapper.createObjectNode();
        body.put("registrationToken", token);
        body.set("publicKeyCredential", attestationJson);

        ResponseEntity<String> res = http.exchange(
                url("/api/v1/rp/registration/finish"),
                HttpMethod.POST,
                new HttpEntity<>(mapper.writeValueAsString(body),
                        authHeaders(PREFIX_A, SECRET_A)),
                String.class);
        // Spring's default exception handler returns 400 for
        // IllegalArgumentException, which is what
        // RegistrationFinishService throws on missing/expired token.
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    // ------------------------------------------------------------
    // 6. Sign-count replay — resubmit the same /finish body
    // ------------------------------------------------------------

    @Test
    void signCountReplay() throws Exception {
        // codex P1: must actually reach the strict-monotonic counter
        // check in AuthenticationFinishService. The earlier version
        // resubmitted the same auth token, which the atomic Redis
        // GETDEL on the challenge rejects first — so the
        // "newCounter <= storedCounter" branch was never exercised.
        // Here we explicitly drive that branch by inflating the
        // persisted signCount above any value webauthn4j-test will
        // produce, then running a fresh ceremony.
        Fido2TestAuthenticator authn = new Fido2TestAuthenticator(
                Origin.create(origin()), mapper);

        JsonNode regStart = registerStart(SLUG_A, PREFIX_A, SECRET_A, USER_HANDLE_A);
        JsonNode attestationJson = authn.register(
                regStart.get("publicKeyCredentialCreationOptions"));
        registerFinish(PREFIX_A, SECRET_A,
                regStart.get("registrationToken").asString(), attestationJson);

        // Inflate the stored signCount past any value the test
        // authenticator will return on the next assertion. The
        // PackedAuthenticator increments by 1 per ceremony, so a
        // five-digit floor is comfortably out of reach.
        adminJdbc.update("UPDATE PSK_APP_OWNER.credential SET sign_count = 99999");

        // Drive a fresh ceremony: new challenge token, new assertion.
        // webauthn4j-test will produce a counter around 1, which is
        // well below the inflated stored value, so the strict-monotonic
        // branch (`newCounter <= storedCounter`) MUST reject with 400.
        JsonNode authStart = authenticationStart(PREFIX_A, SECRET_A, USER_HANDLE_A);
        String authToken = authStart.get("authenticationToken").asString();
        JsonNode assertionJson = authn.authenticate(
                authStart.get("publicKeyCredentialRequestOptions"));
        ObjectNode finishBody = mapper.createObjectNode();
        finishBody.put("authenticationToken", authToken);
        finishBody.set("publicKeyCredential", assertionJson);

        ResponseEntity<String> res = http.exchange(
                url("/api/v1/rp/authentication/finish"),
                HttpMethod.POST,
                new HttpEntity<>(mapper.writeValueAsString(finishBody),
                        authHeaders(PREFIX_A, SECRET_A)),
                String.class);
        assertThat(res.getStatusCode().value())
                .as("Strict-monotonic check must reject when stored counter > new counter")
                .isEqualTo(400);
    }

    // ------------------------------------------------------------
    // 7. Rate limit — >300 calls in succession to /authentication/start
    // ------------------------------------------------------------

    @Test
    void rateLimit429() throws Exception {
        // /authentication/start spec limit is 300/minute per
        // (endpoint, IP) AND per (endpoint, API-key-prefix). The
        // bucket refills greedily at 300/60s = 5 tokens/second, so a
        // serial loop with ~1s per round-trip never wins the race:
        // each request returns just as a new token is refilled.
        //
        // Fire requests in parallel so total throughput outpaces the
        // refill rate. 16 threads × 50 reqs = 800 attempted; both the
        // IP bucket and key bucket should drain within the first 300
        // before any meaningful refill, and remaining attempts should
        // hit 429.
        String body = "{}";
        HttpHeaders headers = authHeaders(PREFIX_A, SECRET_A);
        HttpEntity<String> request = new HttpEntity<>(body, headers);

        int threads = 16;
        int perThread = 50;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.atomic.AtomicInteger rejected =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger accepted =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<java.util.concurrent.Future<?>> tasks = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            tasks.add(pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    ResponseEntity<String> res = http.exchange(
                            url("/api/v1/rp/authentication/start"),
                            HttpMethod.POST, request, String.class);
                    if (res.getStatusCode().value() == 429) {
                        rejected.incrementAndGet();
                    } else {
                        accepted.incrementAndGet();
                    }
                }
            }));
        }
        for (java.util.concurrent.Future<?> f : tasks) f.get();
        pool.shutdown();

        assertThat(rejected.get())
                .as("Rate limit must reject at least one request out of %d "
                        + "(accepted=%d rejected=%d)",
                        threads * perThread, accepted.get(), rejected.get())
                .isGreaterThan(0);
    }

    // ------------------------------------------------------------
    // 8. JWKS alg/use/kid
    // ------------------------------------------------------------

    @Test
    void jwksAlgRS256() throws Exception {
        JsonNode jwks = fetchJwks();
        JsonNode keys = jwks.get("keys");
        assertThat(keys).isNotNull();
        assertThat(keys.isArray()).isTrue();
        assertThat(keys.size()).isGreaterThanOrEqualTo(1);
        JsonNode jwk = keys.get(0);
        assertThat(jwk.get("alg").asString()).isEqualTo("RS256");
        assertThat(jwk.get("use").asString()).isEqualTo("sig");
        assertThat(jwk.get("kid").asString()).isNotBlank();
    }

    // ------------------------------------------------------------
    // Endpoint helpers (typed JSON in/out)
    // ------------------------------------------------------------

    /**
     * Unwraps the {@code data} node from an ApiResponse envelope.
     * Fails fast with a descriptive message if {@code success} is false.
     */
    private JsonNode unwrap(String rawBody) throws Exception {
        JsonNode tree = mapper.readTree(rawBody);
        if (!tree.path("success").asBoolean()) {
            throw new AssertionError("API call failed: " + rawBody);
        }
        return tree.path("data");
    }

    private JsonNode registerStart(String tenantId, String prefix, String secret,
                                   String userHandleB64Url) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("userHandle", userHandleB64Url);
        body.put("displayName", tenantId + "-user");
        body.put("username", tenantId + "-user");
        ResponseEntity<String> res = http.exchange(
                url("/api/v1/rp/registration/start"),
                HttpMethod.POST,
                new HttpEntity<>(mapper.writeValueAsString(body),
                        authHeaders(prefix, secret)),
                String.class);
        if (res.getStatusCode().value() != 200) {
            throw new IllegalStateException(
                    "registration/start failed status=" + res.getStatusCode().value()
                            + " body=" + res.getBody());
        }
        return unwrap(res.getBody());
    }

    private JsonNode registerFinish(String prefix, String secret,
                                    String regToken, JsonNode publicKeyCredential) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("registrationToken", regToken);
        body.set("publicKeyCredential", publicKeyCredential);
        ResponseEntity<String> res = http.exchange(
                url("/api/v1/rp/registration/finish"),
                HttpMethod.POST,
                new HttpEntity<>(mapper.writeValueAsString(body),
                        authHeaders(prefix, secret)),
                String.class);
        if (res.getStatusCode().value() != 200) {
            throw new IllegalStateException(
                    "registration/finish failed status=" + res.getStatusCode().value()
                            + " body=" + res.getBody());
        }
        return unwrap(res.getBody());
    }

    private JsonNode authenticationStart(String prefix, String secret,
                                         String userHandleB64Url) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        if (userHandleB64Url != null) {
            body.put("userHandle", userHandleB64Url);
        }
        ResponseEntity<String> res = http.exchange(
                url("/api/v1/rp/authentication/start"),
                HttpMethod.POST,
                new HttpEntity<>(mapper.writeValueAsString(body),
                        authHeaders(prefix, secret)),
                String.class);
        if (res.getStatusCode().value() != 200) {
            throw new IllegalStateException(
                    "authentication/start failed status=" + res.getStatusCode().value()
                            + " body=" + res.getBody());
        }
        return unwrap(res.getBody());
    }

    private JsonNode authenticationFinish(String prefix, String secret,
                                          String authToken, JsonNode publicKeyCredential) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("authenticationToken", authToken);
        body.set("publicKeyCredential", publicKeyCredential);
        ResponseEntity<String> res = http.exchange(
                url("/api/v1/rp/authentication/finish"),
                HttpMethod.POST,
                new HttpEntity<>(mapper.writeValueAsString(body),
                        authHeaders(prefix, secret)),
                String.class);
        if (res.getStatusCode().value() != 200) {
            throw new IllegalStateException(
                    "authentication/finish failed status=" + res.getStatusCode().value()
                            + " body=" + res.getBody());
        }
        return unwrap(res.getBody());
    }

    private JsonNode fetchJwks() throws Exception {
        ResponseEntity<String> res = http.exchange(
                url("/.well-known/jwks.json"),
                HttpMethod.GET, HttpEntity.EMPTY, String.class);
        if (res.getStatusCode().value() != 200) {
            throw new IllegalStateException("jwks fetch failed status=" + res.getStatusCode().value());
        }
        return mapper.readTree(res.getBody());
    }

}
