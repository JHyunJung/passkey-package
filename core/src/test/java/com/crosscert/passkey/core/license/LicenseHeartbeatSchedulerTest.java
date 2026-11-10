package com.crosscert.passkey.core.license;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P7 — {@link LicenseHeartbeatScheduler#heartbeat()} 의 관찰 가능한 결과(요청 경로, 상태 전이,
 * 실패 사유 문자열, 캐시 파일 기록)를 JDK 내장 HttpServer 스텁으로 고정한다.
 *
 * <p>특성화 테스트는 모두 공개 생성자(운영과 같은 기본 타임아웃)를 쓴다. 타임아웃 도입 전후로
 * 같은 결과여야 한다.
 */
@Timeout(30)
class LicenseHeartbeatSchedulerTest {

    private static final String JTI = "lic-test-001";

    @TempDir
    Path tmp;

    private HttpServer server;
    private ExecutorService serverExecutor;
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        release.countDown();
        if (server != null) server.stop(0);
        if (serverExecutor != null) serverExecutor.shutdownNow();
    }

    // ── 특성화: 현재 동작 고정 ────────────────────────────────────────────

    @Test
    void activeWithValidToken_refreshesStateAndWritesCache() throws Exception {
        String jws = LicenseTestFixtures.issueValid(Duration.ofDays(90), List.of("mds"));
        String url = respondWith(200, "{\"status\":\"active\",\"latestToken\":\"" + jws + "\"}");
        LicenseProperties p = props(url);
        LicenseStateMachine sm = newStateMachine();

        scheduler(sm, p).heartbeat();

        assertThat(requests).containsExactly("GET /v1/license/" + JTI + "/verify");
        LicenseStateMachine.Snapshot snap = sm.snapshot();
        assertThat(snap.state()).isEqualTo(LicenseState.VALID);
        assertThat(snap.lastFailureReason()).isNull();
        assertThat(snap.token().jti()).startsWith("lic-test-").isNotEqualTo(JTI);
        assertThat(new LicenseCache().read(p.cachePath()))
                .get()
                .extracting(LicenseCache.Entry::tokenJws)
                .isEqualTo(jws);
    }

    @Test
    void statusRevoked_reportsStatusFailure() throws Exception {
        String url = respondWith(200, "{\"status\":\"revoked\"}");

        LicenseStateMachine.Snapshot snap = runAgainst(url);

        assertThat(snap.lastFailureReason()).isEqualTo("status=revoked");
        assertThat(snap.state()).isEqualTo(LicenseState.NETWORK_GRACE);
        assertCacheNotWritten();
    }

    @Test
    void emptyBody_reportsEmptyResponse() throws Exception {
        String url = respondWith(200, null);

        LicenseStateMachine.Snapshot snap = runAgainst(url);

        assertThat(snap.lastFailureReason()).isEqualTo("empty response");
        assertThat(snap.state()).isEqualTo(LicenseState.NETWORK_GRACE);
        assertCacheNotWritten();
    }

    @Test
    void missingLatestToken_reportsMissingLatestToken() throws Exception {
        String url = respondWith(200, "{\"status\":\"active\"}");

        LicenseStateMachine.Snapshot snap = runAgainst(url);

        assertThat(snap.lastFailureReason()).isEqualTo("missing latestToken");
        assertThat(snap.state()).isEqualTo(LicenseState.NETWORK_GRACE);
        assertCacheNotWritten();
    }

    @Test
    void badSignature_reportsVerifyFailure() throws Exception {
        String jws = LicenseTestFixtures.issueWithBadSignature();
        String url = respondWith(200, "{\"status\":\"active\",\"latestToken\":\"" + jws + "\"}");

        LicenseStateMachine.Snapshot snap = runAgainst(url);

        assertThat(snap.lastFailureReason()).isEqualTo("verify: License signature invalid");
        assertThat(snap.state()).isEqualTo(LicenseState.NETWORK_GRACE);
        assertCacheNotWritten();
    }

    @Test
    void serverError_reportsHttpFailureWithExceptionSimpleName() throws Exception {
        String url = respondWith(500, "{\"error\":\"boom\"}");

        LicenseStateMachine.Snapshot snap = runAgainst(url);

        assertThat(snap.lastFailureReason()).isEqualTo("http: InternalServerError");
        assertThat(snap.state()).isEqualTo(LicenseState.NETWORK_GRACE);
        assertCacheNotWritten();
    }

    @Test
    void connectionRefused_reportsResourceAccessException() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }

        LicenseStateMachine.Snapshot snap =
                runAgainst("http://127.0.0.1:" + closedPort + "/v1/license");

        assertThat(snap.lastFailureReason()).isEqualTo("http: ResourceAccessException");
        assertThat(snap.state()).isEqualTo(LicenseState.NETWORK_GRACE);
        assertCacheNotWritten();
    }

    @Test
    void springWiring_onprem_constructsScheduler() {
        LicenseProperties p = props("http://127.0.0.1:1/v1/license");
        new ApplicationContextRunner()
                .withPropertyValues("passkey.deployment.mode=onprem")
                .withBean(LicenseStateMachine.class, this::newStateMachine)
                .withBean(LicenseVerifier.class, () -> verifier(p))
                .withBean(LicenseCache.class, LicenseCache::new)
                .withBean(LicenseProperties.class, () -> p)
                .withBean(Clock.class, Clock::systemUTC)
                .withUserConfiguration(LicenseHeartbeatScheduler.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(LicenseHeartbeatScheduler.class);
                });
    }

    // ── P7 개선: 응답 없는 서버에서 영구 블록하지 않음 ──────────────────

    @Test
    void hungServer_readTimeout_takesExistingNetworkFailurePath() throws Exception {
        String url = hangUntilReleased();
        LicenseProperties p = props(url);
        LicenseStateMachine sm = newStateMachine();
        LicenseHeartbeatScheduler s = new LicenseHeartbeatScheduler(
                sm, verifier(p), new LicenseCache(), p, Clock.systemUTC(),
                Duration.ofSeconds(2),     // connectTimeout
                Duration.ofSeconds(1));    // readTimeout

        long startNanos = System.nanoTime();
        s.heartbeat();
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        assertThat(elapsedMs)
                .as("read timeout(1s) 근방에서 끝나야 하며 20초 응답 지연을 다 기다리면 안 된다")
                .isLessThan(5_000);
        assertThat(requests).containsExactly("GET /v1/license/" + JTI + "/verify");
        LicenseStateMachine.Snapshot snap = sm.snapshot();
        // 연결 거부(connectionRefused_reportsResourceAccessException)와 같은 사유 문자열·상태 = 기존 네트워크 오류 경로
        assertThat(snap.lastFailureReason()).isEqualTo("http: ResourceAccessException");
        assertThat(snap.state()).isEqualTo(LicenseState.NETWORK_GRACE);
        assertCacheNotWritten();
    }

    @Test
    void defaultTimeouts_areGenerousAndWellBelowHeartbeatInterval() {
        assertThat(LicenseHeartbeatScheduler.CONNECT_TIMEOUT).isEqualTo(Duration.ofSeconds(30));
        assertThat(LicenseHeartbeatScheduler.READ_TIMEOUT).isEqualTo(Duration.ofSeconds(60));
        Duration defaultInterval =
                new LicenseProperties(null, null, null, null, null, null).heartbeatInterval();
        assertThat(LicenseHeartbeatScheduler.CONNECT_TIMEOUT.plus(LicenseHeartbeatScheduler.READ_TIMEOUT))
                .isLessThan(defaultInterval);
    }

    @Test
    void headersThenStalledBody_doesNotBlock_andTakesNetworkFailurePath() throws Exception {
        String url = start(exchange -> {
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 1000);
                exchange.getResponseBody().write("{\"status\":".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        LicenseProperties p = props(url);
        LicenseStateMachine sm = newStateMachine();
        LicenseHeartbeatScheduler s = new LicenseHeartbeatScheduler(
                sm, verifier(p), new LicenseCache(), p, Clock.systemUTC(),
                Duration.ofSeconds(2), Duration.ofSeconds(1));

        long t0 = System.nanoTime();
        s.heartbeat();

        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
        assertThat(sm.snapshot().state()).isEqualTo(LicenseState.NETWORK_GRACE);
        assertThat(sm.snapshot().lastFailureReason()).startsWith("http: ");
        assertCacheNotWritten();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private LicenseStateMachine.Snapshot runAgainst(String url) {
        LicenseStateMachine sm = newStateMachine();
        scheduler(sm, props(url)).heartbeat();
        return sm.snapshot();
    }

    private void assertCacheNotWritten() {
        assertThat(Files.exists(tmp.resolve("license-cache.jwt"))).isFalse();
    }

    private LicenseHeartbeatScheduler scheduler(LicenseStateMachine sm, LicenseProperties p) {
        return new LicenseHeartbeatScheduler(sm, verifier(p), new LicenseCache(), p, Clock.systemUTC());
    }

    private LicenseVerifier verifier(LicenseProperties p) {
        return new LicenseVerifier(
                new LicensePublicKeyProvider("license-public.test.ed25519.pub"), p, Clock.systemUTC());
    }

    private LicenseProperties props(String heartbeatUrl) {
        return new LicenseProperties(
                null, tmp.resolve("license-cache.jwt"),
                "license.crosscert.com", "passkey-onprem", heartbeatUrl, null);
    }

    private LicenseStateMachine newStateMachine() {
        Instant now = Instant.now();
        LicenseToken initial = new LicenseToken(
                "test", JTI,
                now, now,
                now.plus(Duration.ofDays(365)),
                "00000000-0000-0000-0000-000000000001",
                Set.of("mds"),
                LicenseLimits.DEFAULTS);
        return new LicenseStateMachine(initial, now, Instant::now);
    }

    private String respondWith(int status, String jsonBody) throws IOException {
        return start(exchange -> {
            try {
                byte[] bytes = jsonBody == null ? new byte[0] : jsonBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                }
            } finally {
                exchange.close();
            }
        });
    }

    /** 연결은 받지만 테스트가 끝날 때(최대 20초)까지 응답하지 않는 서버. */
    private String hangUntilReleased() throws IOException {
        return start(exchange -> {
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                exchange.sendResponseHeaders(503, -1);
            } finally {
                exchange.close();
            }
        });
    }

    private String start(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "license-stub");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(serverExecutor);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            handler.handle(exchange);
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/license";
    }
}
