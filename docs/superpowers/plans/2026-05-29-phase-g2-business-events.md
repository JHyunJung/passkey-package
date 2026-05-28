# Phase G2 — Business Event Logs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** G1 인프라 위에 ceremony / api-key auth / admin write / scheduler / sample-rp user-flow 의 핵심 비즈니스 이벤트 로그 ~35 개 추가.

**Architecture:** 신규 파일 0. 약 12개 service/filter/controller 에 SLF4J log.info/warn/error 호출 추가. MDC 는 G1 가 set — 메시지는 logfmt key=val 만.

**Tech Stack:** SLF4J + logback (G1 인프라).

**Spec reference:** `docs/superpowers/specs/2026-05-29-logging-design.md` § Phase G2.

---

## Execution policy

1. Tests minimal — 컴파일만, 새로운 동작 없음
2. Per-task codex review
3. Autonomous decisions

---

## File Structure

```
passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationStartService.java   # modify
passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java  # modify (보강)
passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationStartService.java # modify
passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.java # modify (보강)
passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java                     # modify (WARN 실패분기 + DEBUG 성공)
passkey-app/src/main/java/com/crosscert/passkey/app/security/RateLimitFilter.java                      # modify (WARN)
admin-app/src/main/java/com/crosscert/passkey/admin/operator/AdminUserService.java                     # modify
admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminService.java                     # modify
admin-app/src/main/java/com/crosscert/passkey/admin/policy/AaguidPolicyService.java                    # modify
admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java                     # modify (보강)
admin-app/src/main/java/com/crosscert/passkey/admin/policy/SecurityPolicyService.java                  # modify
admin-app/src/main/java/com/crosscert/passkey/admin/credential/CredentialAdminService.java             # modify
admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyMgmtService.java                        # modify
admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSchedulerService.java                       # modify (일관화)
admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyExpirationJob.java                      # modify
sample-rp/src/main/java/com/crosscert/passkey/samplerp/web/WebAuthnController.java                     # modify
```

---

## Working directory

`.claude/worktrees/logging-g2/` on `worktree-logging-g2` (forked from main at `bfbb7e1` = G1 merged).

---

## Task 1: passkey-app Ceremony Start services

**Files:**
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationStartService.java`
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationStartService.java`

- [ ] **Step 1: Add Logger field**

```java
private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RegistrationStartService.class);
```

(동일하게 AuthenticationStartService 에도)

- [ ] **Step 2: Add log statements**

RegistrationStartService — `start(...)` 메서드:
- 메서드 진입 직후:
  ```java
  log.info("registration/start: externalUserId={} displayName={}", req.externalUserId(), req.displayName());
  ```
- 응답 반환 직전:
  ```java
  log.info("registration/start: issued ceremonyId={} excludeCount={} timeoutMs={}",
           ceremonyId, excludeCredentials.size(), timeoutMs);
  ```

AuthenticationStartService — `start(...)`:
- 진입:
  ```java
  log.info("authentication/start: externalUserId={} allowCount={}",
           req.externalUserId(), allowCredentials == null ? 0 : allowCredentials.size());
  ```
- 응답 직전:
  ```java
  log.info("authentication/start: issued ceremonyId={} timeoutMs={}", ceremonyId, timeoutMs);
  ```

> 정확한 변수명은 실 코드 인스펙션 후 적용. 변수가 다른 이름이면 동등 의미의 것 사용.

- [ ] **Step 3: Compile + Codex + commit**

```bash
./gradlew :passkey-app:compileJava
git add passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationStartService.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationStartService.java
```

Codex prompt: "codex review RegistrationStartService + AuthenticationStartService log additions. Verify: (1) Logger field static final declared, (2) 2 INFO statements per service (진입 + 발급 직전), (3) logfmt key=value 패턴, (4) raw secret/PII 없음, (5) externalUserId 는 user-supplied 이므로 그대로 OK (이메일 아님). Must-fix only."

```bash
git commit -m "feat(passkey-app): registration/authentication start log (G2.1)"
```

---

## Task 2: passkey-app Ceremony Finish services

**Files:**
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java` (기존 3 log.warn 유지 + 보강)
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.java` (기존 6 log 유지 + 보강)

- [ ] **Step 1: 기존 log 확인**

```bash
grep -n "log\." passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java
grep -n "log\." passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.java
```

- [ ] **Step 2: RegistrationFinishService 보강**

성공 분기 (return 직전):
```java
log.info("registration/finish: ceremonyId={} credentialId={} aaguid={}",
         ceremonyId, credentialIdShort, aaguidShort);
```

서명 검증 실패 시 (기존 catch 블록):
```java
log.error("registration/finish: signature verification failed (ceremonyId={})", ceremonyId, e);
```

> `credentialIdShort` 는 `LogRedact.idTail(credentialId, 12)` 같은 패턴이지만 G4 까지는 헬퍼가 없음. 임시로 `credentialId.length() > 12 ? "..." + credentialId.substring(credentialId.length()-12) : credentialId` 정도로 인라인. G4 가 LogRedact 도입 시 일괄 교체.

- [ ] **Step 3: AuthenticationFinishService 보강**

성공 분기:
```java
log.info("authentication/finish: ceremonyId={} credentialId={} counter={}",
         ceremonyId, credentialIdShort, newCounter);
```

signature counter regression 분기 (이미 throw 가 있다면 그 직전):
```java
log.warn("authentication/finish: signature counter regression (cred={} prev={} new={})",
         credentialIdShort, prevCounter, newCounter);
```

ID Token 발급 직후:
```java
log.info("id-token issued: sub={} aud={} expSec={}",
         subShort, tenantId, ttl.getSeconds());
```

> JWT body 절대 출력 금지. claims 메타만.

- [ ] **Step 4: Compile + Codex + commit**

```bash
./gradlew :passkey-app:compileJava
git add passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.java
```

Codex prompt: "codex review Registration/Authentication FinishService log enhancements. Verify: (1) 기존 log.warn (tenant mismatch, attestation parse/verify failed) 유지, (2) 신규 INFO/WARN/ERROR 추가, (3) JWT body / raw signature bytes 노출 없음 — claims 메타만, (4) credentialId/sub 가 tail truncated (full base64url 아님), (5) signature counter regression WARN 의 prev/new 값 노출 OK. Must-fix only."

```bash
git commit -m "feat(passkey-app): registration/authentication finish log (G2.1)"
```

---

## Task 3: ApiKeyAuthFilter WARN failure + DEBUG success

**Files:**
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java`

G1 에서 이미 MDC put 추가됨. 이번엔 분기별 WARN/DEBUG 추가.

- [ ] **Step 1: 분기 식별**

filter 내부의 분기들:
- 헤더 없음 / 잘못된 길이 → unauthenticated 반환 직전
- prefix 미존재 → unknown-prefix
- bcrypt 불일치 → bad-secret
- revokedAt != null → revoked
- expiresAt < now → expired
- 모두 통과 → 성공

- [ ] **Step 2: WARN/DEBUG 추가**

각 실패 분기 직전:
```java
log.warn("api-key auth failed: reason=unknown-prefix prefix={}", prefix);
log.warn("api-key auth failed: reason=bad-secret prefix={}", prefix);
log.warn("api-key auth failed: reason=revoked prefix={}", prefix);
log.warn("api-key auth failed: reason=expired prefix={}", prefix);
```

성공 분기 (MDC put 이후, chain.doFilter 직전):
```java
if (log.isDebugEnabled()) {
    log.debug("api-key auth ok: prefix={} tenantId={}", prefix, row.tenantId());
}
```

- [ ] **Step 3: Compile + Codex + commit**

```bash
./gradlew :passkey-app:compileJava
git add passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java
```

Codex prompt: "codex review ApiKeyAuthFilter G2 log additions. Verify: (1) 4 WARN 실패 분기 (unknown-prefix/bad-secret/revoked/expired) 각각 한 줄, (2) prefix 만 노출 (secret 절대 없음 — 헤더 substring 또는 row.keyPrefix 사용), (3) success DEBUG 는 isDebugEnabled guard 로 prod overhead 0, (4) reason= 값 일관 (kebab-case). Must-fix only."

```bash
git commit -m "feat(passkey-app): ApiKeyAuthFilter WARN failure + DEBUG success (G2.2)"
```

---

## Task 4: RateLimitFilter WARN

**Files:**
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/security/RateLimitFilter.java`

- [ ] **Step 1: Inspect**

```bash
cat passkey-app/src/main/java/com/crosscert/passkey/app/security/RateLimitFilter.java | head -50
```

rate exceeded throw / response 처리 위치 식별.

- [ ] **Step 2: Add WARN**

rate exceeded 분기 직전:
```java
log.warn("rate limit exceeded: prefix={} path={} count={}", prefix, path, currentCount);
```

- [ ] **Step 3: Compile + Codex + commit**

```bash
./gradlew :passkey-app:compileJava
git add passkey-app/src/main/java/com/crosscert/passkey/app/security/RateLimitFilter.java
```

Codex prompt: "codex review RateLimitFilter WARN add. Verify: (1) 단일 WARN 라인 in rate-exceeded branch, (2) prefix only (secret 없음), (3) durMs 또는 count 같은 측정 값 포함. Must-fix only."

```bash
git commit -m "feat(passkey-app): RateLimitFilter rate-exceeded WARN (G2.2)"
```

---

## Task 5: admin-app Write Service logs (multi-file)

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/operator/AdminUserService.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminService.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/policy/AaguidPolicyService.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/policy/SecurityPolicyService.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/credential/CredentialAdminService.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyMgmtService.java`

각 service 의 audit.append(...) 호출 옆에 INFO/WARN 한 줄 추가. 패턴:

- **AdminUserService**:
  - `log.info("admin invite issued: email={} role={} tenantId={}", emailMasked, role, tenantId);`
  - `log.info("admin suspended: email={}", emailMasked);`
  - `log.info("admin activated: email={}", emailMasked);`

- **ApiKeyAdminService**:
  - `log.info("api-key issued: prefix={} name={} tenantId={} scopes={}", prefix, req.name(), req.tenantId(), req.scopes());`
  - `log.warn("api-key revoked: prefix={} reason=admin", k.getKeyPrefix());`

- **AaguidPolicyService**:
  - `log.info("aaguid policy updated: tenantId={} mode={} entries={}", tenantId, mode, entries.size());`

- **TenantAdminService**:
  - `log.info("tenant created: slug={} id={}", slug, id);`
  - `log.info("tenant updated: id={} changed={}", id, changedFields);`

- **SecurityPolicyService**:
  - `log.info("security policy updated: sessionIdle={} pwMin={} mfa={} corsAllowlistSize={}", view.sessionIdleTimeoutMinutes(), view.passwordMinLength(), view.mfaRequired(), view.corsAllowlist().size());`

- **CredentialAdminService**:
  - `log.warn("credential revoked: id={} reason={} tenantId={}", credentialIdShort, reason, tenantId);`

- **KeyMgmtService**:
  - `log.info("key rotation: tenantId={} newKid={} oldKid={}", tenantId, newKid, oldKid);`

> emailMasked / credentialIdShort 는 인라인 truncate (G4 LogRedact 도입 전 임시).

- [ ] **Step 1: Inspect 각 service 의 audit.append 위치**

```bash
for f in admin-app/src/main/java/com/crosscert/passkey/admin/{operator/AdminUserService,apikey/ApiKeyAdminService,policy/AaguidPolicyService,tenant/TenantAdminService,policy/SecurityPolicyService,credential/CredentialAdminService,keymgmt/KeyMgmtService}.java; do
  echo "=== $f ==="
  grep -n "audit\.append\|Logger\|log\.\|@Service" "$f" | head -5
done
```

- [ ] **Step 2: 각 service 에 Logger field + log 호출 추가**

각 파일에 Logger 가 없으면 추가:
```java
private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MyService.class);
```

audit.append 직전 또는 직후에 위 패턴 INFO/WARN 추가.

- [ ] **Step 3: Compile + Codex + commit**

```bash
./gradlew :admin-app:compileJava
git add admin-app/src/main/java/com/crosscert/passkey/admin/operator/AdminUserService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/policy/AaguidPolicyService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/policy/SecurityPolicyService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/credential/CredentialAdminService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyMgmtService.java
```

Codex prompt: "codex review admin-app 7 services log additions. Verify: (1) Logger field present per service, (2) INFO/WARN 라인이 audit.append 와 1:1 매칭 (이중화 OK — audit_log 와 log 는 다른 계층), (3) email/credentialId 등은 truncated/masked (G4 LogRedact 대체 예정 — 임시 inline OK), (4) logfmt 일관, (5) api-key issued 의 plaintext secret 출력 절대 금지 (prefix 만), (6) MDC 가 이미 actorEmail 채우므로 message 안 중복 안 함. Must-fix only."

```bash
git commit -m "feat(admin-app): write actions log on 7 services (G2.3)"
```

---

## Task 6: admin-app Scheduler logs

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSchedulerService.java` (기존 log 일관화 + 보강)
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyExpirationJob.java`

- [ ] **Step 1: Inspect 기존 MdsSchedulerService log**

```bash
grep -n "log\." admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSchedulerService.java
```

이미 4 log 존재. 메시지를 spec 형식으로 일관화:

- 시작 시: `log.info("mds sync started: scheduledAt={}", scheduledAt);`
- 성공: `log.info("mds sync ok: version={} durMs={}", version, durMs);`
- skip: `log.info("mds sync skipped: reason={}", reason);` (이미 있을 가능성)
- 실패: `log.error("mds sync failed: cause={}", e.toString(), e);`

- [ ] **Step 2: KeyExpirationJob 신규 log**

```bash
cat admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyExpirationJob.java | head -50
```

Logger field 추가, 그리고:
- 시작: `log.info("key expiration tick: candidates={}", candidates.size());`
- 키 만료 처리: `log.warn("key expired: tenantId={} kid={} ageMs={}", tenantId, kid, ageMs);`

- [ ] **Step 3: Compile + Codex + commit**

```bash
./gradlew :admin-app:compileJava
git add admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSchedulerService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyExpirationJob.java
```

Codex prompt: "codex review scheduler log additions. Verify: (1) MdsScheduler 기존 4 라인이 spec logfmt 로 변환됨, (2) KeyExpirationJob Logger 신규 추가, (3) ERROR 라인은 throwable second arg 로 stack trace 보존, (4) message logfmt key=val. Must-fix only."

```bash
git commit -m "feat(admin-app): MdsScheduler + KeyExpirationJob log (G2.4)"
```

---

## Task 7: sample-rp WebAuthnController logs

**Files:**
- Modify: `sample-rp/src/main/java/com/crosscert/passkey/samplerp/web/WebAuthnController.java`

기존 0 log.

- [ ] **Step 1: Add Logger field**

```java
private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WebAuthnController.class);
```

- [ ] **Step 2: Add log statements**

`registerOptions`:
- 진입 후 createPending 호출 후:
  ```java
  log.info("register/options: username={} userHandle={}", req.username(), userHandle);
  ```

`registerComplete`:
- 성공 분기 직전:
  ```java
  log.info("register/complete: userHandle={} credentialId={}", handle, fin.credentialId());
  ```
- pending missing throw 직전:
  ```java
  log.warn("register/complete: pending registration token missing");
  ```

`loginOptions`:
- 진입 후:
  ```java
  log.info("login/options: username={}", req.username() == null ? "<discoverable>" : req.username());
  ```

`loginComplete`:
- pending missing throw 직전:
  ```java
  log.warn("login/complete: pending authentication token missing");
  ```
- iss mismatch throw 직전:
  ```java
  log.warn("login/complete: iss mismatch expected={} got={}", expectedIss, claims.iss());
  ```
- aud mismatch:
  ```java
  log.warn("login/complete: aud mismatch expected={} got={}", props.tenantId(), claims.aud());
  ```
- unknown sub:
  ```java
  log.warn("login/complete: unknown sub={}", claims.sub());
  ```
- 성공 분기 마지막:
  ```java
  log.info("login/complete: user={} sub={}", user.username(), claims.sub());
  ```

- [ ] **Step 3: Compile + Codex + commit**

```bash
./gradlew :sample-rp:compileJava
git add sample-rp/src/main/java/com/crosscert/passkey/samplerp/web/WebAuthnController.java
```

Codex prompt: "codex review sample-rp WebAuthnController log additions. Verify: (1) Logger field 추가, (2) 4 endpoint × INFO 진입 + WARN 실패 분기, (3) login/complete 실패 사유 (iss/aud/sub) 명시, (4) JWT/idToken body 노출 0 (claims.iss/sub/aud 메타만), (5) sub 가 b64url userHandle 이므로 짧게 truncated. Must-fix only."

```bash
git commit -m "feat(sample-rp): WebAuthnController user-flow log (G2.5)"
```

---

## Task 8: G2 regression + cumulative codex + merge

**Files:** (verification)

- [ ] **Step 1: 전체 컴파일**

```bash
./gradlew :core:compileJava :admin-app:compileJava :passkey-app:compileJava :sample-rp:compileJava :sdk-java:compileJava
```

- [ ] **Step 2: 누적 codex review**

```bash
git diff main..HEAD --stat
```

Codex prompt: "codex review the cumulative Phase G2 diff (excluding docs/superpowers/plans/). Focus: (1) 약 35개 log statement 가 spec 의 logfmt 컨벤션 (event: key=val) 일관, (2) raw secret/JWT body/password/bcrypt hash 노출 0 — 모두 truncated/redacted, (3) Logger field per file 정상 선언, (4) INFO/WARN/ERROR 레벨 분류 적절 (성공=INFO, 보안 주의=WARN, 시스템 실패=ERROR), (5) admin actions 와 audit_log append 가 1:1 매칭 (이중화 의도), (6) admin-ui 디자인 영향 0 (frontend 파일 무수정), (7) G1 인프라 (MDC 5종) 와 일관 — message 안에 traceId/tenantId 등 중복 명시 안 함 (MDC 가 자동). Must-fix only. APPROVED for merge if clean."

Apply must-fix as `fix(g2): codex final review feedback`.

- [ ] **Step 3: Merge to main**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git merge --no-ff worktree-logging-g2 -m "Merge Phase G2 — Business event logs"
git log --oneline -3
```

---

## Phase G2 Summary

**What ships:** ~35 신규 log statement across 12 files. 신규 파일 0. 의존성 0.

**Design impact:** zero.

**Next phase:** G3 — 외부 호출 + 보안 로그.
