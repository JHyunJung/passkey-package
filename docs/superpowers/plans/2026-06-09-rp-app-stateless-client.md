# rp-app 무상태 외부 클라이언트 연동 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** rp-app 의 WebAuthn begin→finish 를 세션 의존에서 무상태 토큰 릴레이로 전환해, 네이티브 앱·cross-origin 웹 SPA 가 동일 `/passkey/**` 를 쓰게 하고 CSRF 를 제거한다.

**Architecture:** begin 응답에 passkey-app 단명 토큰(+register 는 userHandle)을 담아 클라이언트에 반환하고, finish 가 요청 body 로 그 값을 다시 받아 SDK 로 패스스루한다. 로그인 세션(`SessionKeys.USER`)까지 제거해 완전 무상태가 되며, 그 결과 CSRF 보호가 불필요해진다(제거). id-token 은 rp-app 내부에서만 검증·소비하고 클라이언트에 노출하지 않는다. cross-origin 웹을 위해 정확한 origin 화이트리스트 CORS 를 추가한다.

**Tech Stack:** Spring Boot 3.5 (Spring Security 6, Spring Web), Java records (DTO), Bean Validation, JUnit + MockMvc(@WebMvcTest), Thymeleaf(데모 UI), vanilla JS(helpers.js).

**Spec:** `docs/superpowers/specs/2026-06-09-rp-app-stateless-client-design.md`

---

## File Structure

수정/생성 파일과 책임:
- `rp-app/.../web/dto/RegisterOptionsResp.java` (수정): begin 응답에 `registrationToken` + `userHandle` 노출.
- `rp-app/.../web/dto/LoginOptionsResp.java` (수정): begin 응답에 `authenticationToken` 노출.
- `rp-app/.../web/dto/RegisterCompleteReq.java` (수정): finish 요청에 `registrationToken` + `userHandle` 수신.
- `rp-app/.../web/dto/LoginCompleteReq.java` (수정): finish 요청에 `authenticationToken` 수신.
- `rp-app/.../web/dto/LoginResultResp.java` (생성): authenticate/finish 성공 결과(id-token 미포함).
- `rp-app/.../web/WebAuthnController.java` (수정): 세션 제거, 토큰/userHandle 릴레이, id-token 내부 소비.
- `rp-app/.../session/SessionKeys.java` (수정 또는 삭제): 세션 키 상수 제거.
- `rp-app/.../common/exception/ErrorCode.java` (수정): PENDING_* 메시지를 토큰 기준으로 조정.
- `rp-app/.../web/PageController.java` (수정): `/` 의 세션 USER 의존 제거.
- `rp-app/.../config/WebSecurityConfig.java` (수정): CSRF 제거 + CORS 화이트리스트 + 세션 stateless.
- `rp-app/.../config/CorsProperties.java` (생성): `rp.cors.allowed-origins` 바인딩.
- `rp-app/src/main/resources/templates/{layout,index}.html` (수정): 세션 user/csrf 메타태그 의존 제거.
- `rp-app/src/main/resources/static/js/helpers.js` (수정): `X-XSRF-TOKEN` 헤더·csrf 메타 의존 제거.
- `rp-app/src/test/.../WebAuthnControllerTest.java` (생성/수정): 무상태·id-token 비노출·쿠키 부재·CORS 검증.
- `docs/external-client-integration.md` (생성): 레퍼런스 연동 가이드.

각 task 는 자체로 컴파일·테스트 가능한 단위로 나눈다.

---

## Task 1: DTO 에 토큰/userHandle 필드 추가

**Files:**
- Modify: `rp-app/src/main/java/com/crosscert/passkey/rpapp/web/dto/RegisterOptionsResp.java`
- Modify: `rp-app/src/main/java/com/crosscert/passkey/rpapp/web/dto/LoginOptionsResp.java`
- Modify: `rp-app/src/main/java/com/crosscert/passkey/rpapp/web/dto/RegisterCompleteReq.java`
- Modify: `rp-app/src/main/java/com/crosscert/passkey/rpapp/web/dto/LoginCompleteReq.java`
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/web/dto/LoginResultResp.java`

- [ ] **Step 1: RegisterOptionsResp 에 토큰+userHandle 추가**

```java
package com.crosscert.passkey.rpapp.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * register/begin 응답. 무상태 릴레이를 위해 passkey-app 의 단명 registrationToken 과
 * userHandle 을 클라이언트에 함께 반환한다. 클라이언트는 register/finish 요청에 이 둘을
 * 다시 실어 보낸다. (토큰은 256bit·5분 TTL·1회성이라 노출돼도 개인키 서명 없이는 무력.)
 */
public record RegisterOptionsResp(
        JsonNode publicKeyCredentialCreationOptions,
        String registrationToken,
        String userHandle) {}
```

- [ ] **Step 2: LoginOptionsResp 에 토큰 추가**

```java
package com.crosscert.passkey.rpapp.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * authenticate/begin 응답. 무상태 릴레이를 위해 단명 authenticationToken 을 클라이언트에
 * 반환한다. 클라이언트는 authenticate/finish 요청에 이 토큰을 다시 실어 보낸다.
 */
public record LoginOptionsResp(
        JsonNode publicKeyCredentialRequestOptions,
        String authenticationToken) {}
```

- [ ] **Step 3: RegisterCompleteReq 에 토큰+userHandle 수신 필드 추가**

```java
package com.crosscert.passkey.rpapp.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RegisterCompleteReq(
        @NotNull JsonNode publicKeyCredential,
        @NotBlank String registrationToken,
        @NotBlank String userHandle) {}
```

- [ ] **Step 4: LoginCompleteReq 에 토큰 수신 필드 추가**

```java
package com.crosscert.passkey.rpapp.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record LoginCompleteReq(
        @NotNull JsonNode publicKeyCredential,
        @NotBlank String authenticationToken) {}
```

- [ ] **Step 5: LoginResultResp 생성 (id-token 미포함 결과)**

```java
package com.crosscert.passkey.rpapp.web.dto;

/**
 * authenticate/finish 성공 결과. id-token 은 rp-app 내부에서만 검증·소비하고 노출하지
 * 않는다(spec §4). 클라이언트는 이 결과로 "인증됨"을 알고 자기 UX 를 진행한다.
 */
public record LoginResultResp(
        boolean authenticated,
        String userHandle,
        String displayName) {}
```

- [ ] **Step 6: 컴파일 확인**

Run: `./gradlew :rp-app:compileJava -q`
Expected: BUILD SUCCESSFUL (이 시점엔 컨트롤러가 아직 새 DTO 형태를 안 써서 컴파일 에러가 날 수 있음 — 그 경우 Task 2 와 함께 커밋. 단독 컴파일이 깨지면 Task 2 까지 진행 후 Step 7 커밋).

- [ ] **Step 7: 커밋** (Task 2 컴파일까지 통과한 뒤 함께 커밋해도 됨)

```bash
git add rp-app/src/main/java/com/crosscert/passkey/rpapp/web/dto/
git commit -m "feat(rp-app): WebAuthn DTO 에 무상태 릴레이용 토큰/userHandle 필드 추가"
```

---

## Task 2: 컨트롤러 무상태화 (세션 제거 + 토큰 릴레이 + id-token 내부 소비)

**Files:**
- Modify: `rp-app/src/main/java/com/crosscert/passkey/rpapp/web/WebAuthnController.java`

- [ ] **Step 1: register/begin — 세션 저장 제거, 토큰+userHandle 반환**

`registerOptions` 메서드를 아래로 교체 (`HttpSession s` 파라미터 제거):

```java
    @PostMapping("/register/begin")
    public ApiResponse<RegisterOptionsResp> registerOptions(@Valid @RequestBody RegisterStartReq req) {
        log.info("register/options entry: usernamePresent={}", req.username() != null);
        String userHandle = users.createPending(req.username(), req.displayName());
        RegistrationStartResponse sdkResp;
        try {
            sdkResp = passkey.registrationStart(
                    new RegistrationStartRequest(userHandle, req.displayName(), req.username()));
        } catch (RuntimeException e) {
            log.warn("register/options upstream-failed: cause={}", e.toString());
            throw e;
        }
        log.info("register/options ok: userHandle={}", idTail(userHandle));
        // 무상태: 토큰·userHandle 을 세션이 아니라 응답으로 클라이언트에 반환.
        return ApiResponse.ok(new RegisterOptionsResp(
                sdkResp.publicKeyCredentialCreationOptions(),
                sdkResp.registrationToken(),
                userHandle));
    }
```

- [ ] **Step 2: register/finish — body 토큰/userHandle 사용**

`registerComplete` 메서드를 아래로 교체:

```java
    @PostMapping("/register/finish")
    public ApiResponse<RegistrationFinishResponse> registerComplete(@Valid @RequestBody RegisterCompleteReq req) {
        log.info("register/complete entry: userHandle={}", idTail(req.userHandle()));
        RegistrationFinishResponse fin;
        try {
            fin = passkey.registrationFinish(
                    new RegistrationFinishRequest(req.registrationToken(), req.publicKeyCredential()));
        } catch (RuntimeException e) {
            log.warn("register/complete upstream-failed: cause={}", e.toString());
            throw e;
        }
        users.confirmRegistration(req.userHandle(), fin.credentialId());
        log.info("register/complete ok: userHandle={} credentialId={}",
                idTail(req.userHandle()), idTail(fin.credentialId()));
        return ApiResponse.ok("Passkey registered", fin);
    }
```

- [ ] **Step 3: authenticate/begin — 세션 저장 제거, 토큰 반환**

`loginOptions` 메서드를 아래로 교체:

```java
    @PostMapping("/authenticate/begin")
    public ApiResponse<LoginOptionsResp> loginOptions(@RequestBody LoginStartReq req) {
        log.info("login/options entry: flow={}",
                req.username() == null ? "discoverable" : "typed");
        String userHandle = req.username() == null ? null
                : users.findByUsername(req.username()).map(RpAppUser::userHandle).orElse(null);
        AuthenticationStartResponse sdkResp;
        try {
            sdkResp = passkey.authenticationStart(new AuthenticationStartRequest(userHandle));
        } catch (RuntimeException e) {
            log.warn("login/options upstream-failed: cause={}", e.toString());
            throw e;
        }
        log.info("login/options ok: userHandlePresent={}", userHandle != null);
        return ApiResponse.ok(new LoginOptionsResp(
                sdkResp.publicKeyCredentialRequestOptions(),
                sdkResp.authenticationToken()));
    }
```

- [ ] **Step 4: authenticate/finish — body 토큰 사용, id-token 내부 소비, 세션 USER 제거, LoginResultResp 반환**

`loginComplete` 메서드를 아래로 교체 (`HttpSession s` 제거, 응답 타입 `ApiResponse<LoginResultResp>`):

```java
    @PostMapping("/authenticate/finish")
    public ApiResponse<LoginResultResp> loginComplete(@Valid @RequestBody LoginCompleteReq req) {
        log.info("login/complete entry");
        AuthenticationFinishResponse fin;
        try {
            fin = passkey.authenticationFinish(
                    new AuthenticationFinishRequest(req.authenticationToken(), req.publicKeyCredential()));
        } catch (RuntimeException e) {
            log.warn("login/complete upstream-failed: cause={}", e.toString());
            throw e;
        }
        IdTokenClaims claims;
        try {
            claims = passkey.verifyIdToken(fin.idToken());
        } catch (RuntimeException e) {
            log.warn("login/complete failed: reason=id-token-verify-failed cause={}", e.toString());
            throw e;
        }

        // iss = "<issuerBase>/<tenantId>", aud = "<tenantId>" 검증 (기존 로직 유지, P004).
        String expectedTenant = normalizeTenantId(props.tenantId());
        String issPrefix = props.issuerBase().toString();
        String tokenIss  = claims.iss();
        boolean issOk = tokenIss != null
                && tokenIss.startsWith(issPrefix + "/")
                && normalizeTenantId(tokenIss.substring((issPrefix + "/").length())).equals(expectedTenant);
        if (!issOk) {
            log.warn("login/complete failed: reason=iss-mismatch expectedPrefix={} expectedTenant={} got={}",
                    issPrefix, expectedTenant, tokenIss);
            throw new BusinessException(ErrorCode.PASSKEY_ID_TOKEN, "iss mismatch");
        }
        if (!expectedTenant.equals(normalizeTenantId(claims.aud()))) {
            log.warn("login/complete failed: reason=aud-mismatch expected={} got={}",
                    expectedTenant, claims.aud());
            throw new BusinessException(ErrorCode.PASSKEY_ID_TOKEN, "aud mismatch");
        }

        RpAppUser user = users.findByUserHandle(claims.sub())
                .orElseThrow(() -> {
                    log.warn("login/complete failed: reason=unknown-sub subTail={}", idTail(claims.sub()));
                    return new BusinessException(ErrorCode.PASSKEY_ID_TOKEN, "unknown sub");
                });
        log.info("login/complete ok: subTail={} userHandle={}",
                idTail(claims.sub()), idTail(user.userHandle()));
        // 무상태: 세션에 USER 저장하지 않음. id-token 은 여기서 검증·소비하고 노출하지 않음.
        return ApiResponse.ok(new LoginResultResp(true, user.userHandle(), user.displayName()));
    }
```

- [ ] **Step 5: import 정리 + HttpSession/SessionKeys import 제거**

`WebAuthnController.java` 상단에서 `import jakarta.servlet.http.HttpSession;` 와 `import ...session.SessionKeys;` 를 제거하고, `LoginResultResp` import 가 `web.dto.*` 와일드카드에 포함되는지 확인(현재 `import com.crosscert.passkey.rpapp.web.dto.*;` 사용 중이라 자동 포함).

- [ ] **Step 6: 컴파일 확인**

Run: `./gradlew :rp-app:compileJava -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add rp-app/src/main/java/com/crosscert/passkey/rpapp/web/WebAuthnController.java rp-app/src/main/java/com/crosscert/passkey/rpapp/web/dto/
git commit -m "feat(rp-app): WebAuthn 컨트롤러 무상태화 — 토큰 릴레이·id-token 내부 소비·세션 제거"
```

---

## Task 2.5: 등록 relay 토큰 — userHandle 바인딩 (P0-3, spec §5)

**배경:** Task 1+2 는 register DTO 에 `registrationToken`+`userHandle` 을 독립 필드로 노출했는데, 이러면 클라이언트가 `{registrationToken=A, userHandle=B}` 로 finish 해 A 의 credential 을 B 계정에 붙일 수 있다(코드 리뷰 P1). rp-app 이 HMAC 서명 relay 토큰으로 둘을 묶어 차단한다. passkey-app 무수정. **인증 흐름은 안 건드린다**(authenticationToken 은 passkey-app 이 userHandle 바인딩).

**Files:**
- Create: `rp-app/.../config/RelayProperties.java`
- Create: `rp-app/.../web/relay/RegRelayCodec.java`
- Modify: `rp-app/.../web/dto/RegisterOptionsResp.java`
- Modify: `rp-app/.../web/dto/RegisterCompleteReq.java`
- Modify: `rp-app/.../web/WebAuthnController.java`
- Modify: `rp-app/src/main/resources/application.yml`

- [ ] **Step 1: RelayProperties 생성**

```java
package com.crosscert.passkey.rpapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 등록 relay 토큰 HMAC 서명 설정(spec §5). registrationToken↔userHandle 바인딩용.
 * secret 은 운영 시 환경변수로 주입(데모 기본값은 비밀 아님). ttl 은 passkey-app
 * challenge TTL(5분)과 정렬.
 */
@ConfigurationProperties(prefix = "rp.relay")
public record RelayProperties(String secret, Duration ttl) {
    public RelayProperties {
        if (secret == null || secret.isBlank()) {
            secret = "dev-rp-relay-secret-not-for-prod-change-me";
        }
        if (ttl == null) ttl = Duration.ofMinutes(5);
    }
}
```

- [ ] **Step 2: RegRelayCodec 작성 (HMAC 서명/검증)**

```java
package com.crosscert.passkey.rpapp.web.relay;

import com.crosscert.passkey.rpapp.config.RelayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;

/**
 * 등록 relay 토큰 코덱(spec §5). {registrationToken, userHandle, exp} 를 HMAC-SHA256 으로
 * 서명한 불투명 토큰 "base64url(payloadJson).base64url(hmac)" 을 만들고 검증한다.
 * 서명이 맞아야 payload 를 신뢰 → 클라이언트가 userHandle 을 조작할 수 없다. 무상태(자기완결).
 */
@Component
public class RegRelayCodec {

    /** 복원된 relay payload. */
    public record RegRelay(String registrationToken, String userHandle) {}

    private static final String HMAC_ALG = "HmacSHA256";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] key;
    private final long ttlSeconds;
    private final ObjectMapper mapper;
    private final Clock clock;

    public RegRelayCodec(RelayProperties props, ObjectMapper mapper, Clock clock) {
        this.key = props.secret().getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = props.ttl().toSeconds();
        this.mapper = mapper;
        this.clock = clock;
    }

    /** {rt, uh, exp} 를 서명한 relay 토큰 생성. */
    public String encode(String registrationToken, String userHandle) {
        long exp = clock.instant().getEpochSecond() + ttlSeconds;
        ObjectNodePayload p = new ObjectNodePayload(registrationToken, userHandle, exp);
        byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(p);
        } catch (Exception e) {
            throw new IllegalStateException("relay encode failed", e);
        }
        String p64 = B64.encodeToString(payload);
        String sig = B64.encodeToString(hmac(p64));
        return p64 + "." + sig;
    }

    /** relay 토큰 검증·복원. 서명 불일치/만료/형식오류면 IllegalArgumentException. */
    public RegRelay decode(String token) {
        if (token == null) throw new IllegalArgumentException("relay token missing");
        int dot = token.indexOf('.');
        if (dot < 0) throw new IllegalArgumentException("relay token malformed");
        String p64 = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        byte[] expected = hmac(p64);
        byte[] actual;
        try {
            actual = B64D.decode(sig);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("relay token bad signature encoding");
        }
        // 상수시간 비교(타이밍 공격 방지).
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IllegalArgumentException("relay token bad signature");
        }
        ObjectNodePayload p;
        try {
            p = mapper.readValue(B64D.decode(p64), ObjectNodePayload.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("relay token bad payload");
        }
        if (p.exp() < clock.instant().getEpochSecond()) {
            throw new IllegalArgumentException("relay token expired");
        }
        return new RegRelay(p.rt(), p.uh());
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(key, HMAC_ALG));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("relay hmac failed", e);
        }
    }

    /** 직렬화 payload. 필드명을 짧게(rt/uh/exp) 유지. */
    record ObjectNodePayload(String rt, String uh, long exp) {}
}
```

- [ ] **Step 3: RegisterOptionsResp — regRelayToken 단일 필드로 교체**

```java
package com.crosscert.passkey.rpapp.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * register/begin 응답. 무상태 릴레이를 위해 registrationToken·userHandle 을 HMAC 서명한
 * 불투명 regRelayToken(spec §5)을 반환한다. 클라이언트는 register/finish 에 이 토큰을
 * 다시 실어 보낸다(userHandle 조작 불가).
 */
public record RegisterOptionsResp(
        JsonNode publicKeyCredentialCreationOptions,
        String regRelayToken) {}
```

- [ ] **Step 4: RegisterCompleteReq — regRelayToken 수신으로 교체**

```java
package com.crosscert.passkey.rpapp.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RegisterCompleteReq(
        @NotNull JsonNode publicKeyCredential,
        @NotBlank String regRelayToken) {}
```

- [ ] **Step 5: WebAuthnController register/begin·finish 를 relay 토큰으로**

`registerOptions` 의 반환을 relay 인코딩으로 교체(컨트롤러에 `RegRelayCodec relay` 주입 추가):

```java
        log.info("register/options ok: userHandle={}", idTail(userHandle));
        String regRelayToken = relay.encode(sdkResp.registrationToken(), userHandle);
        return ApiResponse.ok(new RegisterOptionsResp(
                sdkResp.publicKeyCredentialCreationOptions(), regRelayToken));
```

`registerComplete` 를 relay 디코딩으로 교체:

```java
    @PostMapping("/register/finish")
    public ApiResponse<RegistrationFinishResponse> registerComplete(@Valid @RequestBody RegisterCompleteReq req) {
        RegRelayCodec.RegRelay r;
        try {
            r = relay.decode(req.regRelayToken());
        } catch (IllegalArgumentException e) {
            log.warn("register/complete failed: reason=relay-invalid cause={}", e.getMessage());
            throw new BusinessException(ErrorCode.PENDING_REG_MISSING, e.getMessage());
        }
        log.info("register/complete entry: userHandle={}", idTail(r.userHandle()));
        RegistrationFinishResponse fin;
        try {
            fin = passkey.registrationFinish(
                    new RegistrationFinishRequest(r.registrationToken(), req.publicKeyCredential()));
        } catch (RuntimeException e) {
            log.warn("register/complete upstream-failed: cause={}", e.toString());
            throw e;
        }
        users.confirmRegistration(r.userHandle(), fin.credentialId());
        log.info("register/complete ok: userHandle={} credentialId={}",
                idTail(r.userHandle()), idTail(fin.credentialId()));
        return ApiResponse.ok("Passkey registered", fin);
    }
```

컨트롤러 생성자에 `RegRelayCodec relay` 파라미터 추가 + 필드 저장. `RegRelayCodec` import 추가(`com.crosscert.passkey.rpapp.web.relay.RegRelayCodec`).

- [ ] **Step 6: @EnableConfigurationProperties 에 RelayProperties 등록 + application.yml**

`RelayProperties` 가 바인딩되도록, 기존 `@EnableConfigurationProperties` 가 있는 설정 클래스(예: `PasskeyClientConfiguration` 또는 메인 애플리케이션)에 `RelayProperties.class` 추가. (없으면 `RelayProperties` 에 `@ConfigurationPropertiesScan` 이 메인에 있는지 확인.) Read 로 현재 바인딩 방식 확인 후 일관되게 추가.

`application.yml` 에 추가:

```yaml
rp:
  relay:
    # 등록 relay 토큰 HMAC 서명 키(spec §5). ⚠️ 운영은 환경변수로 강한 키 주입.
    secret: ${RP_RELAY_SECRET:}
    ttl: 5m
```

- [ ] **Step 7: 컴파일**

Run: `./gradlew :rp-app:compileJava -q 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: 커밋**

```bash
git add rp-app/src/main/java/com/crosscert/passkey/rpapp/config/RelayProperties.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/web/relay/RegRelayCodec.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/web/dto/RegisterOptionsResp.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/web/dto/RegisterCompleteReq.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/web/WebAuthnController.java \
        rp-app/src/main/resources/application.yml
git commit -m "fix(rp-app): 등록 relay 토큰으로 registrationToken↔userHandle 바인딩 (codex P1, spec §5)"
```

---

## Task 3: SessionKeys/ErrorCode/PageController 정리

**Files:**
- Modify: `rp-app/src/main/java/com/crosscert/passkey/rpapp/session/SessionKeys.java`
- Modify: `rp-app/src/main/java/com/crosscert/passkey/rpapp/common/exception/ErrorCode.java`
- Modify: `rp-app/src/main/java/com/crosscert/passkey/rpapp/web/PageController.java`

- [ ] **Step 1: SessionKeys 제거**

`SessionKeys.java` 는 더 이상 참조되지 않는다(컨트롤러에서 제거됨). 파일을 삭제한다:

Run: `git rm rp-app/src/main/java/com/crosscert/passkey/rpapp/session/SessionKeys.java`

만약 다른 곳에서 참조하면(grep 으로 확인) 그 참조부터 제거:
Run: `grep -rn "SessionKeys" rp-app/src/main/java`
Expected: (참조 없음 — 있으면 제거 후 진행)

- [ ] **Step 2: ErrorCode PENDING_* 메시지를 토큰 기준으로 조정**

`ErrorCode.java` 에서 두 줄을 교체 (세션→토큰 표현):

```java
    PENDING_REG_MISSING (BAD_REQUEST,             "W002", "Registration token missing or expired"),
    PENDING_AUTH_MISSING(BAD_REQUEST,             "W003", "Authentication token missing or expired"),
```

(이 ErrorCode 들은 이제 `@NotBlank` 위반 시 Bean Validation 이 먼저 400 을 내므로 직접 throw 되진 않지만, 메시지 일관성을 위해 조정. 미사용이면 그대로 두되 메시지만 갱신.)

- [ ] **Step 3: PageController 세션 USER 의존 제거**

`PageController.java` 의 `index` 를 아래로 교체 (세션 USER 미사용 — 데모 UI 는 클라이언트 측에서 상태 관리):

```java
package com.crosscert.passkey.rpapp.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/")
    public String index() { return "index"; }

    @GetMapping("/register")
    public String register() { return "register"; }

    @GetMapping("/login")
    public String login() { return "login"; }
}
```

import 에서 `HttpSession`, `Model`, `SessionKeys`, `RpAppUser` 제거.

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew :rp-app:compileJava -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add -A rp-app/src/main/java/com/crosscert/passkey/rpapp/
git commit -m "refactor(rp-app): SessionKeys 제거·ErrorCode 토큰 메시지·PageController 세션 의존 제거"
```

---

## Task 4: WebSecurityConfig — CSRF 제거 + CORS 화이트리스트 + stateless 세션

**Files:**
- Modify: `rp-app/src/main/java/com/crosscert/passkey/rpapp/config/WebSecurityConfig.java`
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/config/CorsProperties.java`

- [ ] **Step 1: CorsProperties 생성 (정확한 화이트리스트 바인딩)**

```java
package com.crosscert.passkey.rpapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * cross-origin 웹 SPA 를 위한 정확한 origin 화이트리스트.
 * ⚠️ reflected-origin(요청 Origin 반사)·와일드카드 금지(spec §3). 정확한 origin 목록만.
 * 이 목록은 passkey-app tenant 의 allowed-origins 와 일치시킬 것(드리프트 방지).
 * 설정: rp.cors.allowed-origins (콤마 구분 또는 YAML 리스트). 비면 CORS 비활성.
 */
@ConfigurationProperties(prefix = "rp.cors")
public record CorsProperties(List<String> allowedOrigins) {
    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
```

- [ ] **Step 2: WebSecurityConfig 교체 — CSRF disable + CORS + stateless**

```java
package com.crosscert.passkey.rpapp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class WebSecurityConfig {

    private final CorsProperties cors;

    public WebSecurityConfig(CorsProperties cors) {
        this.cors = cors;
    }

    @Bean
    SecurityFilterChain chain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(a -> a
                    .requestMatchers("/", "/register", "/login",
                                     "/passkey/**", "/css/**", "/js/**",
                                     "/.well-known/**", "/robots.txt").permitAll()
                    .anyRequest().permitAll())   // 데모용 — 보호 리소스 없음
            // 무상태(세션/쿠키 미사용)이므로 CSRF 보호 불필요(spec §3). 토큰은 절대 쿠키에 담지 않는다.
            .csrf(c -> c.disable())
            // 서버측 세션을 만들지 않는다 — 완전 무상태(spec P0-1).
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .cors(c -> c.configurationSource(corsConfigurationSource()))
            .formLogin(f -> f.disable())
            .httpBasic(h -> h.disable())
            .logout(l -> l.disable());
        return http.build();
    }

    /**
     * /passkey/** 에 정확한 origin 화이트리스트 CORS. allowCredentials=false(쿠키 미사용).
     * reflected-origin·와일드카드 금지 — allowedOrigins(정확 목록)만 사용.
     */
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(cors.allowedOrigins());      // 정확한 origin 만 (반사 금지)
        cfg.setAllowedMethods(List.of("POST", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Content-Type"));
        cfg.setAllowCredentials(false);                    // 쿠키 미사용
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/passkey/**", cfg);
        return src;
    }
}
```

- [ ] **Step 3: application.yml 에 CORS 기본값 + 주석 추가**

`rp-app/src/main/resources/application.yml` 의 `rp-app:` 블록 근처(또는 `passkey:` 블록 옆)에 추가:

```yaml
rp:
  cors:
    # cross-origin 웹 SPA 의 정확한 origin 목록. 비면 CORS 비활성(같은-origin 데모만).
    # ⚠️ 와일드카드/reflected 금지. passkey-app tenant allowed-origins 와 일치시킬 것.
    allowed-origins: ${RP_CORS_ALLOWED_ORIGINS:}
```

(환경변수 `RP_CORS_ALLOWED_ORIGINS` 가 콤마 구분 문자열이면 Spring 이 List 로 바인딩. 비면 빈 리스트 → CORS 헤더 미발급.)

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew :rp-app:compileJava -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add rp-app/src/main/java/com/crosscert/passkey/rpapp/config/ rp-app/src/main/resources/application.yml
git commit -m "feat(rp-app): CSRF 제거 + stateless 세션 + CORS 정확한 화이트리스트(reflected 금지)"
```

---

## Task 5: 데모 UI 무상태 전환 (helpers.js + 템플릿)

**Files:**
- Modify: `rp-app/src/main/resources/static/js/helpers.js`
- Modify: `rp-app/src/main/resources/templates/layout.html`
- Modify: `rp-app/src/main/resources/templates/index.html`

- [ ] **Step 1: helpers.js 의 postJson 에서 CSRF 헤더·메타 의존 제거**

`postJson` 함수를 아래로 교체 (csrf 메타 읽기·`X-XSRF-TOKEN`·`credentials` 제거 — 무상태):

```javascript
export async function postJson(url, body) {
  const res  = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  const env = await res.json();
  if (!env.success) {
    const err = new Error(env.message || 'Unknown error');
    err.code = env.code; err.traceId = env.traceId; err.fieldErrors = env.error?.fieldErrors;
    throw err;
  }
  return env.data;
}
```

- [ ] **Step 2: register/login 클라이언트 JS 가 토큰/userHandle 을 릴레이하도록 확인·수정**

데모 register/login 흐름 JS(템플릿 인라인 또는 별도 js)가 begin 응답의 `registrationToken`/`userHandle`/`authenticationToken` 을 보관했다가 finish 요청 body 에 실어야 한다. 해당 JS 위치를 찾는다:

Run: `grep -rn "register/begin\|authenticate/begin\|register/finish\|authenticate/finish\|postJson" rp-app/src/main/resources/templates rp-app/src/main/resources/static/js`

각 호출부에서: begin 응답 `data` 의 토큰/userHandle 을 지역 변수(메모리)에 보관 → finish 호출 body 에 `{publicKeyCredential, registrationToken, userHandle}` / `{publicKeyCredential, authenticationToken}` 로 포함. **localStorage/쿠키 저장 금지(메모리 변수만)** — spec §3, XSS 방어.

(구체적 수정은 그 JS 의 현재 구조에 맞춰 적용. 핵심: 토큰을 세션이 아니라 begin 응답에서 받아 finish 에 전달.)

- [ ] **Step 3: layout.html — csrf 메타태그·세션 user 의존 제거**

`layout.html` 에서:
- `<meta name="csrf" th:content="${_csrf?.token}">` 줄 **제거**.
- 상태 표시줄 `th:classappend="${user != null} ...` / `th:text="${user != null} ...` 를 정적 문구 또는 클라이언트 측 JS 갱신으로 변경 (서버 세션 user 없음). 최소 변경: 해당 `th:*` 속성을 제거하고 기본 "비로그인" 정적 표기 + 로그인 성공 시 JS 가 DOM 갱신.

- [ ] **Step 4: index.html — 세션 user 분기·csrf hidden input 제거**

`index.html` 에서:
- `th:if="${user == null}"` / `th:if="${user != null}"` 분기를 제거하고, 로그인 결과는 클라이언트 JS 가 `LoginResultResp`(authenticated/userHandle/displayName)로 DOM 을 갱신하도록 변경.
- `<input type="hidden" th:name="${_csrf?.parameterName}" ...>` (csrf hidden) **제거** (logout form 도 제거 — logout 비활성화됨).

- [ ] **Step 5: 데모 기동 수동 확인 (선택, 빌드만 필수)**

Run: `./gradlew :rp-app:compileJava :rp-app:processResources -q`
Expected: BUILD SUCCESSFUL (정적 리소스·템플릿 처리 통과).

- [ ] **Step 6: 커밋**

```bash
git add rp-app/src/main/resources/static/js/helpers.js rp-app/src/main/resources/templates/
git commit -m "feat(rp-app): 데모 UI 무상태 전환 — csrf 메타/세션 user 의존 제거, 토큰 메모리 릴레이"
```

---

## Task 6: 테스트 — 무상태·id-token 비노출·쿠키 부재·CORS

**Files:**
- Create/Modify: `rp-app/src/test/java/com/crosscert/passkey/rpapp/web/WebAuthnControllerTest.java`

기존 테스트가 있으면 무상태 형태로 수정, 없으면 생성. `@WebMvcTest(WebAuthnController.class)` + MockMvc, `PasskeyClient`/`InMemoryUserStore`/`PasskeyProperties` 는 `@MockBean`.

- [ ] **Step 1: register/begin 이 토큰+userHandle 을 응답에 포함하는지 + 세션 미사용**

```java
@Test
void registerBegin_returnsTokenAndUserHandle_noSession() throws Exception {
    given(users.createPending(any(), any())).willReturn("uh_test");
    given(passkey.registrationStart(any())).willReturn(
            new RegistrationStartResponse("regtok_123", objectMapper.readTree("{\"challenge\":\"x\"}")));

    mockMvc.perform(post("/passkey/register/begin")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"u\",\"displayName\":\"d\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.registrationToken").value("regtok_123"))
            .andExpect(jsonPath("$.data.userHandle").value("uh_test"))
            .andExpect(request().sessionAttributeDoesNotExist("PENDING_REG_TOKEN", "PENDING_USER_HANDLE", "USER"));
}
```

- [ ] **Step 2: 실행해서 실패 확인 (컨트롤러가 아직 구현 전이면 fail; 구현 후 pass)**

Run: `./gradlew :rp-app:test --tests "*WebAuthnControllerTest.registerBegin_returnsTokenAndUserHandle_noSession" -q`
Expected: Task 1~2 구현 후 PASS.

- [ ] **Step 3: authenticate/finish 가 id-token/JWT 를 응답에 노출하지 않는지 (회귀 가드)**

```java
@Test
void authenticateFinish_doesNotExposeIdToken() throws Exception {
    given(passkey.authenticationFinish(any())).willReturn(
            new AuthenticationFinishResponse("eyJhbGciOi.SECRET.JWT", "Bearer", 900));
    given(passkey.verifyIdToken(any())).willReturn(
            new IdTokenClaims("uh_test", "http://localhost:8080/" + TENANT, TENANT, /* ...claims... */));
    given(props.tenantId()).willReturn(TENANT);
    given(props.issuerBase()).willReturn(java.net.URI.create("http://localhost:8080"));
    given(users.findByUserHandle("uh_test")).willReturn(Optional.of(
            new RpAppUser("uh_test", "user", "disp", "credId")));

    MvcResult r = mockMvc.perform(post("/passkey/authenticate/finish")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"publicKeyCredential\":{},\"authenticationToken\":\"authtok_1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.authenticated").value(true))
            .andReturn();
    String bodyStr = r.getResponse().getContentAsString();
    org.assertj.core.api.Assertions.assertThat(bodyStr).doesNotContain("eyJhbGciOi");  // JWT 비노출
    org.assertj.core.api.Assertions.assertThat(bodyStr).doesNotContain("idToken");
}
```

(주의: `IdTokenClaims`·`RpAppUser` 생성자 시그니처는 실제 코드에 맞춰 조정. TENANT 상수는 `"00000000-0000-0000-0000-00000000c0de"` 등.)

- [ ] **Step 4: CSRF/세션 쿠키 부재 가드**

```java
@Test
void responses_setNoSessionOrCsrfCookie() throws Exception {
    given(users.createPending(any(), any())).willReturn("uh_test");
    given(passkey.registrationStart(any())).willReturn(
            new RegistrationStartResponse("regtok_123", objectMapper.readTree("{}")));

    MvcResult r = mockMvc.perform(post("/passkey/register/begin")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"u\",\"displayName\":\"d\"}"))
            .andExpect(status().isOk()).andReturn();
    java.util.List<String> setCookies = r.getResponse().getHeaders("Set-Cookie");
    org.assertj.core.api.Assertions.assertThat(setCookies)
            .noneMatch(c -> c.contains("XSRF-TOKEN") || c.contains("JSESSIONID"));
}
```

- [ ] **Step 5: finish 토큰 누락 시 400 (Bean Validation)**

```java
@Test
void authenticateFinish_missingToken_returns400() throws Exception {
    mockMvc.perform(post("/passkey/authenticate/finish")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"publicKeyCredential\":{}}"))   // authenticationToken 누락
            .andExpect(status().isBadRequest());
}
```

- [ ] **Step 6: CORS — 화이트리스트 origin 통과 / 그 외 미발급 / credentials 아님**

`@TestPropertySource(properties = "rp.cors.allowed-origins=https://app.example.com")` 를 테스트 클래스(또는 별도 CORS 테스트)에 적용:

```java
@Test
void cors_allowsWhitelistedOrigin_notReflected_noCredentials() throws Exception {
    // preflight: 화이트리스트 origin → Allow-Origin 발급, credentials true 아님
    mockMvc.perform(options("/passkey/register/begin")
                    .header("Origin", "https://app.example.com")
                    .header("Access-Control-Request-Method", "POST"))
            .andExpect(header().string("Access-Control-Allow-Origin", "https://app.example.com"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    // 화이트리스트 밖 origin → Allow-Origin 미발급(반사 안 함)
    mockMvc.perform(options("/passkey/register/begin")
                    .header("Origin", "https://evil.example.com")
                    .header("Access-Control-Request-Method", "POST"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
}
```

- [ ] **Step 7: 전체 rp-app 테스트 실행**

Run: `./gradlew :rp-app:test -q`
Expected: BUILD SUCCESSFUL, 모든 테스트 PASS.

- [ ] **Step 8: 커밋**

```bash
git add rp-app/src/test/
git commit -m "test(rp-app): 무상태 릴레이·id-token 비노출·쿠키 부재·CORS 화이트리스트 검증"
```

---

## Task 7: 레퍼런스 연동 가이드 문서

**Files:**
- Create: `docs/external-client-integration.md`

- [ ] **Step 1: 가이드 작성**

아래 내용으로 `docs/external-client-integration.md` 생성:

```markdown
# 외부 클라이언트(네이티브 앱 / cross-origin 웹) 연동 가이드

rp-app 의 `/passkey/**` 는 무상태 토큰 릴레이 방식이다. 세션 쿠키를 쓰지 않으므로
네이티브 앱과 cross-origin 웹 SPA 가 동일하게 연동할 수 있다.

## 시퀀스

### 등록
1. `POST /passkey/register/begin` `{username, displayName}`
   → 응답 `{publicKeyCredentialCreationOptions, registrationToken, userHandle}`
2. 클라이언트가 `registrationToken`·`userHandle` 을 **메모리에 보관**(localStorage/쿠키 금지).
3. WebAuthn `navigator.credentials.create(options)` 실행.
4. `POST /passkey/register/finish` `{publicKeyCredential, registrationToken, userHandle}`

### 인증
1. `POST /passkey/authenticate/begin` `{username?}` → `{publicKeyCredentialRequestOptions, authenticationToken}`
2. `authenticationToken` 메모리 보관.
3. WebAuthn `navigator.credentials.get(options)` 실행.
4. `POST /passkey/authenticate/finish` `{publicKeyCredential, authenticationToken}`
   → `{authenticated, userHandle, displayName}` (id-token 은 반환하지 않음 — rp-app 내부 검증·소비)

## 보안 요구사항 (반드시 지킬 것)
- **토큰은 POST body 로만.** URL 쿼리/프래그먼트/딥링크 쿼리/Referer 금지(로그·history 유출).
- **웹 SPA 는 토큰을 localStorage/sessionStorage 금지 — 메모리 변수만.** XSS 유출·지속성 차단.
- begin 토큰은 256bit·5분 TTL·1회성이라 노출돼도 개인키 서명 없이 무력하지만, 그래도 비밀로 다룬다.
- 토큰을 쿠키에 담지 말 것 — 담으면 CSRF 가 부활한다.

## CORS
- 서버는 `rp.cors.allowed-origins` 의 **정확한 origin 목록만** 허용(반사·와일드카드 금지).
- 쿠키를 안 쓰므로 클라이언트는 `credentials:'include'` 불필요, 서버는 `Allow-Credentials` off.
- 이 목록은 passkey-app tenant 의 allowed-origins 와 일치시킬 것.

## 프로덕션 주의
- rp-app 자체엔 rate limit 이 없다. begin 남용(challenge 발급) 방지를 위해 **앞단 게이트웨이/
  per-client rate limit 을 둘 것.** passkey-app 의 공유 키·공유 IP rate limit 만으로는 부족하다.
- **장기 세션이 필요하면**: authenticate/finish 성공 후 RP 가 자체 세션/토큰을 발급하라(데모는
  인증 결과 반환까지만 한다).

## 대안: same-origin 웹이면 BFF
cross-origin/네이티브가 아니라 **같은 origin 웹 전용**이면, 세션+CSRF(또는 BFF — 서버가 토큰을
보관하고 브라우저엔 HttpOnly 쿠키만)가 더 안전하다. 무상태 릴레이는 cross-origin/네이티브
요구가 있을 때의 패턴이다. 무상태 릴레이를 same-origin 웹에 그대로 쓰면 토큰을 JS 가 다루게 되어
XSS 노출 표면이 커진다.
```

- [ ] **Step 2: 커밋**

```bash
git add docs/external-client-integration.md
git commit -m "docs: 외부 클라이언트(네이티브/cross-origin 웹) 무상태 연동 가이드"
```

---

## Self-Review

**1. Spec coverage:**
- §1 무상태 begin/finish → Task 2 ✅
- §2 DTO/컨트롤러 → Task 1(DTO)·Task 2(컨트롤러) ✅
- §3 로그인 세션 제거 + CSRF 제거 + CORS → Task 2(세션 USER 제거)·Task 3(SessionKeys/PageController)·Task 4(CSRF/CORS/stateless) ✅
- §4 id-token 비노출 → Task 2 Step 4(LoginResultResp, id-token 미반환) + Task 6 Step 3(회귀 가드) ✅
- 에러 처리(토큰 누락 400, ErrorCode 메시지) → Task 3 Step 2·Task 6 Step 5 ✅
- 테스트(무상태·비노출·쿠키 부재·CORS) → Task 6 ✅
- 레퍼런스 문서(운반 규칙·localStorage 금지·rate limit·BFF 대안) → Task 7 ✅
- 데모 UI 무상태 전환(spec §3) → Task 5 ✅

**2. Placeholder scan:** 모든 Java/JS/YAML/MD 코드 완전 기재. Task 5 Step 2 와 Task 6 Step 3 은 "실제 시그니처에 맞춰 조정"을 명시했으나, 그 이유(데모 JS 구조·`IdTokenClaims` 생성자가 코드 의존)와 정확한 grep 명령·핵심 요구를 함께 줬으므로 실행 가능. ✅

**3. Type consistency:**
- `RegisterOptionsResp(options, registrationToken, userHandle)` ↔ Task 2 Step 1 의 생성 인자 3개 일치.
- `RegisterCompleteReq(publicKeyCredential, registrationToken, userHandle)` ↔ Task 2 Step 2 의 `req.registrationToken()`/`req.userHandle()` 일치.
- `LoginOptionsResp(options, authenticationToken)` ↔ Task 2 Step 3 일치.
- `LoginCompleteReq(publicKeyCredential, authenticationToken)` ↔ Task 2 Step 4 의 `req.authenticationToken()` 일치.
- `LoginResultResp(authenticated, userHandle, displayName)` ↔ Task 2 Step 4 반환 + Task 6 Step 3 검증 일치.
- `CorsProperties(allowedOrigins)` `@ConfigurationProperties("rp.cors")` ↔ Task 4 Step 3 의 `rp.cors.allowed-origins` 일치.
- SDK `RegistrationStartResponse.registrationToken()`·`AuthenticationStartResponse.authenticationToken()`·`AuthenticationFinishResponse.idToken()` 은 기존 코드 확인됨(spec 검증 단계).
