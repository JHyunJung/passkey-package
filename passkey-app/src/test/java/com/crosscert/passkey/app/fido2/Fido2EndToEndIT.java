package com.crosscert.passkey.app.fido2;

import com.crosscert.passkey.core.vpd.TenantContextHolder;
import com.zaxxer.hikari.HikariDataSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

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

    private static final String ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim-faststart";
    // OracleContainer.configure() copies the single password field into
    // BOTH ORACLE_PASSWORD (SYS/SYSTEM) and APP_USER_PASSWORD (APP_OWNER).
    // We reuse the same secret here so sqlplus can connect as SYS-AS-SYSDBA.
    private static final String SYS_PASSWORD = "app_owner_pw";

    @org.testcontainers.junit.jupiter.Container
    static final OracleContainer ORACLE = new OracleContainer(ORACLE_IMAGE)
            .withUsername("APP_OWNER")
            .withPassword(SYS_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("bootstrap-vpd.sql"),
                    "/tmp/bootstrap-vpd.sql");

    // Redis 7 with the same pinned tag as docker-compose.yml so dev and CI
    // exercise the same engine. exposedPorts wires Spring's Lettuce
    // connection factory through @DynamicPropertySource below.
    @org.testcontainers.junit.jupiter.Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry reg) throws Exception {
        // Run scripts/bootstrap-vpd.sql before Spring opens its first
        // pool connection — APP_ADMIN_USER must exist before Hikari
        // tries to authenticate. Same pattern as :core's VpdIsolationIT.
        Container.ExecResult exec = ORACLE.execInContainer(
                "bash", "-c",
                "sqlplus -S sys/" + SYS_PASSWORD + "@localhost:1521/XEPDB1 as sysdba "
                        + "@/tmp/bootstrap-vpd.sql");
        if (exec.getExitCode() != 0) {
            throw new IllegalStateException(
                    "bootstrap-vpd.sql failed (exit=" + exec.getExitCode() + ")\n"
                            + "STDOUT:\n" + exec.getStdout() + "\n"
                            + "STDERR:\n" + exec.getStderr());
        }
        // Runtime DataSource → APP_RUNTIME_USER so VPD policies actually
        // engage during the request path (APP_ADMIN holds EXEMPT ACCESS
        // POLICY and would silently bypass the cross-tenant isolation
        // check). Flyway uses APP_ADMIN_USER via spring.flyway.user in
        // application-test.yml, so migrations run independently of the
        // runtime pool.
        reg.add("spring.datasource.url", ORACLE::getJdbcUrl);
        reg.add("spring.datasource.username", () -> "APP_RUNTIME_USER");
        reg.add("spring.datasource.password", () -> "runtime_pw");

        reg.add("spring.data.redis.host", REDIS::getHost);
        reg.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
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
     * Independent APP_ADMIN_USER pool used ONLY by seed and cleanup.
     * The primary Spring DataSource logs in as APP_RUNTIME_USER so that
     * VPD policies engage during scenario execution (the
     * cross-tenant-isolation scenario depends on that). But APP_RUNTIME
     * lacks INSERT/UPDATE/DELETE grants on the {@code tenant} table
     * (V1 grants only SELECT to APP_RUNTIME), and VPD's update_check=TRUE
     * blocks cross-tenant writes from the runtime path. Seeding via
     * APP_ADMIN sidesteps both: EXEMPT ACCESS POLICY bypasses VPD,
     * and APP_ADMIN holds full DML on the platform-scoped tables.
     */
    private static HikariDataSource adminPool;
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
        ensureAdminPool();

        // Clear out any state lingering from a previous scenario before
        // seeding anew. Order matters because of FK from api_key →
        // tenant and credential → tenant.
        // Child tables have ON DELETE CASCADE FKs, so deleting parents
        // implicitly removes children; however explicit deletes are safer
        // when testing concurrent seeds.
        adminJdbc.update("DELETE FROM APP_OWNER.credential");
        adminJdbc.update("DELETE FROM APP_OWNER.api_key_scope");
        adminJdbc.update("DELETE FROM APP_OWNER.api_key");
        adminJdbc.update("DELETE FROM APP_OWNER.tenant_allowed_origin");
        adminJdbc.update("DELETE FROM APP_OWNER.tenant_accepted_format");
        adminJdbc.update("DELETE FROM APP_OWNER.tenant");

        seedTenant(TENANT_A_HEX, SLUG_A, "Tenant A", PREFIX_A, SECRET_A);
        seedTenant(TENANT_B_HEX, SLUG_B, "Tenant B", PREFIX_B, SECRET_B);

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
        if (adminPool != null) {
            adminPool.close();
            adminPool = null;
            adminJdbc = null;
        }
    }

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        // Same FK-aware order on cleanup. Use the admin pool so we
        // don't have to juggle APP_CTX for every DELETE.
        if (adminJdbc != null) {
            adminJdbc.update("DELETE FROM APP_OWNER.credential");
            adminJdbc.update("DELETE FROM APP_OWNER.api_key_scope");
            adminJdbc.update("DELETE FROM APP_OWNER.api_key");
            adminJdbc.update("DELETE FROM APP_OWNER.tenant_allowed_origin");
            adminJdbc.update("DELETE FROM APP_OWNER.tenant_accepted_format");
            adminJdbc.update("DELETE FROM APP_OWNER.tenant");
        }
        // Redis is shared across scenarios — flush all challenge tokens
        // and rate-limit buckets so the next test starts clean.
        redisFactory.getConnection().serverCommands().flushAll();
    }

    /**
     * Lazy-init the APP_ADMIN_USER pool on first seed. The JDBC URL is
     * the same one Spring's primary DataSource uses; we read it from
     * the ORACLE container directly (no need to crack open the primary
     * Hikari to copy it).
     */
    private void ensureAdminPool() {
        if (adminPool != null) return;
        adminPool = new HikariDataSource();
        adminPool.setJdbcUrl(ORACLE.getJdbcUrl());
        adminPool.setUsername("APP_ADMIN_USER");
        adminPool.setPassword("admin_pw");
        adminPool.setMaximumPoolSize(2);
        adminPool.setMinimumIdle(1);
        adminPool.setPoolName("fido2-it-admin-pool");
        adminJdbc = new JdbcTemplate(adminPool);
    }

    /**
     * Seeds one tenant + child config rows + one api_key + scope rows via the APP_ADMIN pool.
     *
     * <p>Phase 7: tenant no longer carries JSON CLOB columns. Origins and
     * accepted formats live in the {@code tenant_allowed_origin} and
     * {@code tenant_accepted_format} child tables. Similarly, api_key
     * scopes moved to the {@code api_key_scope} child table.
     *
     * @param tenantHex  32-char hex string for the RAW(16) id column
     * @param slug       short human-readable slug
     * @param displayName tenant display name
     * @param prefix     api_key key_prefix
     * @param secret     api_key plaintext secret (bcrypt-hashed before INSERT)
     */
    private void seedTenant(String tenantHex, String slug, String displayName,
                             String prefix, String secret) {
        // INSERT via raw JDBC as APP_ADMIN — VPD does not engage for
        // APP_ADMIN (EXEMPT ACCESS POLICY), and APP_ADMIN has full DML
        // on tenant and api_key (V21 grants).
        //
        // Phase 6: id and tenant_id are RAW(16); HEXTORAW converts the
        // 32-char hex literal. SYS_GUID() generates the api_key PK.
        // Phase 7: flag columns require_user_verification + mds_required
        // replace the attestation_policy CLOB. Origins and formats are in
        // child tables.
        adminJdbc.update(
                "INSERT INTO APP_OWNER.tenant "
                        + "(id, slug, display_name, status, rp_id, rp_name, "
                        + " require_user_verification, mds_required, "
                        + " created_at, updated_at) "
                        + "VALUES (HEXTORAW(?), ?, ?, 'active', 'localhost', ?, "
                        + "        'Y', 'N', SYSTIMESTAMP, SYSTIMESTAMP)",
                tenantHex, slug, displayName, displayName);

        // Seed allowed origin — must match origin() so WebAuthn4J accepts it.
        adminJdbc.update(
                "INSERT INTO APP_OWNER.tenant_allowed_origin "
                        + "(id, tenant_id, origin, sort_order) "
                        + "VALUES (SYS_GUID(), HEXTORAW(?), ?, 0)",
                tenantHex, origin());

        // Seed accepted formats (none + packed cover the test authenticator).
        adminJdbc.update(
                "INSERT INTO APP_OWNER.tenant_accepted_format "
                        + "(id, tenant_id, format) "
                        + "VALUES (SYS_GUID(), HEXTORAW(?), 'none')",
                tenantHex);
        adminJdbc.update(
                "INSERT INTO APP_OWNER.tenant_accepted_format "
                        + "(id, tenant_id, format) "
                        + "VALUES (SYS_GUID(), HEXTORAW(?), 'packed')",
                tenantHex);

        // Seed api_key (no scopes CLOB in Phase 7).
        adminJdbc.update(
                "INSERT INTO APP_OWNER.api_key "
                        + "(id, tenant_id, key_prefix, key_hash, name, created_at) "
                        + "VALUES (SYS_GUID(), HEXTORAW(?), ?, ?, 'primary', SYSTIMESTAMP)",
                tenantHex, prefix, encoder.encode(secret));

        // Seed api_key_scope — re-fetch the api_key id we just inserted by prefix.
        adminJdbc.update(
                "INSERT INTO APP_OWNER.api_key_scope (id, api_key_id, scope) "
                        + "SELECT SYS_GUID(), id, 'registration' FROM APP_OWNER.api_key "
                        + "WHERE key_prefix = ?",
                prefix);
        adminJdbc.update(
                "INSERT INTO APP_OWNER.api_key_scope (id, api_key_id, scope) "
                        + "SELECT SYS_GUID(), id, 'authentication' FROM APP_OWNER.api_key "
                        + "WHERE key_prefix = ?",
                prefix);
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
        String regToken = regStart.get("registrationToken").asText();
        JsonNode creationOptions = regStart.get("publicKeyCredentialCreationOptions");

        JsonNode attestationJson = authn.register(creationOptions);
        JsonNode regFinish = registerFinish(PREFIX_A, SECRET_A, regToken, attestationJson);
        assertThat(regFinish.get("credentialId").asText()).isNotBlank();

        // Authenticate against the same credential. Pass USER_HANDLE_A
        // so /start filters its allowCredentials to that one credential
        // (otherwise the usernameless path returns empty and ClientPlatform
        // has no descriptor to look up).
        JsonNode authStart = authenticationStart(PREFIX_A, SECRET_A, USER_HANDLE_A);
        String authToken = authStart.get("authenticationToken").asText();
        JsonNode requestOptions = authStart.get("publicKeyCredentialRequestOptions");

        JsonNode assertionJson = authn.authenticate(requestOptions);
        JsonNode authFinish = authenticationFinish(PREFIX_A, SECRET_A, authToken, assertionJson);

        String idToken = authFinish.get("idToken").asText();
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
        registerFinish(PREFIX_A, SECRET_A, regStart.get("registrationToken").asText(),
                attestationJson);

        // Now call /authentication/start from T_B with T_A's userHandle.
        // VPD must hide T_A's credential row from T_B, so allowCredentials
        // comes back EMPTY (no credentials match userHandle within T_B's
        // view of the credential table).
        JsonNode authStart = authenticationStart(PREFIX_B, SECRET_B, USER_HANDLE_A);
        JsonNode allow = authStart.get("publicKeyCredentialRequestOptions")
                .get("allowCredentials");
        assertThat(allow.isArray()).isTrue();
        assertThat(allow.size())
                .as("VPD must hide T_A's credential from T_B's /authentication/start")
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
        assertThat(res.getStatusCodeValue()).isEqualTo(401);
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
        assertThat(res.getStatusCodeValue()).isEqualTo(401);
    }

    // ------------------------------------------------------------
    // 5. Expired challenge — Redis key deleted between /start and /finish
    // ------------------------------------------------------------

    @Test
    void expiredChallenge() throws Exception {
        Fido2TestAuthenticator authn = new Fido2TestAuthenticator(
                Origin.create(origin()), mapper);
        JsonNode regStart = registerStart(SLUG_A, PREFIX_A, SECRET_A, USER_HANDLE_A);
        String token = regStart.get("registrationToken").asText();

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
        assertThat(res.getStatusCodeValue()).isEqualTo(400);
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
                regStart.get("registrationToken").asText(), attestationJson);

        // Inflate the stored signCount past any value the test
        // authenticator will return on the next assertion. The
        // PackedAuthenticator increments by 1 per ceremony, so a
        // five-digit floor is comfortably out of reach.
        adminJdbc.update("UPDATE APP_OWNER.credential SET sign_count = 99999");

        // Drive a fresh ceremony: new challenge token, new assertion.
        // webauthn4j-test will produce a counter around 1, which is
        // well below the inflated stored value, so the strict-monotonic
        // branch (`newCounter <= storedCounter`) MUST reject with 400.
        JsonNode authStart = authenticationStart(PREFIX_A, SECRET_A, USER_HANDLE_A);
        String authToken = authStart.get("authenticationToken").asText();
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
        assertThat(res.getStatusCodeValue())
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
                    if (res.getStatusCodeValue() == 429) {
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
        assertThat(jwk.get("alg").asText()).isEqualTo("RS256");
        assertThat(jwk.get("use").asText()).isEqualTo("sig");
        assertThat(jwk.get("kid").asText()).isNotBlank();
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
        if (res.getStatusCodeValue() != 200) {
            throw new IllegalStateException(
                    "registration/start failed status=" + res.getStatusCodeValue()
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
        if (res.getStatusCodeValue() != 200) {
            throw new IllegalStateException(
                    "registration/finish failed status=" + res.getStatusCodeValue()
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
        if (res.getStatusCodeValue() != 200) {
            throw new IllegalStateException(
                    "authentication/start failed status=" + res.getStatusCodeValue()
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
        if (res.getStatusCodeValue() != 200) {
            throw new IllegalStateException(
                    "authentication/finish failed status=" + res.getStatusCodeValue()
                            + " body=" + res.getBody());
        }
        return unwrap(res.getBody());
    }

    private JsonNode fetchJwks() throws Exception {
        ResponseEntity<String> res = http.exchange(
                url("/.well-known/jwks.json"),
                HttpMethod.GET, HttpEntity.EMPTY, String.class);
        if (res.getStatusCodeValue() != 200) {
            throw new IllegalStateException("jwks fetch failed status=" + res.getStatusCodeValue());
        }
        return mapper.readTree(res.getBody());
    }

}
