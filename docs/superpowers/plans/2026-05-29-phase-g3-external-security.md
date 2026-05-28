# Phase G3 — External Calls + Security Logs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** G1 인프라 + G2 비즈니스 위에 외부 호출(MDS, SDK call) 과 보안 결정 지점(IdToken verify, admin login, RBAC, tenant boundary) ~25 개 로그 추가.

**Architecture:** 약 10 파일에 log 호출 추가. 신규 파일 0. 의존성 0.

**Spec reference:** `docs/superpowers/specs/2026-05-29-logging-design.md` § Phase G3.

---

## Execution policy

1. Tests minimal — compile only
2. Per-task codex review
3. Autonomous decisions

---

## File Structure

```
admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsBlobClient.java        # modify
admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSchedulerService.java  # modify (lease/diff 보강)
admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java # modify (login success/failure handler)
admin-app/src/main/java/com/crosscert/passkey/admin/operator/InvitationService.java # modify
admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantBoundary.java    # modify (or wherever assertCanAccessTenant lives)
admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyRotationService.java # modify (rotated WARN)
passkey-app/src/main/java/com/crosscert/passkey/app/api/v1/rp/JwksController.java # modify (DEBUG)
core/src/main/java/com/crosscert/passkey/core/jwt/IdTokenIssuer.java               # modify (DEBUG kid/alg)
sdk-java/src/main/java/com/crosscert/passkey/sdk/PasskeyClient.java                # modify (sdk call log)
sdk-java/src/main/java/com/crosscert/passkey/sdk/idtoken/IdTokenVerifier.java      # modify (verify success/failure)
```

---

## Working directory

`.claude/worktrees/logging-g3/` on `worktree-logging-g3` (forked from main at G2 merge `b80c743`).

---

## Task 1: MDS external call logs

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsBlobClient.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSchedulerService.java`

- [ ] **Step 1: Inspect**

```bash
cat admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsBlobClient.java | head -60
grep -n "log\." admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSchedulerService.java
```

- [ ] **Step 2: MdsBlobClient — fetch result logs**

Logger field 추가 (없으면). HTTP fetch 호출 주변에:
- 성공 후: `log.info("mds blob fetch: url={} sizeBytes={} durMs={}", url, sizeBytes, durMs);`
- 서명 검증 실패 catch: `log.warn("mds blob fetch: signature verify failed (cause={})", e.toString());`
- HTTP 에러: `log.error("mds blob fetch: http error status={} url={}", status, url);`

- [ ] **Step 3: MdsSchedulerService — lease/diff 보강**

G2 에서 시작/끝/실패는 이미 있음. lease 분기 추가:
- lease 획득: `log.info("mds sync: lease acquired (instance={})", instance);`
- lease skip: `log.info("mds sync: lease skipped (held by other instance)");`
- diff: `log.info("mds sync: entries diff added={} removed={} unchanged={}", added, removed, unchanged);`

> 실제 lease/diff 계산 위치는 코드 인스펙션 후 결정. 변수 없으면 가능한 가까운 위치에 INFO 한 줄.

- [ ] **Step 4: Compile + Codex + commit**

```bash
./gradlew :admin-app:compileJava
git add admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsBlobClient.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSchedulerService.java
```

Codex prompt: "codex review MDS log additions. Verify: (1) MdsBlobClient INFO/WARN/ERROR 3종 추가, (2) URL/size/duration 메타만, blob body 노출 없음, (3) Scheduler lease/diff 분기별 INFO, (4) 기존 G2 의 mds sync started/ok/skipped/failed 유지, (5) ERROR 는 throwable second arg. Must-fix only."

```bash
git commit -m "feat(admin-app): MDS external call + scheduler lease/diff log (G3.1)"
```

---

## Task 2: SDK call log + IdTokenVerifier

**Files:**
- Modify: `sdk-java/src/main/java/com/crosscert/passkey/sdk/PasskeyClient.java`
- Modify: `sdk-java/src/main/java/com/crosscert/passkey/sdk/idtoken/IdTokenVerifier.java`

- [ ] **Step 1: PasskeyClient post() log**

`PasskeyClient.post(...)` 내부:
- DEBUG (성공): `log.debug("sdk call: {} {} status=200 durMs={}", method, path, durMs);`
- WARN (실패): `log.warn("sdk call: {} {} status={} code={}", method, path, status, errorCode);`

Logger field 추가.

- [ ] **Step 2: IdTokenVerifier.verify log**

- 성공 후 return 직전: `log.info("id-token verified: iss={} sub={} durMs={}", iss, subShort, durMs);`
- 서명 실패 throw 직전: `log.warn("id-token verify failed: reason=signature");`
- 만료: `log.warn("id-token verify failed: reason=expired exp={} now={}", exp, now);`
- iss 불일치: `log.warn("id-token verify failed: reason=iss-mismatch expected={} got={}", expected, got);`
- aud 불일치: `log.warn("id-token verify failed: reason=aud-mismatch expected={} got={}", expected, got);`

> JWT compactJwt 자체는 절대 안 찍음.

- [ ] **Step 3: Compile + Codex + commit**

```bash
./gradlew :sdk-java:compileJava
git add sdk-java/src/main/java/com/crosscert/passkey/sdk/PasskeyClient.java \
        sdk-java/src/main/java/com/crosscert/passkey/sdk/idtoken/IdTokenVerifier.java
```

Codex prompt: "codex review SDK log additions. Verify: (1) PasskeyClient.post DEBUG 성공 + WARN 실패, body/JWT 안 노출, (2) IdTokenVerifier 5개 분기 (success / signature / expired / iss-mismatch / aud-mismatch) WARN with reason= 명시, (3) sub 가 truncated (full b64url userHandle 안 노출), (4) compactJwt raw 노출 0. Must-fix only."

```bash
git commit -m "feat(sdk): PasskeyClient + IdTokenVerifier log (G3.2/G3.3)"
```

---

## Task 3: IdTokenIssuer + JwksController

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/jwt/IdTokenIssuer.java`
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/api/v1/rp/JwksController.java`

- [ ] **Step 1: IdTokenIssuer DEBUG**

`issue(...)` 메서드 끝 (return 직전):
```java
if (log.isDebugEnabled()) {
    log.debug("id-token issued: kid={} alg=RS256", signingKeys.signingKey().getKeyID());
}
```

> G2 의 AuthenticationFinishService 에서 이미 INFO 로 sub/aud 발급 정보 출력. IdTokenIssuer 는 DEBUG 로 키/알고리즘 메타만.

- [ ] **Step 2: JwksController DEBUG**

응답 직전:
```java
if (log.isDebugEnabled()) {
    log.debug("jwks served: keys={} activeKid={}", jwks.size(), activeKid);
}
```

> RequestLoggingFilter 가 path=/.well-known/jwks.json 을 exclude 함 — 명시적 로그 없으면 운영자가 호출량 추적 불가. DEBUG 한 줄로 보강.

- [ ] **Step 3: Compile + Codex + commit**

```bash
./gradlew :core:compileJava :passkey-app:compileJava
git add core/src/main/java/com/crosscert/passkey/core/jwt/IdTokenIssuer.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/api/v1/rp/JwksController.java
```

Codex prompt: "codex review IdTokenIssuer + JwksController DEBUG additions. Verify: (1) Logger field present, (2) isDebugEnabled guard 사용, (3) kid/alg 메타만, JWT body 안 노출, (4) JWKS public key bytes 안 노출 (count + active kid 만). Must-fix only."

```bash
git commit -m "feat(passkey-app/core): IdTokenIssuer + JwksController DEBUG log (G3.3/G3.4)"
```

---

## Task 4: Key rotation logs

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyRotationService.java`

(G2 의 KeyRotation INFO 와 KeyExpiration WARN 외 보강 — 키 만료 시 회전 미발생 경고)

- [ ] **Step 1: Inspect**

```bash
grep -n "log\.\|expired\|rotation" admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyRotationService.java | head -20
```

- [ ] **Step 2: 보강 로그**

키 회전 트리거 조건 미달 시:
```java
log.warn("signing key expired: kid={} (no rotation triggered, cause={})", kid, cause);
```

> 정확한 분기는 코드 인스펙션 후. 없으면 KeyExpirationJob 쪽에 두는 게 자연스러움 — G2 의 `key expired` WARN 이 이미 그 자리에 있을 수 있어 중복 회피.

> **Autonomous decision**: 만약 키 만료 + rotation 미발동 의 조합 분기가 명시적으로 없으면, 이 task 는 no-op + commit message 에 "no-op: rotation gating already covered by G2 key-expired WARN" 남기고 다음 task 로.

- [ ] **Step 3: Codex + commit (또는 no-op skip)**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyRotationService.java
git commit -m "feat(admin-app): key rotation gating WARN (G3.4)" || echo "no-op"
```

---

## Task 5: admin login success/failure handler

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java`

- [ ] **Step 1: Inspect formLogin handlers**

```bash
grep -nB1 -A5 "formLogin\|successHandler\|failureHandler\|logoutHandler" admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java
```

기존 handler 가 있으면 거기 log 호출 추가. 없으면 lambda 인라인 등록.

- [ ] **Step 2: Add success/failure handlers**

success:
```java
.successHandler((req, res, auth) -> {
    log.info("admin login success: email={}", auth.getName());
    // 기존 default behavior (redirect to /)
    res.sendRedirect("/");
})
```

failure:
```java
.failureHandler((req, res, ex) -> {
    String email = req.getParameter("username");
    String reason = (ex instanceof BadCredentialsException) ? "bad-password"
                  : (ex instanceof DisabledException) ? "user-disabled"
                  : (ex instanceof UsernameNotFoundException) ? "unknown-user"
                  : "other";
    log.warn("admin login failed: email={} reason={}", emailMask(email), reason);
    res.sendRedirect("/admin/login?error");
})
```

logout:
```java
.logoutSuccessHandler((req, res, auth) -> {
    if (auth != null) log.info("admin logout: email={}", auth.getName());
    res.sendRedirect("/admin/login?logout");
})
```

> AccessDeniedHandler 도 정의:
```java
.exceptionHandling(eh -> eh.accessDeniedHandler((req, res, ex) -> {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    log.warn("access denied: email={} path={} cause={}",
             auth != null ? auth.getName() : "anonymous",
             req.getRequestURI(),
             ex.getMessage());
    res.sendError(403);
}));
```

- [ ] **Step 3: Compile + Codex + commit**

```bash
./gradlew :admin-app:compileJava
git add admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java
```

Codex prompt: "codex review AdminSecurityConfig login/logout/access-denied handlers. Verify: (1) success/failure/logout 3 handlers, (2) failure 의 reason= 분류 (bad-password/user-disabled/unknown-user/other), (3) email 은 마스킹 헬퍼 (없으면 인라인 a***@domain), (4) AccessDeniedHandler WARN, (5) 기존 동작 (redirect path) 유지. Must-fix only."

```bash
git commit -m "feat(admin-app): login/logout/access-denied handlers + log (G3.5)"
```

---

## Task 6: InvitationService logs

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/operator/InvitationService.java`

- [ ] **Step 1: Inspect accept flow**

```bash
grep -n "log\.\|accept\|expired\|used\|throw" admin-app/src/main/java/com/crosscert/passkey/admin/operator/InvitationService.java | head -20
```

- [ ] **Step 2: Add WARN/INFO**

- accept 성공: `log.info("invitation accepted: email={} tokenPrefix={}", emailMask(email), tokenPrefix);`
- expired: `log.warn("invitation expired: tokenPrefix={}", tokenPrefix);`
- 재사용: `log.warn("invitation used: tokenPrefix={}", tokenPrefix);`

- [ ] **Step 3: Compile + Codex + commit**

```bash
./gradlew :admin-app:compileJava
git add admin-app/src/main/java/com/crosscert/passkey/admin/operator/InvitationService.java
```

Codex prompt: "codex review InvitationService log additions. Verify: (1) INFO accepted + WARN expired/used, (2) email masking, (3) tokenPrefix 만 (full token 안 노출). Must-fix only."

```bash
git commit -m "feat(admin-app): InvitationService log (G3.5)"
```

---

## Task 7: TenantBoundary WARN

**Files:**
- Modify: TenantBoundary class location TBD — `grep -rn "assertCanAccessTenant\|TenantBoundary" admin-app/src/main/java | head`

- [ ] **Step 1: Locate + inspect**

```bash
grep -rn "assertCanAccessTenant\|TenantBoundary" admin-app/src/main/java | head -5
```

- [ ] **Step 2: Add WARN before throw**

violation throw 직전:
```java
log.warn("tenant boundary violation: actor={} requested={} allowed={}",
         emailMask(actor.getEmail()), requestedTenantId, allowedTenantId);
```

- [ ] **Step 3: Compile + Codex + commit**

```bash
./gradlew :admin-app:compileJava
git add admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantBoundary.java
```

Codex prompt: "codex review TenantBoundary WARN. Verify: (1) violation 분기 직전 WARN, (2) actor email masked, (3) requested/allowed tenant UUID 만, (4) throw 동작 유지. Must-fix only."

```bash
git commit -m "feat(admin-app): TenantBoundary violation WARN (G3.6)"
```

---

## Task 8: G3 regression + cumulative codex + merge

**Files:** (verification)

- [ ] **Step 1: 전체 컴파일**

```bash
./gradlew :core:compileJava :admin-app:compileJava :passkey-app:compileJava :sample-rp:compileJava :sdk-java:compileJava
```

- [ ] **Step 2: 누적 codex review**

```bash
git diff main..HEAD --stat
```

Codex prompt: "codex review cumulative G3 diff. Focus: (1) MDS external call 3 levels (info/warn/error), (2) SDK call DEBUG/WARN, (3) IdTokenVerifier 5 분기 reason=, (4) IdTokenIssuer/JwksController DEBUG kid/alg/count, (5) admin login/logout/RBAC handlers, (6) InvitationService 3 분기, (7) TenantBoundary WARN, (8) 모든 자격증명/토큰 raw 출력 0, (9) email masking 일관. Must-fix only. APPROVED if clean."

Apply must-fix as `fix(g3): codex final review feedback`.

- [ ] **Step 3: Merge to main**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git merge --no-ff worktree-logging-g3 -m "Merge Phase G3 — External calls + security logs"
```

---

## Phase G3 Summary

~25 신규 log statement across 10 files. 신규 파일 0. 의존성 0. admin-ui 영향 0.

**Next phase:** G4 — Redaction 강화 + 운영 가이드.

---

## Execution notes (autonomous decisions)

- **Task 2 IdTokenVerifier — iss-mismatch / aud-mismatch omitted.**
  Current `IdTokenVerifier.verify` does NOT validate `iss`/`aud`; it
  only extracts them into `IdTokenClaims`. Logging non-existent branches
  would be misleading. Replaced with: `success`, `unknown-kid`,
  `signature`, `expired`, `parse` (5 reasons). When iss/aud validation
  is introduced (Phase H?), add matching WARNs alongside the new throw sites.

- **Task 4 KeyRotationService — no-op skip.**
  Rotation in KeyRotationService is admin-triggered (not time-gated). The
  "key expired + no rotation triggered" branch the plan envisaged does not
  exist as a distinct flow. G2's `key expired` WARN in
  `KeyExpirationJob.runOnce()` already covers the expiry observability.

- **Task 1 MdsBlobClient — size→entries substitution.**
  `FidoMDS3MetadataBLOBProvider.provide()` does not surface raw blob byte
  size. Logged `entries` count + `durMs` as a more actionable surrogate
  for operators.

- **Task 5 AdminSecurityConfig — DaoAuthenticationProvider
  setHideUserNotFoundExceptions(false).** Required to make `unknown-user`
  classification reachable; without it Spring wraps UNF in
  BadCredentialsException and every unknown-user attempt is mis-classified
  as bad-password.

- **RedactingRequestInterceptor (sdk-java internal) — out of scope.**
  Pre-existing G0 component that DEBUG-logs SDK request bodies with
  partial redaction of `idToken` and authenticator response fields.
  Tightening (full redaction) deferred to Phase G4 redaction hardening.
