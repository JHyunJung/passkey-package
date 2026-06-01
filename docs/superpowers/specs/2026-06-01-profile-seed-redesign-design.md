# 프로필별 초기 시드 데이터 재정의 — 설계 문서

**작성일:** 2026-06-01
**목표:** dev/qa/prod/local 프로필별로 어떤 초기 데이터(운영자 계정·테넌트·API 키)를 가질지 명확히 분리하고, 로그인 화면의 데모 UI도 같은 프로필 기준으로 게이팅한다.

---

## 1. 배경 / 현재 문제

현재 시드는 테스트 편의를 위해 작성됐고 프로필 격리가 안 돼 있다.

- **`db/migration`(모든 프로필 공통)에 데모 데이터가 섞여 있음:**
  - `V11__seed_admin_user.sql` → `alice`/`bob` 계정(알려진 임시 비번 `alice-temp-pw` 등)이 **prod에도 들어감**
  - `V23__admin_role_separation.sql` → `demo-rp` 테넌트가 **prod에도 들어감**
  - `V26`(aaguid_policy)·`V27`(snapshot)에 demo-rp 관련 파생 시드
- **`db/dev/R__dev_seed.sql`(dev 전용)** → `acme/bar/foo` 테넌트 + known-plaintext API 키. rpId 전부 `localhost`.
- `db/qa`, `db/prod` 디렉터리 없음 → qa/prod는 db/migration만 읽음(공통 시드가 그대로 들어감).
- **로그인 화면(`LoginPage.tsx`)의 "데모: 어떤 role로 로그인할까요?" 카드**가 프로필 게이팅 없이 **모든 프로필(prod 포함)에 노출**되며, 카드 코드에 `alice-temp-pw` 같은 비번이 박혀 prod 번들에 포함됨.

**핵심 결함:** prod 환경에 알려진-비번 계정·데모 테넌트·데모 로그인 UI가 자동으로 들어간다.

---

## 2. 설계 방향 (확정된 결정)

프로필별 **완전 분리**. 공통 마이그레이션에는 스키마와 인프라 싱글톤만 두고, 데모 데이터는 프로필 전용 시드 경로로 옮긴다.

| 결정 항목 | 확정값 |
|---|---|
| 시드 철학 | 프로필별 완전 분리 |
| prod 시드 | 없음 (빈 디렉터리) |
| qa 시드 | 운영자 계정만 |
| dev 시드 | 계정 + `dev-passkey` 테넌트(rpId=도메인) |
| local 시드 (신규 프로필) | 계정 + `demo-rp` 테넌트(rpId=localhost) |
| 계정 시드 | `alice`(PLATFORM_OPERATOR) 하나만. **bob 제거** |
| 계정 중복 처리 | 공유 경로 `db/seed-common` |
| 로그인 데모 UI | dev/local에서만 노출 |
| 마이그레이션 전략 | DB 초기화(clean) 전제. 기존 V11/V23/V26/V27의 **시드 INSERT만 제거**(스키마 DDL 유지), 데이터는 R__ 시드로 이관. clean 재마이그레이션으로 checksum 새로 기록 |

---

## 3. 아키텍처 — Flyway locations 구조

```
core/src/main/resources/db/
├ migration/      스키마 + 인프라 싱글톤(signing_key·mds_blob_cache·security_policy)
│                 ← 모든 프로필 공통. 데모 데이터 없음.
├ seed-common/    운영자 계정 alice (PLATFORM_OPERATOR)
│                 ← local·dev·qa 공통 (prod 제외)
├ local/          테넌트 demo-rp (rpId=localhost)         ← local 만
├ dev/            테넌트 dev-passkey (rpId=도메인)        ← dev 만
├ qa/             (비움 — 계정은 seed-common 이 담당)     ← qa 만
└ prod/           (빈 디렉터리)                           ← prod 만
```

### 프로필별 flyway locations

| 프로필 | `spring.flyway.locations` |
|---|---|
| (base `application.yml`) | `classpath:db/migration` |
| **local** | `classpath:db/migration,classpath:db/seed-common,classpath:db/local` |
| **dev** | `classpath:db/migration,classpath:db/seed-common,classpath:db/dev` |
| **qa** | `classpath:db/migration,classpath:db/seed-common,classpath:db/qa` |
| **prod** | `classpath:db/migration,classpath:db/prod` |

> prod는 `seed-common`을 **포함하지 않는다** → 운영자 계정조차 자동 생성 안 됨(최초 운영자는 별도 부트스트랩/CLI로 생성, 본 작업 범위 밖).

---

## 4. 시드 내용 명세

### 4.1 `db/seed-common/R__seed_operators.sql` (local·dev·qa)

- `alice@crosscert.com`, role `PLATFORM_OPERATOR`, enabled `Y`
- bcrypt 해시: 기존 V11의 alice 해시 재사용(`alice-temp-pw`에 대응)
- **idempotent**: 이미 존재하면 skip(이메일 UNIQUE 기준 `MERGE`/`NOT EXISTS`)
- bob 계정은 만들지 않음

### 4.2 `db/local/R__seed_local_tenant.sql` (local 전용)

테넌트 1개 — 로컬 패스키 테스트용.

| 필드 | 값 |
|---|---|
| slug | `demo-rp` |
| display_name | `Demo RP` |
| rp_id | `localhost` |
| rp_name | `Demo RP` |
| allowed_origins | `http://localhost:9090` |
| accepted_formats | `none`, `packed` |
| tenantId | 결정적 RAW(16) (로컬 전용 고정값) |
| API key | known-plaintext (`pk_devlocal...` 형태), `registration`+`authentication` scope |

- tenant_aaguid_policy(ANY 모드), tenant_webauthn_snapshot도 같이 시드(기존 V26/V27이 db/migration에서 빠지므로 여기서 채움).

### 4.3 `db/dev/R__seed_dev_tenant.sql` (dev 전용)

기존 `R__dev_seed.sql`(acme/bar/foo)을 **교체**. 테넌트 1개 — 서버 배포용.

| 필드 | 값 |
|---|---|
| slug | `dev-passkey` |
| display_name | `Dev Passkey` |
| rp_id | `dev-passkey.crosscert.com` |
| rp_name | `Dev Passkey` |
| allowed_origins | `https://dev-passkey.crosscert.com` |
| accepted_formats | `none`, `packed` |
| tenantId | 결정적 RAW(16) (dev 전용 고정값, local과 다름) |
| API key | known-plaintext (`pk_devserver...` 형태), `registration`+`authentication` scope |

- tenant_aaguid_policy / snapshot 동반 시드.

### 4.4 `db/qa/` — 빈 디렉터리 (또는 placeholder 주석 파일)

계정은 seed-common이 담당하므로 qa 전용 시드는 없음. Flyway가 빈 location을 허용하므로 디렉터리만 존재하면 됨(빈 디렉터리는 git이 추적 못 하므로 `.gitkeep` 또는 주석 SQL 1개 둠).

### 4.5 `db/prod/` — 빈 디렉터리

placeholder만(`.gitkeep` 또는 주석 SQL). prod는 어떤 데모 데이터도 받지 않음.

---

## 5. 기존 db/migration 시드 분리

**왜 V 파일 수정이 불가피한가:** 데모 데이터(alice/bob 계정, demo-rp 테넌트)가 `db/migration`의 `V11`/`V23`/`V26`/`V27`에 박혀 있어, 이 파일들이 모든 프로필에서 실행된다. R__ 시드를 추가만 해서는 "prod에 alice/bob/demo-rp가 안 들어가게" 만들 수 없다 — db/migration의 V11/V23이 prod에서도 그대로 돌기 때문이다. 따라서 **이 V 파일들에서 시드 INSERT 구문을 제거**해야 진짜 분리가 된다.

**checksum 문제 해소 = DB 초기화 전제:**
- V 파일을 수정하면 Flyway checksum이 바뀌어 기존 이력과 충돌한다.
- 사용자가 "DB 초기화 후 재생성"을 택했으므로, **`flyway clean` + 재마이그레이션**으로 이력을 새로 쌓아 checksum 충돌을 흡수한다. 운영 DB가 아직 없어 안전하다.

**구체 작업:**
- `V11`/`V23`/`V26`/`V27`에서 **시드 데이터(INSERT/MERGE) 구문만 제거**하고 **스키마 DDL(ALTER/CREATE/CHECK 등)은 유지**한다.
- 제거된 데이터는 전부 R__ 시드(`seed-common`/`local`/`dev`)로 이관한다.
- `db/dev/R__dev_seed.sql`(acme/bar/foo)은 새 `R__seed_dev_tenant.sql`로 교체.

> V 파일에서 "데이터만 들어내고 스키마는 남긴다"가 핵심 — 스키마 변경 이력은 보존하면서 시드만 프로필 경로로 옮긴다.

---

## 6. 로그인 데모 UI 게이팅

### 6.1 `/admin/api/profile` 확장

현재 `{ active: string[] }`를 반환. LoginPage가 `active.includes('dev')`만 확인. **`local`도 인식**하도록 프론트 조건을 `active.includes('dev') || active.includes('local')`로 변경(서버는 이미 active 프로필 배열을 주므로 백엔드 변경 불필요).

### 6.2 `LoginPage.tsx`

- 데모 role 카드 블록 전체를 **`devOrLocal`일 때만 렌더링**(현재는 무조건 렌더링).
- prefill: PLATFORM_OPERATOR = `alice@crosscert.com` / `alice-temp-pw`. **RP_ADMIN 카드 제거**(bob 삭제).
- prod/qa: 카드 자체가 안 보임 → 이메일/비번 입력 폼만.

> 결과: 비밀번호 문자열이 박힌 데모 UI가 dev/local 번들 흐름에서만 의미를 갖고, prod에서는 렌더 경로가 차단됨(코드에는 남지만 노출 안 됨).

---

## 7. 신규 `application-local.yml`

현재 local 프로필 설정 파일이 없다. 신규 생성:

- **DB 접속**: dev와 동일한 로컬 Oracle(`jdbc:oracle:thin:@localhost:1521/XEPDB1`, APP_ADMIN_USER/APP_RUNTIME_USER) 재사용.
- **master-key**: dev와 동일한 dev용 고정 키(`jDKp...`).
- **flyway locations**: `db/migration,db/seed-common,db/local`.
- **기타**: dev yml을 베이스로 하되 flyway locations만 다름. dev에만 있는 설정(있으면)도 복사.

> local과 dev가 **같은 로컬 Oracle DB**를 쓰면 시드가 섞일 수 있다(demo-rp와 dev-passkey가 한 DB에 공존). slug·tenantId가 다르므로 충돌은 없으나, 한 DB에 두 프로필 시드가 같이 쌓인다. 이는 허용 가능(둘 다 idempotent, slug 다름). 완전 격리가 필요하면 local은 별도 스키마/DB를 쓰도록 후속 조정 가능(본 작업 범위 밖).

---

## 8. 영향받는 파일

**신규:**
- `core/src/main/resources/db/seed-common/R__seed_operators.sql`
- `core/src/main/resources/db/local/R__seed_local_tenant.sql`
- `core/src/main/resources/db/dev/R__seed_dev_tenant.sql` (기존 R__dev_seed.sql 교체/리네임)
- `core/src/main/resources/db/qa/.gitkeep` (또는 주석 SQL)
- `core/src/main/resources/db/prod/.gitkeep` (또는 주석 SQL)
- `admin-app/src/main/resources/application-local.yml`

**수정:**
- 기존 `db/migration/V11`,`V23`,`V26`,`V27` — 시드 INSERT 구문 제거(스키마 DDL 유지)
- `admin-app/src/main/resources/application-dev.yml` — flyway locations에 seed-common 추가
- `admin-app/src/main/resources/application-qa.yml` — flyway locations 정의(seed-common 포함)
- `admin-app/src/main/resources/application-prod.yml` — flyway locations에 db/prod 추가
- `admin-ui/src/pages/LoginPage.tsx` — 데모 카드 게이팅 + RP_ADMIN 카드 제거

**삭제:**
- `core/src/main/resources/db/dev/R__dev_seed.sql` (새 파일로 교체)

---

## 9. 데이터 흐름

```
[부팅: SPRING_PROFILES_ACTIVE=<profile>]
        │
        ▼
[Flyway: locations 결정]
   local → migration + seed-common + local
   dev   → migration + seed-common + dev
   qa    → migration + seed-common + qa
   prod  → migration + prod
        │
        ▼
[V__ 마이그레이션(스키마+싱글톤) → R__ 시드(idempotent) 적용]
        │
        ▼
[프로필별 초기 데이터 확정]
   local: alice + demo-rp(localhost)
   dev:   alice + dev-passkey(도메인)
   qa:    alice
   prod:  (없음)
```

---

## 10. 검증 / 테스트

- **부팅 검증**: 각 프로필로 admin-app 기동 → Flyway 성공 + 기대 데이터 존재 확인(테넌트 row, 계정 row).
- **prod 격리 검증**: prod 프로필 부팅 후 `tenant`·`admin_user` 0건(또는 운영자 부트스트랩분만) 확인.
- **로그인 UI**: dev/local에서 데모 카드 노출, prod에서 미노출(프론트 조건).
- **sample-rp 연동**: local은 `pk_devlocal...`, dev는 `pk_devserver...` 환경변수로 sample-rp 기동 → 등록/인증 ceremony 성공.
- **기존 IT 영향**: AdminFlowIT 등 alice 로그인에 의존하는 테스트가 seed-common 경로에서 alice를 받는지 확인. bob 의존 테스트가 있으면 수정(범위에 포함).
- Oracle IT는 이 환경에서 flaky하므로 컴파일 + 단위/슬라이스 테스트로 게이트, 부팅 검증은 수동.

---

## 11. 리스크 / 한계

- **기존 V 파일 수정 → checksum 변경**: DB clean 재마이그레이션 전제로만 안전. 이미 데이터가 있는 환경에서는 적용 불가(본 작업은 dev 초기화 전제).
- **bob 의존 테스트**: bob 계정을 쓰는 IT가 깨질 수 있음 — 식별해 함께 정리.
- **local/dev 동일 DB 공존**: 같은 로컬 Oracle에 두 프로필 시드가 쌓임. slug 분리로 충돌은 없으나 데이터가 섞임(허용).
- **prod 최초 운영자 생성 수단**: 본 작업 범위 밖(별도 부트스트랩/CLI 필요). 설계는 "prod 시드 없음"까지만 책임.
