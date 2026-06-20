# 전체 타임존 KST 전환 설계 (`Instant` → `OffsetDateTime` +09:00)

- 작성일: 2026-06-20
- 상태: 설계 확정 (구현 계획 작성 대기)
- 전제: **한국 사용자 전용**, 개발 단계로 **데이터 리셋 가능** (운영 실데이터 없음 → 마이그레이션/백필 불필요)

## 1. 목표와 결정 사항

### 1.1 목표
프로젝트 전반에서 시간을 다룰 때 **기준 타임존을 KST(Asia/Seoul) 하나로 못 박는다.** 사람이 시간을 보는 모든 지점 — DB 직접 조회(DBeaver), 자바 코드/디버거, admin-ui 화면 — 에서 한국 시간이 보이고, "UTC ↔ KST 변환"이라는 개념 자체를 코드베이스에서 제거한다.

### 1.2 채택한 접근 (= "2-B 전면 전환")
표현 레이어만 바꾸는 실용안이 아니라, **자바 타입까지 전부 KST로 전환**하는 전면 접근을 채택한다.

- 자바 시간 타입: `java.time.Instant` → **`java.time.OffsetDateTime`** (offset 항상 `+09:00`)
- `Clock`: `systemUTC()` → `system(Asia/Seoul)`
- `hibernate.jdbc.time_zone`: `UTC` → `Asia/Seoul`
- DB 세션 타임존: 커넥션 init SQL로 `Asia/Seoul` 고정

### 1.3 타입을 `OffsetDateTime`으로 정한 이유
`LocalDateTime`(offset 정보 소실 → JWT/만료 비교 위험)과 `ZonedDateTime`(한국은 서머타임 없어 실익 없음, 직렬화 복잡) 대신 `OffsetDateTime`을 선택한다.

- 각 값이 `2026-06-20T18:00:00+09:00`처럼 **offset을 명시적으로 보유** → 자기기술적(self-describing).
- DB `TIMESTAMP WITH TIME ZONE` 컬럼과 의미가 정확히 일치 → **컬럼 DDL 변경 불필요.**
- `isBefore`/`isAfter` 절대시각 비교가 offset을 고려해 **정확** → 만료/라이선스/JWT 로직이 안전하게 전환된다.

## 2. 아키텍처 원칙 (Single Source of Truth)

전환의 모든 결정을 지배하는 불변 원칙 4가지. 구현·리뷰 시 이 원칙 위반 여부가 1차 게이트다.

1. **표준 타입 = `OffsetDateTime`** (offset 항상 `+09:00`). `Instant`/`LocalDateTime`을 시간 표현 타입으로 신규 사용 금지.
2. **시각 생성의 단일 출처 = `Clock`** (zone = `Asia/Seoul`). 모든 "지금"은 `OffsetDateTime.now(clock)`에서. 인자 없는 `Instant.now()`/`OffsetDateTime.now()` 직접 호출 금지. (엔티티 `@PrePersist`는 예외 처리 — §3.5)
3. **절대시각 비교 원칙**: 만료·TTL·JWT 비교는 `OffsetDateTime.isBefore/isAfter`로 수행. offset이 동일(+09:00)하므로 벽시계 비교 = 절대시각 비교로 일치 → 안전.
4. **epoch 직렬화 원칙**: JWT `exp/iat/nbf`, relay token, TOTP는 **epoch 초/밀리초**로만 직렬화(`toInstant().getEpochSecond()`). 벽시계 문자열을 토큰/HMAC에 넣지 않는다(audit HMAC은 `OffsetDateTime.toString()` 일관 형식 사용 — §3.4).

이 4원칙이 아래 CRITICAL 5종을 안전하게 통과시키는 근거다.

## 3. 컴포넌트별 변경

### 3.1 Clock 레이어 (시각 생성 출처)
- `core/.../config/CoreClockConfig.java`: `Clock.systemUTC()` → `Clock.system(ZoneId.of("Asia/Seoul"))`. **단일 변경점** — admin-app/rp-app에 별도 Clock 빈 없음, core 빈을 전 모듈 공유.
- 공용 상수: `Asia/Seoul` zone / `+09:00` offset을 공용 유틸(예: `KstTime`)에 한 곳 정의 → 매직스트링 산재 방지.

### 3.2 엔티티/타입 레이어 (`Instant` → `OffsetDateTime`)
- `BaseEntity`: `createdAt`/`updatedAt` 타입 `Instant` → `OffsetDateTime`. `@PrePersist/@PreUpdate`의 시각 생성은 §3.5 방침 적용.
- 비-BaseEntity 전부: `AuditLog`, `AdminUserInvitation`, `AdminPasswordResetToken`, `SchedulerLease`, `MdsBlobCache`, `SigningKey`, `AdminUserRecoveryCode` 등 `Instant` → `OffsetDateTime`.
- 만료 메서드 시그니처: `ApiKey.isActive(...)`, `AdminUserInvitation.isPending/isExpired`, `AdminPasswordResetToken.isExpired(...)` 등의 `Instant now` 파라미터 → `OffsetDateTime now`. 비교 로직 자체는 그대로.

### 3.3 Hibernate/DB 매핑 레이어
- `hibernate.jdbc.time_zone: UTC` → `Asia/Seoul` (운영 `core/.../application-common.yml`, 테스트 `core/.../application-test.yml` 2곳).
- DB 컬럼 타입 `TIMESTAMP WITH TIME ZONE` **유지** → 컬럼 DDL 변경 불필요.
- 세션 타임존: HikariCP `connection-init-sql`로 `ALTER SESSION SET TIME_ZONE='Asia/Seoul'` 설정 (§3.6).

### 3.4 audit_log HMAC 체인
- `admin-app/.../audit/AuditLogService.java`(현재 line ~181)의 `now.toString()`이 HMAC 입력에 포함.
- `OffsetDateTime.toString()` → `2026-06-20T18:00:00+09:00` 형식으로 **일관 계산**.
- **데이터 리셋**으로 기존 `...Z`(UTC) 형식 해시가 모두 사라지므로 형식 혼재 없음 → 추가 작업 불필요. 새 체인은 KST 형식으로 일관 검증.

### 3.5 `@PrePersist` Clock 주입 — 방침 (a) zone 상수 직접 호출
엔티티는 스프링 빈이 아니라 `Clock` 주입이 까다롭다. **JPA Auditing 전환(b)은 over-engineering**으로 판단하고, **`OffsetDateTime.now(KstTime.ZONE)` 직접 호출(a)** 을 채택.

- 근거: 한국 전용·서머타임 없음으로 zone 상수면 충분. created_at/updated_at은 감사·표시용이지 만료 비교 대상이 아니라 테스트 시간고정 필요성이 낮다.

### 3.6 DB 마이그레이션 정책
- **기존 V1~V48 불변**: 적용된 Flyway 마이그레이션 수정 금지(체크섬 깨짐). `SYSTIMESTAMP`(18개 파일)는 **세션 `TIME_ZONE`을 따르므로** DEFAULT를 손대지 않아도 세션이 KST면 신규 INSERT가 KST를 찍는다.
- **신규 `V49__set_kst_session_timezone.sql`**(또는 동등 위치): 세션 타임존 KST 정렬을 문서화/적용. 단, 앱이 찍는 시간은 대부분 자바(`OffsetDateTime`)에서 오고 DB DEFAULT는 시드/직접INSERT에만 쓰임.
- **세션 타임존 고정 위치 = (a) 커넥션 init SQL**. DB 전역 `ALTER DATABASE`(b)는 권한·재기동·이식성 문제(외부 SE 권한 제약 이력)로 회피. 자바만(c)은 DB 직접 조회 KST 보장이 깨져 목표 미달.
- dev/local seed(`R__seed_dev_tenant.sql`, `R__seed_local_tenant.sql`)의 `SYSTIMESTAMP`는 리셋 후 재적용 시 (a)에 의해 자동 KST → 별도 수정 불필요.

### 3.7 서비스/비즈니스 로직 레이어
- `clock.instant()` 사용처 → `OffsetDateTime.now(clock)`.
- **JWT**(`IdTokenIssuer`, `LicenseVerifier`): exp/iat 계산을 `OffsetDateTime` 기반으로 하되 **epoch 경유**(`toInstant().getEpochSecond()`). JWT 표준은 NumericDate(epoch)라 offset 무관하게 동일 숫자 → 검증 로직 자동 안전.
- **relay**(`rp-app/.../RegRelayCodec.kt`), **TOTP**: 이미 epoch 기반. 시각 출처만 `Clock` 경유로 통일, 비교 로직 무변경.

### 3.8 프론트(admin-ui) 레이어
- 백엔드가 `+09:00` offset 포함 ISO 문자열 응답.
- `new Date("...+09:00")`는 정확한 절대시각으로 파싱되고 `Intl.DateTimeFormat({timeZone:'Asia/Seoul'})`가 KST로 포맷 → **결과 동일, 이중변환 없음.** 원칙적으로 무변경으로 정상 동작.
- 단 백엔드가 `Z`(UTC)를 더는 주지 않으므로 **8개 변환 지점 회귀 검증** 필요: `formatDateTime.ts`, `TenantDetailPage`, `TenantsListPage`, `CredentialsTab`, `CredentialDetailDialog`, `MdsStatusTab`, `ApiKeysTab`, `AuditTab`, `AdminUsersTab`.

## 4. 영향 범위 — CRITICAL 5종 (전수 조사 결과)

| # | 지점 | 파일 | 전환 후 안전 근거 |
|---|------|------|------------------|
| 1 | audit HMAC 체인 | `AuditLogService.java` | 리셋으로 형식 혼재 없음, `OffsetDateTime.toString()` 일관(§3.4) |
| 2 | License 만료 검증 | `LicenseVerifier.java`, `LicenseStateMachine` | offset 포함 절대시각 비교로 정확(원칙 3) |
| 3 | Relay token TTL | `RegRelayCodec.kt` | epoch 기반, 개발단계라 진행중 토큰 무시 가능(원칙 4) |
| 4 | API Key 활성화 | `ApiKey.java` | offset +09:00 적용 후 만료 경계 정확 |
| 5 | Invitation/Reset token 만료 | `AdminUserInvitation.java`, `AdminPasswordResetToken.java` | 동일 — 절대시각 비교 |

## 5. 테스트 & 검증 전략

### 5.1 테스트 코드 영향 (조사: 40+ 파일)
- 고정 Clock: `Clock.fixed(Instant.parse("...Z"), ZoneOffset.UTC)` → `Clock.fixed(<동일 instant>, ZoneId.of("Asia/Seoul"))`. 기준 instant는 그대로, zone만 KST → `OffsetDateTime.now(clock)`이 `+09:00` 산출.
- 하드코딩 단언: `Instant` 비교 → `OffsetDateTime.parse("...+09:00")` 또는 `.toInstant()` 비교로 조정.
- `"Z"` suffix 가정 단언: `+09:00`로 수정.

### 5.2 집중 검증 케이스 (CRITICAL 5종 매핑)
1. audit: 리셋 후 새 로그 체인이 `+09:00` 형식으로 verifier 통과(IT).
2. JWT: 발급→검증 라운드트립, epoch가 KST/UTC 무관 동일 절대시각 단언(IT).
3. 라이선스: KST `OffsetDateTime`으로 만료/유효 정확 판정.
4. API key / invitation / reset token: offset 적용 후 만료 경계 테스트.
5. 프론트 8개 페이지: `+09:00` 응답 시 표시값 이중변환 없이 정확.

### 5.3 빌드 게이트 함정 (기존 경험 반영)
- `./gradlew build`는 SliceConfig 충돌·Oracle 컨테이너 경합으로 항상 실패 → 머지 게이트로 쓰지 않음.
- 회귀 판정은 **base(main) worktree 대조**로 "새로 깨진 테스트만" 식별. 슬라이스 `SecurityPolicyService` 누락·core `ORA-01031`은 pre-existing로 분류.
- 세션 타임존·DB 변경은 **실제 Oracle Testcontainers로 검증**(inspection이 못 잡는 함정 다수).

### 5.4 실DB 부팅 최종 검증
- dev 부팅 절차(`SPRING_PROFILES_ACTIVE` env, APP_OWNER 접속, flyway 소문자 식별자, V8 repair)로 로컬 1회 부팅.
- **DBeaver로 실제 컬럼값이 KST(+09:00)로 보이는지 육안 확인** — 2-B 목표 달성의 최종 증거.

## 6. 비목표 (Out of Scope)
- 멀티존/해외 사용자 지원 (한국 전용 전제).
- 운영 실데이터 마이그레이션/백필 (데이터 리셋 전제).
- 관련 없는 리팩터링.

## 7. 리스크 요약
- 가장 큰 리스크는 데이터가 아니라 **직렬화 형식 변화**(audit HMAC, 프론트 이중변환)였고, 각각 §3.4·§3.8 + 리셋 전제로 해소.
- 잔존 리스크: 40+ 테스트 파일 조정 중 누락 → §5.3 base 대조로 회귀 식별.
