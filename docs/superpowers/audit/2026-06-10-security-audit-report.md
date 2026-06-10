# 보안 심층 감사 보고서 (제로베이스, 2026-06-10)

- **방법**: 신뢰경계 7렌즈 멀티에이전트 fan-out(각 렌즈 내 STRIDE 체크) → finding별 독립 적대적 검증 → 종합. 이전 감사 기록은 입력으로 쓰지 않은 완전 독립 감사.
- **규모**: 발견 30건 → **적대적 검증 통과 19건 confirmed**, 11건 오탐 기각. 검증 에이전트 포함 37 에이전트.
- **위협 모델**: SaaS 멀티테넌시(프록시/LB 뒤, VPD on) / 온프레미스(내부망, VPD off, 외부 SE DB) 각각 별도 판정.
- **원본 데이터**: [`2026-06-10-security-audit-findings.json`](./2026-06-10-security-audit-findings.json) (검증 reasoning 전문 포함).

> ⚠️ confirmed finding의 위치 인용은 검증 에이전트가 코드에서 재확인한 것이며, critical/high의 핵심 메커니즘은 보고서 작성 시 main 기준으로 직접 재검증했다.

## 요약 — 심각도 분포

| 심각도 | 건수 | finding |
|---|---|---|
| **critical** | 1 | MFA 재등록 우회 |
| **high** | 3 | MFA verify 브루트포스, 외부 부트스트랩 약한 DB 비번+GRANT ALL, admin 런타임 GRANT ALL(audit 무결성) |
| **medium** | 7 | rate-limit 프록시 붕괴, 초대 토큰 로그 노출, attestation trust 미검증, RSA-DoS는 low로 강등됨… (아래 표) |
| **low** | 6 | |
| **info** | 1 | cross-tenant 토큰 거부 회귀 테스트 부재 |

> 검증 에이전트가 발견 단계 severity를 독립 재판정하여 일부가 하향됐다(예: `scm-passkey-actuator` medium→low, `cc-recovery-code`/`dp-recovery-code` medium→low). 아래는 **검증 후 최종 severity** 기준.

## 우선순위 매트릭스 (최종 severity 기준)

| # | ID | 제목 | 최종 | 경계 | SaaS | 온프렘 |
|---|---|---|---|---|---|---|
| 1 | `rp-mfa-reenroll-bypass` | MFA-pending 세션이 enroll/disable 접근 → 비밀번호만으로 2차 인증 완전 우회 + 운영자 락아웃 | **critical** | admin-auth | ✅ | ✅ |
| 2 | `scm-external-bootstrap-hardcoded-db-creds` | 외부 Oracle 부트스트랩이 공개 약한 비번 + GRANT ALL PRIVILEGES | **high** | supply | — | ✅ |
| 3 | `dp-admin-user-grant-all-privileges` | admin-app 런타임 계정 GRANT ALL → audit_log append-only·컬럼 최소권한 무력화 | **high** | data | ✅ | ✅ |
| 4 | `rp-mfa-verify-no-ratelimit` | MFA verify(TOTP/복구코드)에 시도제한·잠금 부재 → 2차 인증 온라인 브루트포스 | **high** | admin-auth | ✅ | ✅ |
| 5 | `rp-ratelimit-proxy-remoteaddr` | 프록시 뒤 IP/anon 버킷이 LB 단일 IP로 붕괴 → 교차테넌트 DoS | medium | rp-api | ✅ | ✅(조건부) |
| 6 | `rp-invitation-token-logged` | 관리자 초대 토큰이 URL 경로 → 요청 로그에 평문 기록(replay) | medium | admin-auth | ✅ | ✅ |
| 7 | `cc-attestation-trust-never-enforced` | attestation 인증서 체인이 trust anchor로 검증 안 됨 → AAGUID/디바이스 정책 위조 | medium | crypto | ✅ | ✅ |
| 8 | `rp-pending-user-unbounded-dos` | 비인증 register/begin이 pending user 무한 누적 → 메모리 고갈 DoS | medium | client | ✅ | ✅ |
| 9 | `scm-passkey-actuator-unauthenticated` | passkey-app actuator(prometheus·health 상세) 무인증 노출 | low | supply | — | ✅ |
| 10 | `dp-runtime-overgrant-admin-secret-tables` | 인터넷 노출 passkey-app 계정이 admin 자격증명 테이블 SELECT + 마스터키 공존 | medium | data | ✅ | ✅ |
| 11 | `cc-recovery-code-weak-hash` / `dp-recovery-code-unsalted-low-entropy` | 복구코드 ~40비트 + 무염 SHA-256 → DB 덤프 오프라인 복원 | low | crypto/data | — | ✅ |
| 12 | `rp-registration-scope-grants-destructive-delete` | registration scope가 패스키 DELETE까지 허용(최소권한 위배) | low | rp-api | ✅ | ✅ |
| 13 | `cc-cosekey-rsa-no-upper-bound` | COSE RSA modulus/exponent 상한 부재 → 인증 시 검증 비용 증폭 | low | crypto | ✅ | ✅ |
| 14 | `admin-ui-cdn-css-no-sri` | admin 콘솔이 외부 CDN CSS를 SRI·CSP 없이 로드 | low | client | ✅ | ✅ |
| 15 | `rp-profile-endpoint-leak` | 미인증 /admin/api/profile이 활성 profile 목록 노출 | low | admin-auth | ✅ | ✅ |
| 16 | `scm-rpapp-secretmasking-jws-drift` | rp-app SecretMaskingConverter가 core 대비 JWS 패턴 누락(드리프트) | low | supply | — | — |
| 17 | `dp-dev-master-key-in-git` | dev/local 프로파일이 실제 32바이트 마스터키를 git 평문 출하 | low | data | — | ✅ |
| 18 | `rp-missing-cross-tenant-rejection-test` | cross-tenant id-token 거부 회귀 테스트 부재 | info | client | — | — |

(11번은 두 렌즈가 같은 결함을 독립 발견 — 한 건으로 묶음.)

---

## CRITICAL

### 1. `rp-mfa-reenroll-bypass` — MFA 재등록으로 2차 인증 완전 우회 + 운영자 락아웃

- **STRIDE**: S, E, D · **경계**: 관리자 인증 · **SaaS/온프렘 둘 다 악용 가능**
- **위치**: `admin-app/.../auth/MfaPendingFilter.java:73-80`, `MfaController.java:99-120`(enroll), `MfaController.java:56-84`(verify), `MfaController.java:174-178`(validCode)

**무엇이 문제인가.** `MfaPendingFilter.isAllowedWhilePending()`가 `uri.startsWith("/admin/api/mfa/")`로 MFA 네임스페이스 **전체**를 pending 상태에서 허용한다(76행). 1차(비밀번호) 로그인만 통과한 `MFA_PENDING=true` 세션이 `verify`뿐 아니라 `enroll`·`disable`에도 도달한다. `enroll`은 인자가 `Authentication auth`뿐이고 세션의 pending 여부나 기존 유효 TOTP 제시를 요구하지 않은 채 새 secret을 생성해 기존 secret을 덮어쓰고 평문 secret을 응답으로 돌려준다(112·119행). 결정적으로 `validCode`는 `mfaEnabled`를 보지 않고 secret 존재+코드 일치만 검사하므로(175-177행), enroll이 `mfaEnabled=false`로 남겨두는 것이 게이트 우회를 막지 못한다.

**악용 체인(3회 요청).** (1) 탈취한 운영자 비밀번호로 `/admin/login` → 세션 `MFA_PENDING=true`. (2) `/admin/api/mfa/enroll`(pending 허용) → 피해자의 원래 TOTP secret이 공격자 통제 secret으로 교체되고 평문 획득. (3) 그 secret의 TOTP로 `/admin/api/mfa/verify` → `validCode` 통과 → `session.removeAttribute(MFA_PENDING)` → 전체 관리자 세션. `confirm`조차 불필요. 부작용으로 원래 secret이 파괴되어 정당 운영자는 락아웃된다.

**영향.** MFA는 비밀번호 유출에 대비한 마지막 방어선인데, 비밀번호 하나만으로 그 방어선을 우회당한다. SaaS에서 PLATFORM_OPERATOR 계정이면 전 테넌트(API key·감사로그·테넌트 설정) cross-tenant 장악. 온프레미스도 앱 레벨 게이트 우회라 VPD off와 무관하게 동일.

**개선 방향.** pending 상태 허용 경로를 `/admin/api/mfa/verify`(+복구코드 검증)로만 좁힌다. enroll/confirm/disable은 비-pending(2차 인증 완료) 세션에서만 허용 — prefix 매칭을 명시 경로 매칭으로 바꾸거나 각 핸들러 진입부에서 pending을 직접 거부. **회귀 테스트**: 현재 `MfaPendingFilterTest`는 verify 통과만 검증하고 enroll/disable 차단을 검증하지 않는다 — enroll-while-pending 차단 테스트 추가 필수.

---

## HIGH

### 2. `scm-external-bootstrap-hardcoded-db-creds` — 외부 부트스트랩 약한 DB 비번 + GRANT ALL (온프렘)

- **STRIDE**: S, E, I · **경계**: 공급망·설정 · **온프레미스에서 직접 악용**
- **위치**: `scripts/bootstrap-external.sql:29,75,83,89`, `scripts/init-db-external.sh:93-95`, `scripts/bootstrap-vpd.sql:69,79`

고객사 외부 Oracle을 초기화하는 **문서화된 유일한 경로**가 APP_OWNER/APP_RUNTIME_USER/APP_ADMIN_USER 세 계정을 저장소에 공개된 고정 평문 비밀번호로 생성한다. 비밀번호는 `EXECUTE IMMEDIATE`의 SQL 리터럴이라 부트스트랩 시점에 env/치환 메커니즘이 없다. 더해 `bootstrap-external.sql:89`가 APP_ADMIN_USER에 `GRANT ALL PRIVILEGES`를 부여한다(스크립트 주석도 known-issue로 자인). 내부망 침투자가 DB 리스너에 도달하면 공개 `admin_pw`로 로그인 → 사실상 DBA → 전 테넌트 덤프·변조·VPD 우회. 강한 비밀번호를 강제하는 forcing function이 전무하다.

**개선 방향.** 계정 비밀번호를 외부 입력(sqlplus DEFINE/env 치환/필수 인자)으로 받고 디폴트가 비면 fail-fast. APP_ADMIN_USER의 GRANT ALL을 Flyway DDL·DBMS_RLS 운영에 필요한 최소 권한으로 축소. docker-compose 디폴트와 분리.

### 3. `dp-admin-user-grant-all-privileges` — 런타임 GRANT ALL이 audit append-only를 무력화

- **STRIDE**: E, R, T · **경계**: 데이터 보호 · **SaaS/온프렘 둘 다**
- **위치**: `scripts/bootstrap-vpd.sql:90`, `scripts/bootstrap-external.sql:89`, `core/.../db/migration/V10__audit_log_table.sql:15-16,39`, `V9__admin_user_table.sql:27`

Flyway 마이그레이션은 감사로그를 tamper-evident로 설계한다 — V10:15-16 주석이 "APP_ADMIN gets SELECT + INSERT only. NO UPDATE, NO DELETE … Tampering requires DBA-level privilege"라 명시하고 V10:39도 `GRANT SELECT, INSERT`만 부여한다. 그러나 두 부트스트랩이 admin-app이 접속하는 APP_ADMIN_USER에 `GRANT ALL PRIVILEGES`를 줘서 컬럼·DML 단위 GRANT가 전부 무의미해진다. admin-app 런타임 principal이 사실상 DBA다. admin-app 침해(SQLi/RCE/자격증명 유출) 시 audit_log를 DELETE한 뒤 해시체인을 재계산·UPDATE하여 위조 가능 — `AuditChainVerifier`는 저장 필드로 재계산해 비교하므로 prevention도 detection도 우회된다. (cross-tenant 관리에 EXEMPT ACCESS POLICY가 필요해 admin-app은 VPD 바인딩 계정을 못 쓰고 이 계정만 쓴다.)

**개선 방향.** 런타임 계정에서 GRANT ALL 제거, 마이그레이션의 컬럼·DML 단위 GRANT만으로 동작하도록 축소. Flyway DDL 권한은 별도 마이그레이션 전용 계정으로 분리, 런타임은 audit_log에 SELECT+INSERT만. "런타임 계정은 audit_log UPDATE/DELETE 불가" Testcontainers 회귀 테스트 추가.

### 4. `rp-mfa-verify-no-ratelimit` — 2차 인증 온라인 브루트포스

- **STRIDE**: S, D · **경계**: 관리자 인증 · **SaaS/온프렘 둘 다**
- **위치**: `MfaController.java:56-84`, `TotpService.java:53-71`, `RecoveryCodeService.java:53-58`

1차 비밀번호 로그인은 계정별 lockout(5회→15분)으로 보호되지만 `/admin/api/mfa/verify`에는 시도 카운터·잠금·rate limit이 전혀 없다. 코드베이스 전체에 실제 rate limiter가 0건이고, verify 실패는 단순 401만 반환하며 `MFA_PENDING` 세션이 유지되어 동일 세션으로 무제한 재시도가 가능하다. `TotpService.verifyAt`는 현재±1 window(3코드)를 수용하므로 요청당 3/10⁶ 적중 → 평균 ~33만 요청, throttle 부재 시 스크립트로 적중 가능. (이 결함은 #1 재등록 우회를 막더라도 남는다.) NIST 800-63B가 OTP 검증 throttling을 의무화하는 표준 anti-automation 공백.

> 복구코드 브루트포스 주장은 검증에서 과장으로 판정(키스페이스가 커서 비현실적). 실질 결함은 TOTP 경로.

**개선 방향.** verify(+복구코드 consume) 실패에 계정/세션 단위 시도 카운터·잠금(1차 lockout 정책 재사용)을 적용, 임계 초과 시 세션 무효화 + 보안 알림. 실패 비례 backoff 고려.

---

## MEDIUM

### 5. `rp-ratelimit-proxy-remoteaddr` — 프록시 뒤 rate-limit 버킷 붕괴
`RateLimitFilter.java:144,164`의 IP/anon 버킷이 `req.getRemoteAddr()`만 쓰는데 어떤 프로파일에도 `server.forward-headers-strategy`가 없다(`core/application-common.yml` + `passkey-app/application{,-dev,-qa,-prod}.yml`). LB 뒤에서 모든 트래픽이 단일 IP 버킷으로 합쳐져, 한 행위자가 anon 경로 flood로 전체 테넌트의 해당 엔드포인트를 429로 막거나 정상 집계 부하만으로도 false 429가 발생한다. 온프렘은 직접 TLS 종단(무프록시)이면 이슈 소멸 → 조건부. **방향**: 신뢰 프록시 구간에서만 `forward-headers-strategy` 활성화, 배포별 IP 추출 전략 명시.

### 6. `rp-invitation-token-logged` — 초대 토큰 평문 로그 노출
`InvitationController.java:16-25`가 토큰(`inv_`+64hex, 비밀번호 설정용 bearer, 7일 TTL)을 path variable로 받고, `RequestLoggingFilter.java:63-91`이 `getRequestURI()`를 그대로 INFO 로그에 기록한다. 로그 열람 권한자가 미사용 초대 토큰을 추출해 임의 비밀번호 설정 → 계정 탈취. 비밀번호 재설정은 토큰을 POST 본문으로 받아 안전한데 초대만 비대칭 노출. PLATFORM_OPERATOR 초대면 플랫폼 전체. **방향**: 토큰을 본문/비로그 헤더로 옮기거나 RequestLoggingFilter가 민감 경로 path를 마스킹.

### 7. `cc-attestation-trust-never-enforced` — attestation 신뢰 사슬 미검증
`RegistrationFinishService.java:117`이 trust 정책을 `SELF_ALLOWED`로 하드코딩하고, verifier 빈이 `EmptyTrustAnchorProvider`로 구성된다. `enforceTrust`의 SELF_ALLOWED 분기(`NativeWebAuthnVerifier.java:243`)는 no-op이라 x5c 체인을 어떤 루트로도 PKIX 검증하지 않는다. AAGUID는 등록자가 통제하는 authData 값이고 packed self-attestation은 자기 키 서명만 통과하면 되므로, 공격자가 임의 AAGUID(예: 실제 YubiKey)를 주장하고 self-signed로 BASIC/ATT_CA를 통과 → MdsVerifier·AaguidPolicyChecker 모두 우회. "하드웨어 키만 허용"·"mdsRequired"·"AAGUID 화이트리스트" 테넌트 정책이 무력화(소프트웨어 인증기가 하드웨어로 위장 등록). VPD로 cross-tenant는 아님. **방향**: attestation을 보안 결정에 쓰는 테넌트에 `TRUST_CHAIN_REQUIRED` + 실제 제조사 trust anchor 주입. attestation 신뢰가 없는 한 AAGUID/MDS 정책을 "보증"으로 표기 금지.

### 8. `rp-pending-user-unbounded-dos` — pending user 무한 누적 OOM
`WebAuthnController.java:43-60`이 upstream 호출보다 먼저 `users.createPending()`을 호출하고, `InMemoryUserStore.java:120-128`이 매 호출마다 TTL/eviction 없이 맵에 적재(재기동 시에만 정리). `/passkey/**`가 permitAll이고 rp-app 자체 rate-limit이 없어 비인증 flood로 힙 고갈 + upstream registration/start 60/min 예산 소진. 단일 테넌트 가용성 한정(upstream IP/key 버킷이 코어·타 테넌트는 보호). **방향**: pending 엔트리 TTL/상한 + begin 엔드포인트 IP throttle, RegisterStartReq에 `@Size` 상한.

### 10. `dp-runtime-overgrant-admin-secret-tables` — passkey-app이 admin 자격증명 테이블 SELECT + 마스터키 공존
인터넷 노출 passkey-app이 접속하는 APP_RUNTIME 계정에 `admin_user`(bcrypt_hash·mfa_secret 포함), recovery_code, reset_token에 테이블 단위 `GRANT SELECT`가 있다(`V19:188` 등). 마이그레이션 주석이 밝히듯 Hibernate ddl-validate 통과용일 뿐 실제로 읽지 않는다. 동시에 passkey-app은 서명키를 열기 위해 KeyEnvelope 마스터키(`application-prod.yml:38`)를 메모리에 보유한다. passkey-app 침해 시 (1) bcrypt·recovery·reset 해시 직접 SELECT, (2) 마스터키로 `enc:v1:` mfa_secret 복호화 → at-rest 봉투 격리 무력화. admin_user는 플랫폼 전역이라 한 곳 침해로 전 운영자 자격증명 노출. **방향**: 엔티티 스캔 범위 축소 또는 민감 컬럼 제외 읽기전용 뷰에만 SELECT, 마스터키 주입 경로 재검토.

---

## LOW / INFO (요약)

| ID | 핵심 | 방향 |
|---|---|---|
| `scm-passkey-actuator-unauthenticated` | passkey-app엔 SecurityFilterChain이 없어 actuator(health 상세·prometheus)가 앱 레벨 fail-open. 노출 정보는 저가치 텔레메트리(시크릿/데이터 없음)라 low. | management.server.port 분리 또는 actuator 보호, health.show-details=when-authorized |
| `cc-recovery-code-weak-hash` / `dp-recovery-code-unsalted-low-entropy` | 복구코드 8자·~40비트 + 무염 SHA-256. DB 덤프 시 GPU로 분 단위 복원. 단 2차 인자라 단독 우회 불가(bcrypt 1차 통과 필요)·복합 전제로 low. | 엔트로피↑ 또는 per-code salt+느린 KDF(API key의 PasswordEncoder 재사용) |
| `rp-registration-scope-grants-destructive-delete` | registration scope가 패스키 DELETE까지 허용(`ApiKeyScopeResolver.java:27`). 키 유출 시 폭발반경↑. 테넌트 내 한정·DoS 등급. | DELETE를 별도 scope로 분리 |
| `cc-cosekey-rsa-no-upper-bound` | COSE RSA modulus/exponent 상한 부재(`CoseKeyParser.java:62-84`). 거대 키 1회 등록 후 인증 반복으로 검증 비용 증폭. rate-limit으로 유계. | modulus ≤4096, exponent {3,65537} 화이트리스트 |
| `admin-ui-cdn-css-no-sri` | admin 콘솔이 jsdelivr/Google Fonts CSS를 SRI·CSP 없이 로드(`admin-ui/index.html:12-15`). CDN 침해 시 CSS 기반 추출. 악용 난이도 매우 높음. | 자가호스팅 + 서버측 CSP(style-src 화이트리스트) |
| `rp-profile-endpoint-leak` | 미인증 `/admin/api/profile`이 활성 profile 목록 노출(`ProfileController.java:28-35`). fingerprinting. (vpd-off는 profile이 아니라 별도 property라 노출 안 됨 — 영향 info급.) | `active` 제거, `local` 불리언만 |
| `scm-rpapp-secretmasking-jws-drift` | rp-app SecretMaskingConverter가 core의 JWS_STANDALONE 패턴 누락. 현재 토큰 누출 경로는 없는 순수 심층방어 갭. | 두 converter 동기화 또는 공용 모듈화 + 패턴 동등성 테스트 |
| `dp-dev-master-key-in-git` | dev/local 프로파일이 실제 32바이트 마스터키를 git 평문 출하. prod/qa는 빈 디폴트로 fail-closed. 프로파일 오설정+덤프 복합 전제. | dev 키를 더미/미커밋 분리 + 운영성 프로파일에서 dev 상수 키 거부 가드 |
| `rp-missing-cross-tenant-rejection-test` (info) | `WebAuthnController.java:140-157`의 cross-tenant 토큰 거부(iss/aud)에 음성 회귀 테스트 부재. 현재 코드는 정상, 향후 리팩터 회귀 안전망 공백. | aud/iss 불일치·unknown sub·RAW↔UUID 동치성 거부 테스트 추가 |

---

## 기각된 11건 (오탐) — 요점

검증에서 반박 성공한 항목. 코드 묘사는 정확했으나 악용 전제가 무너졌다:

- **`rp-suspension-only-at-start`**: suspend가 같은 트랜잭션에서 테넌트의 모든 API 키를 revoke하고, finish/self-service도 ApiKeyAuthFilter가 매 요청 revoked 키를 401 처리 → 정지 테넌트 지속 인증 불성립.
- **`rp-traceid-unvalidated-reflect-logforge`**: Tomcat 기본 파서가 헤더 값 CR/LF를 거부 → 로그 라인 분할(위조) 불가, 길이도 8KB 캡.
- **`rp-finish-no-request-body-cap`**: Jackson 기본 StreamReadConstraints(depth 1000)로 깊은 중첩 차단, 위협모델(LB 뒤)에서 LB가 본문 크기 강제.
- **`ti-saas-vpd-off-default`**: Flyway가 VPD 정책을 무조건 부착 → EE/XE에서 env 누락 시 즉시 전면 장애(fail-closed)로 드러남. 조용한 붕괴 아님.
- **`ti-event-tables-no-rls-no-filter`**: 세 테이블의 모든 읽기 경로가 이미 테넌트 술어 보유, 읽기 주체 admin-app은 EXEMPT라 VPD 백스톱이 애초에 무효. audit_log @Filter 미적용은 전테넌트 검증을 위한 의도적 설계.
- **`ti-external-bootstrap-fixed-exempt-pw`**: EXEMPT는 VPD on에서만 의미 있는데 악용 도달 가능 환경(온프렘)은 VPD off → exempt가 no-op. (단 기저의 약한 비번 위생 문제는 #2로 별도 confirmed.)
- **`ti-aaguidpolicy-missing-self-guard`**: 진입점이 PLATFORM_OPERATOR 전용이라 현재 cross-tenant는 의도된 동작, 악용은 존재하지 않는 미래 코드 변경 전제.
- **`sdk-idtoken-no-iss-aud-validation`**: 본 repo 유일 소비자 rp-app이 이미 iss/aud를 완전 검증. 저수준 verify + 호출자 claim 대조는 표준 계층 분리 패턴.
- **`sdk-baseurl-no-tls-enforced`**: 기본값이 루프백(MITM 불가), 평문 http 수용은 모든 HTTP 클라이언트 표준 동작. 악용은 운영자 오설정 + on-path 공격자 복합 전제.
- **`rp-idtoken-verify-error-reflected`**: 검증 대상 토큰이 클라이언트 입력이 아니라 서버-서버로 발급된 것이라 공격자 도달 불가. 누설 정보(kid/alg/now)도 이미 공개.
- **`scm-external-init-default-master-key`**: KeyEnvelope AES-GCM이 키 불일치 시 부팅 크래시(fail-loud) → dev 키로 봉인된 행이 운영 앱에서 조용히 살아남지 못함.

---

## 권고 순서

1. **즉시(critical/high)**: #1 MFA 재등록 우회 → #4 MFA verify 브루트포스 → #3 admin 런타임 GRANT ALL → #2 외부 부트스트랩 비번/GRANT. #1·#4는 같은 MfaController/Filter라 함께 작업 가능하고 회귀 테스트가 핵심.
2. **단기(medium)**: #6 초대 토큰 로그(자명한 수정), #5 forward-headers, #7 attestation trust(설계 결정 동반), #8 pending DoS, #10 GRANT/마스터키 최소화.
3. **중기(low/info)**: 위 표의 방어심화·위생 항목 + 회귀 테스트 공백.

다음 단계로 개선 대상을 선별해 `2026-06-10-security-hardening-design.md` spec을 작성한다.
