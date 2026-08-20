# DB 계정·롤 `PSK_` 접두사 전면 반영

- 작성일: 2026-08-20
- 브랜치: `psk-prefix-rename`
- 상태: 설계 승인 완료 → 구현 대기

## 배경

QA 대상 Oracle DB 에 계정과 롤이 `PSK_` 접두사를 붙인 이름으로 생성되었다.
코드베이스는 아직 구 이름을 쓰고 있어 그대로 배포하면 QA 기동이 실패한다.

**Flyway 는 아직 한 번도 실행되지 않았다.** 따라서 이미 적용된 마이그레이션을
고칠 때 발생하는 체크섬 문제(`flyway repair`)는 이번 작업에 해당하지 않는다.
V1/V4 를 직접 수정해 히스토리를 깨끗하게 유지한다.

## 변경 대상 식별자

| 구분 | 기존 | 변경 후 | 길이 |
|---|---|---|---|
| 롤 | `APP_RUNTIME` | `PSK_APP_RUNTIME` | 15 |
| 롤 | `APP_ADMIN` | `PSK_APP_ADMIN` | 13 |
| 계정 | `APP_OWNER` | `PSK_APP_OWNER` | 13 |
| 계정 | `APP_RUNTIME_USER` | `PSK_APP_RUNTIME_USER` | 20 |
| 계정 | `APP_ADMIN_USER` | `PSK_APP_ADMIN_USER` | 18 |

전부 30자 이내이므로 구버전 Oracle 식별자 길이 제한에도 안전하다.

`APP_OWNER` 는 단순한 계정이 아니라 **스키마 소유자**다. 이름이 바뀌면
스키마명이 바뀌고, 스키마명을 참조하는 모든 지점이 함께 바뀌어야 한다.

## 영향 범위

총 **134개 파일**.

| 디렉터리 | 파일 수 | 성격 |
|---|---|---|
| `docs/` | 70 | 설계문서·감사기록 |
| `admin-app/` | 31 | Java 4 + YAML 5 + IT 다수 |
| `core/` | 15 | V1/V4 마이그레이션 + SigningKeyProvider + IT |
| `scripts/` | 9 | DDL·bootstrap·reset (QA 반입본) |
| `passkey-app/` | 5 | ApiKeyLookupService + YAML |
| `deploy/`, 루트 | 4 | compose·README |

식별자별 출현 횟수:

| 식별자 | 출현 |
|---|---|
| `APP_OWNER` | 856 |
| `APP_ADMIN` (단독) | 371 |
| `APP_RUNTIME` (단독) | 201 |
| `APP_ADMIN_USER` | 192 |
| `APP_RUNTIME_USER` | 58 |

## ⚠️ 핵심 함정 — 부분문자열 충돌

`APP_ADMIN` 은 `APP_ADMIN_USER` 의 부분문자열이다. `APP_RUNTIME` 과
`APP_RUNTIME_USER` 도 마찬가지다. 순진하게 치환하면 이름이 깨지거나
`PSK_PSK_` 같은 이중 접두사가 생긴다.

**긴 이름부터 치환하고, 단어 경계(`\b`)를 적용한다.**

```
1순위  APP_RUNTIME_USER  →  PSK_APP_RUNTIME_USER
2순위  APP_ADMIN_USER    →  PSK_APP_ADMIN_USER
3순위  APP_RUNTIME       →  PSK_APP_RUNTIME
4순위  APP_ADMIN         →  PSK_APP_ADMIN
5순위  APP_OWNER         →  PSK_APP_OWNER
```

치환 스크립트는 GNU/BSD sed 차이를 피하기 위해 Python 으로 작성하고,
정규식은 `\bAPP_RUNTIME_USER\b` 형태로 경계를 명시한다. 이미 `PSK_` 가
붙은 토큰을 다시 치환하지 않도록 negative lookbehind(`(?<!PSK_)`)를 건다.

## 설계

### 1. 스키마명 외부화 (Java 14곳 / 7파일)

`APP_OWNER.` 가 **실행되는 SQL 문자열 안에** 하드코딩돼 있다. 컴파일은
통과하고 런타임에만 깨지므로 정적 검사로는 잡히지 않는 위험 지점이다.

대상 파일과 건수. **실행 SQL** 은 반드시 고쳐야 하는 런타임 파손 지점이고,
**주석** 은 문서 일관성 차원에서 함께 고친다.

| 파일 | 실행 SQL | 주석 | 합계 |
|---|---|---|---|
| `admin-app/.../mds/MdsHistoryService.java` | 6 | 3 | 9 |
| `passkey-app/.../security/ApiKeyLookupService.java` | 2 | 2 | 4 |
| `core/.../jwt/SigningKeyProvider.java` | 1 | 2 | 3 |
| `admin-app/.../audit/AuditChainBackfillService.java` | 2 | 0 | 2 |
| `admin-app/.../audit/AuditLogService.java` | 1 | 0 | 1 |
| `admin-app/.../mds/MdsBlobStore.java` | 1 | 0 | 1 |
| `admin-app/.../mds/MdsAdminController.java` | 1 | 0 | 1 |
| **합계** | **14** | **7** | **21** |

14곳 전부 평범한 Java 문자열 리터럴(JdbcTemplate / CallableStatement)이며
`@Query` 애너테이션은 없다. 따라서 런타임 주입이 가능하다.

문자열 치환에 그치지 않고 **설정값으로 외부화**한다. 다음에 스키마명이 또
바뀌어도 코드를 고칠 필요가 없다.

```yaml
# core/src/main/resources/application-common.yml
app:
  db:
    schema: ${DB_SCHEMA:PSK_APP_OWNER}
```

```java
// AS-IS
"SELECT ... FROM APP_OWNER.mds_sync_history "

// TO-BE
"SELECT ... FROM " + schema + ".mds_sync_history "
```

주입 방식은 각 클래스가 이미 쓰는 생성자 주입을 따른다
(`@Value("${app.db.schema}") String schema`).

**SQL 인젝션 우려 없음**: 값의 출처가 배포자가 통제하는 설정 파일이며
사용자 입력이 아니다. 다만 방어적으로 부팅 시 식별자 패턴
(`^[A-Za-z][A-Za-z0-9_]*$`)을 검증해 오설정을 조기에 잡는다.

### 2. Flyway 설정

`admin-app/src/main/resources/application.yml`:

```yaml
flyway:
  schemas: PSK_APP_OWNER          # was APP_OWNER
  default-schema: PSK_APP_OWNER   # was APP_OWNER
  user: ${SPRING_FLYWAY_USER:PSK_APP_OWNER}
```

### 3. 마이그레이션 V1 / V4

Flyway 미실행 상태이므로 **직접 수정**한다.

| 파일 | 변경 |
|---|---|
| `V1__baseline_schema.sql` | `TO APP_ADMIN` 93건 → `TO PSK_APP_ADMIN`<br>`TO APP_RUNTIME` 41건 → `TO PSK_APP_RUNTIME` |
| `V4__grant_security_incident_to_app_runtime.sql` | `TO APP_RUNTIME` 1건 → `TO PSK_APP_RUNTIME` |

두 파일 모두 `CREATE USER` / `CREATE ROLE` 은 포함하지 않는다(계정·롤 생성은
`scripts/full-schema-part1-accounts.sql` 담당). GRANT 대상 이름만 바뀐다.

V4 는 파일명에 `app_runtime` 이 들어 있으나 **파일명은 바꾸지 않는다.**
Flyway 는 버전(`V4`)과 체크섬으로 식별하며, 파일명 변경은 이력 추적만
어렵게 만든다.

### 4. YAML 설정값

| 파일 | 라인 | 변경 |
|---|---|---|
| `admin-app/application.yml` | 13,14,19 | `schemas`/`default-schema`/`user` |
| `admin-app/application-dev.yml` | 23 | `username: PSK_APP_ADMIN_USER` |
| `admin-app/application-local.yml` | 14 | `username: PSK_APP_ADMIN_USER` |
| `passkey-app/application-dev.yml` | 24 | `username: PSK_APP_RUNTIME_USER` |

주석 안의 계정명도 함께 치환한다(문서 일관성).

### 5. DDL 스크립트 (`scripts/` 9개)

`full-schema-part1-accounts.sql` — `CREATE ROLE` 2건, `CREATE USER` 3건,
`GRANT ... TO` 전부. `full-schema-part2-schema.sql` — 객체 권한 GRANT.
그 외 `bootstrap-schema.sql`, `bootstrap-external*.sql`, `reset-app-owner*.sql`,
`init-*.sh` 도 동일 규칙으로 치환한다.

`reset-app-owner*.sql` 은 파일명에 `app-owner` 가 들어 있으나 **파일명은
유지**한다(하이픈 표기이며 식별자가 아니다).

### 6. 테스트 (29개 파일)

테스트는 `OracleContainer.withUsername("APP_OWNER")` 로 컨테이너를 직접
만들고 `bootstrap-schema.sql` 로 스키마를 올린다. 즉 **자기완결적**이다.
DDL 과 테스트를 일관되게 치환하면 그대로 통과해야 한다.

정리 SQL 의 `DELETE FROM APP_OWNER.<table>` 형태도 함께 치환된다.

### 7. 문서 (70개)

`docs/` 전부 치환한다(사용자 결정). 과거 감사기록의 역사적 정확성보다
전체 일관성을 우선한다.

## 검증

### 자동 검사

```bash
# 1. 잔존 검사 — 0건이어야 한다
grep -rE '\bAPP_(OWNER|RUNTIME|ADMIN)(_USER)?\b' . \
  --exclude-dir=build --exclude-dir=.git | grep -v PSK_

# 2. 오염 검사 — 전부 0건이어야 한다
grep -rE 'PSK_PSK_' .
grep -rE 'PSK_APP_(ADMIN|RUNTIME)_USER_USER' .
grep -rE 'PSK_APP_(ADMIN|RUNTIME)_PSK_' .

# 3. 건수 보존 — 치환 전후 총합이 같아야 한다
#    APP_OWNER 856 / APP_ADMIN 371 / APP_RUNTIME 201
#    APP_ADMIN_USER 192 / APP_RUNTIME_USER 58
```

### 빌드·테스트

```bash
./gradlew :core:test :admin-app:test :passkey-app:test
```

⚠️ Testcontainers Oracle 기동이 필요하다. 이 환경에서는 컨테이너 경합으로
전체 빌드가 불안정한 이력이 있으므로, 실패가 나오면 **base(main) worktree
에서 같은 테스트를 돌려 대조**해 이번 변경으로 깨진 것인지 판정한다.
(`project_full_build_preexisting_traps` 참고)

### 수동 확인

- `admin-app` 부팅 시 Flyway 가 `PSK_APP_OWNER` 스키마에 V1~V4 적용
- 스키마 외부화가 적용된 SQL 이 실제로 동작 (MDS 이력 조회, API 키 조회)

## QA 반입 번들 재생성

DDL 과 이미지가 모두 바뀌므로 전면 재생성한다.

1. 이미지 3개 **`--platform linux/amd64`** 재빌드
   (개발 Mac 은 arm64 — 그대로 쓰면 x86_64 서버에서 `exec format error`)
2. `nginx`/`redis` 는 기존 amd64 이미지 재사용
3. `scripts/` DDL 2종 + `DBA-요청서.md` 갱신본 반영
4. `sql/seed-admin-user.sql` — 스키마 소유자명 반영
5. `deploy/.env.qa-template` — `DB_OWNER_USER` 등 기본값 갱신
6. `tar.gz` + `sha256` 재생성, Linux 컨테이너에서 추출 검증

## 범위 밖

- 계정 **비밀번호** 변경 (이름만 바뀜)
- 테이블·컬럼·인덱스 등 스키마 객체명 (변경 없음)
- `passkey_rp` 등 별도 저장소 (이 저장소 범위 밖)
- 파일명 변경 (`V4__...app_runtime.sql`, `reset-app-owner.sql` 유지)

## 리스크

| 리스크 | 완화 |
|---|---|
| 부분문자열 오염으로 이름 깨짐 | 긴 이름 우선 + 단어경계 + lookbehind, 치환 후 3종 검사 |
| 스키마 외부화 누락으로 런타임 실패 | 14곳 전수 목록화, 잔존 grep 0건 확인 |
| Testcontainers 경합으로 오탐 | base worktree 대조 판정 |
| 번들 아키텍처 불일치 | `docker image inspect` 로 amd64 확인 + tarball 내부까지 검증 |
